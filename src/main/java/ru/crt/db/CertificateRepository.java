package ru.crt.db;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.UnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ru.crt.client.KnownLog;
import ru.crt.x509.DomainName;

/**
 * Всё обращение к БД.
 */
@Repository
public class CertificateRepository {

    private static final Logger log = LoggerFactory.getLogger(CertificateRepository.class);

    private static final String INSERT_CERT = """
            INSERT INTO certificate (issuer_ca_id, log_url, log_index, sha256, serial,
                                     subject_dn, issuer_dn, not_before, not_after, entry_ts, is_precert, der)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    /**
     * Кеш issuer DN -> ca.id; попадают только зафиксированные id — из откатившейся
     * транзакции id указывал бы на несуществующую строку.
     */
    private final Cache caIds;

    public CertificateRepository(JdbcTemplate jdbc, PlatformTransactionManager txManager,
                                 CacheManager caches) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
        this.caIds = caches.getCache("caIds");
    }

    /** Строка для вставки, которую готовит CtSyncService из разобранной записи лога. */
    public record NewCert(
            String logUrl,
            long logIndex,
            byte[] der,
            String sha256,
            String serial,
            String subjectDn,
            String issuerDn,
            LocalDateTime notBefore,
            LocalDateTime notAfter,
            LocalDateTime entryTs,
            boolean precert,
            Set<String> domains) {
    }

    // --- ct_log ---

    public void upsertLog(String url, String description, String operator) {
        jdbc.update("""
                MERGE INTO ct_log t USING (VALUES ?) s(url) ON t.url = s.url
                WHEN MATCHED THEN UPDATE SET t.description = ?, t.operator = ?
                WHEN NOT MATCHED THEN INSERT (url, description, operator) VALUES (?, ?, ?)
                """, url, description, operator, url, description, operator);
    }

    public List<KnownLog> getKnownLogs() {
        return jdbc.query("SELECT url, description, operator FROM ct_log ORDER BY url",
                (rs, _) -> new KnownLog(rs.getString(1), rs.getString(2), rs.getString(3)));
    }

    public Map<String, Long> loadSyncProgress() {
        Map<String, Long> out = new HashMap<>();
        jdbc.query("SELECT url, last_synced_index FROM ct_log",
                (RowCallbackHandler) rs -> out.put(rs.getString(1), rs.getLong(2)));
        return out;
    }

    /** Ширина ct_log.last_error в schema.sql — обрезаем здесь, а не у вызывающих. */
    private static final int MAX_ERROR_LENGTH = 1024;

    /** Лог догнан до конца: фиксируем tree_size и снимаем прежнюю ошибку. */
    public void markCaughtUp(String url, long treeSize) {
        updateLogState(url, treeSize, null, null);
    }

    /** Пачка вставлена: двигаем прогресс, обновляем tree_size и снимаем ошибку. */
    public void advance(String url, long treeSize, long lastIndex) {
        updateLogState(url, treeSize, lastIndex, null);
    }

    /** Лог отвалился: ошибка запоминается, прогресс и tree_size не трогаются. */
    public void markFailed(String url, String error) {
        updateLogState(url, null, null, error);
    }

    private void updateLogState(String url, Long treeSize, Long lastIndex, String error) {
        if (error != null && error.length() > MAX_ERROR_LENGTH) {
            error = error.substring(0, MAX_ERROR_LENGTH);
        }
        // updated_at — тем же UTC-локальным временем, что и остальные даты
        jdbc.update("""
                UPDATE ct_log SET tree_size = COALESCE(?, tree_size),
                                  last_synced_index = COALESCE(?, last_synced_index),
                                  last_error = ?, updated_at = ?
                WHERE url = ?
                """, treeSize, lastIndex, error, nowUtc(), url);
    }

    public List<LogRow> logRows() {
        return jdbc.query("""
                SELECT url, description, operator, last_synced_index, tree_size, last_error, updated_at
                FROM ct_log ORDER BY operator, url
                """, (rs, _) -> new LogRow(
                rs.getString(1),
                rs.getString(2),
                rs.getString(3),
                rs.getLong(4),
                rs.getLong(5),
                rs.getString(6),
                rs.getObject(7, LocalDateTime.class)));
    }

    // --- вставка (синк) ---

    /**
     * Вставка пачки одной транзакцией; если БД отвергла её из-за одной битой записи
     * (SAN длиннее 255 и т.п.), повтор по одной с пропуском плохих — иначе такая запись
     * останавливала бы лог навсегда. Инфраструктурные ошибки уходят наверх.
     *
     * @return сколько записей пришлось пропустить
     */
    public int insertCertificates(List<NewCert> certs) {
        if (certs.isEmpty()) {
            return 0;
        }
        try {
            insertInTransaction(certs);
            return 0;
        } catch (DataIntegrityViolationException batchRejected) {
            log.warn("Пачка из {} записей отклонена БД ({}), повтор по одной",
                    certs.size(), batchRejected.getMostSpecificCause().toString());
        }
        int skipped = 0;
        for (NewCert c : certs) {
            try {
                insertInTransaction(List.of(c));
            } catch (DataIntegrityViolationException rejected) {
                skipped++;
                log.warn("Запись {}#{} отклонена БД, пропускаем: {}",
                        c.logUrl(), c.logIndex(), rejected.getMostSpecificCause().toString());
            }
        }
        return skipped;
    }

    /** Одна транзакция; новые ca публикуются в общий кеш только после коммита. */
    private void insertInTransaction(List<NewCert> certs) {
        Map<String, Long> created = new HashMap<>();
        tx.executeWithoutResult(_ -> {
            List<Object[]> domainArgs = new ArrayList<>(certs.size() * 2);
            for (NewCert c : certs) {
                long caId = getOrCreateCa(c.issuerDn(), created);
                Long certId = insertCertificateReturningId(caId, c);
                if (certId == null) {
                    continue; // уже вставлена ранее — идемпотентность
                }
                for (String d : c.domains()) {
                    domainArgs.add(new Object[]{certId, d, DomainName.reverse(d)});
                }
            }
            if (!domainArgs.isEmpty()) {
                jdbc.batchUpdate("INSERT INTO cert_domain (cert_id, domain, domain_reversed)"
                        + " VALUES (?, ?, ?)", domainArgs);
            }
        });
        created.forEach(caIds::put);
    }

    private Long insertCertificateReturningId(long caId, NewCert c) {
        KeyHolder keys = new GeneratedKeyHolder();
        try {
            jdbc.update(con -> {
                PreparedStatement ps = con.prepareStatement(INSERT_CERT, Statement.RETURN_GENERATED_KEYS);
                int i = 1;
                ps.setLong(i++, caId);
                ps.setString(i++, c.logUrl());
                ps.setLong(i++, c.logIndex());
                ps.setString(i++, c.sha256());
                ps.setString(i++, c.serial());
                ps.setString(i++, c.subjectDn());
                ps.setString(i++, c.issuerDn());
                ps.setObject(i++, c.notBefore());
                ps.setObject(i++, c.notAfter());
                ps.setObject(i++, c.entryTs());
                ps.setBoolean(i++, c.precert());
                ps.setBytes(i, c.der());
                return ps;
            }, keys);
        } catch (DuplicateKeyException e) {
            return null;
        }
        return keys.getKey() == null ? null : keys.getKey().longValue();
    }

    /** {@code created} — ca, вставленные текущей, ещё не зафиксированной транзакцией. */
    private long getOrCreateCa(String issuerDn, Map<String, Long> created) {
        final String dn = issuerDn == null ? "" : issuerDn;
        Long cached = caIds.get(dn, Long.class);
        if (cached != null) {
            return cached;
        }
        Long pending = created.get(dn);
        if (pending != null) {
            return pending;
        }
        List<Long> found = jdbc.queryForList("SELECT id FROM ca WHERE name = ?", Long.class, dn);
        if (!found.isEmpty()) {
            caIds.put(dn, found.getFirst());
            return found.getFirst();
        }
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO ca (name) VALUES (?)", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, dn);
            return ps;
        }, keys);
        Number key = keys.getKey();
        if (key == null) {
            // без id издателя строку вставлять некуда; лог отметится ошибкой и повторит диапазон
            throw new IllegalStateException("БД не вернула id для ca " + dn);
        }
        long id = key.longValue();
        created.put(dn, id);
        return id;
    }

    // --- поиск ---

    /** Запрос считается шаблоном, если содержит подстановочные символы LIKE. */
    public static boolean isWildcard(String q) {
        return q.indexOf('%') >= 0 || q.indexOf('_') >= 0;
    }

    /**
     * Условие отбора по домену и значение к нему — одной парой. Запрос приводится
     * к индексной форме (toIndexForm), поэтому {@code %.рф} и {@code %.xn--p1ai} —
     * один запрос; два LIKE через OR планировщик H2 в диапазон индекса не складывает.
     */
    private record DomainMatch(String sql, String param) {

        static DomainMatch of(String q) {
            String domain = DomainName.toIndexForm(q);
            if (!isWildcard(domain)) {
                return new DomainMatch("d.domain = ?", domain);
            }
            // %.example.ru — по перевёрнутой колонке это префикс, то есть диапазон индекса
            if (domain.startsWith("%") && !isWildcard(domain.substring(1))) {
                return new DomainMatch("d.domain_reversed LIKE ?",
                        DomainName.reverse(domain.substring(1)) + "%");
            }
            return new DomainMatch("d.domain LIKE ?", domain);
        }
    }

    /**
     * Общая часть обоих поисковых запросов: соединение, отбор и параметры —
     * одной записью, чтобы счётчик и страница не разошлись.
     */
    private record Selection(String sql, Object[] args) {

        static Selection of(String extraJoin, DomainMatch match, boolean excludeExpired) {
            return new Selection("""
                    FROM certificate c
                    JOIN cert_domain d ON d.cert_id = c.id
                    %s
                    WHERE %s%s
                    """.formatted(extraJoin, match.sql(),
                            // не CURRENT_TIMESTAMP: в H2 это зона сервера, а даты лежат UTC-локально
                            excludeExpired ? " AND c.not_after >= ?" : ""),
                    excludeExpired
                            ? new Object[]{match.param(), nowUtc()}
                            : new Object[]{match.param()});
        }
    }

    /** Текущий момент в том же представлении, в каком лежат все даты, — UTC-локальном. */
    private static LocalDateTime nowUtc() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    private static final String DEDUP_RANK =
            "ROW_NUMBER() OVER (PARTITION BY c.sha256 ORDER BY c.entry_ts NULLS LAST, c.id)";

    /** Общее число совпадений и id одной страницы, посчитанные вместе. */
    public record SearchResult(int total, List<Long> ids) {
    }

    /**
     * Страница выдачи: сколько всего уникальных сертификатов подходит и какие id
     * попадают на страницу (нумерация с единицы). Оба числа — одной записью:
     * раздельные кеши разъезжались, и пейджер обещал страницы, которых нет.
     * Дубли по sha256 сворачиваются в SQL, окно режется в БД.
     */
    @Cacheable(cacheNames = "search",
            key = "#p0 + ':' + (#p1 ? 'expired' : '') + ':' + #p2 + ':' + #p3"
                    + " + ':' + #p4 + ':' + #p5")
    public SearchResult searchPage(String q, boolean excludeExpired, SortColumn sort,
                                   boolean descending, int page, int pageSize) {
        DomainMatch match = DomainMatch.of(q);
        if (pageSize <= 0) {
            return new SearchResult(count(match, excludeExpired), List.of());
        }
        long offset = (long) (Math.max(page, 1) - 1) * pageSize;
        SearchResult found = page(match, excludeExpired, sort, descending, offset, pageSize);
        return found != null ? found : new SearchResult(count(match, excludeExpired), List.of());
    }

    private int count(DomainMatch match, boolean excludeExpired) {
        // COUNT(DISTINCT) берётся хеш-агрегатом, без сортировки всей выборки
        Selection selection = Selection.of("", match, excludeExpired);
        Integer total = jdbc.queryForObject("SELECT COUNT(DISTINCT c.sha256) " + selection.sql(),
                Integer.class, selection.args());
        return total == null ? 0 : total;
    }

    /** Страница вместе со счётчиком; null, если на неё не попало ни одной строки. */
    private SearchResult page(DomainMatch match, boolean excludeExpired, SortColumn sort,
                              boolean descending, long offset, int limit) {
        String dir = descending ? "DESC" : "ASC";
        Selection selection = Selection.of(sort.joinSql(), match, excludeExpired);
        String sql = """
                SELECT t.id, COUNT(*) OVER () AS total FROM (
                  SELECT c.id AS id, c.entry_ts AS entry_ts, c.not_before AS not_before,
                         c.not_after AS not_after, %s%s AS rn
                  %s
                ) t
                WHERE t.rn = 1
                ORDER BY %s %s, t.id %s
                LIMIT ? OFFSET ?
                """.formatted(sort.selectExtra(), DEDUP_RANK, selection.sql(), sort.sql(), dir, dir);
        List<Object> args = new ArrayList<>(List.of(selection.args()));
        args.add(limit);
        args.add(offset);
        List<Long> ids = new ArrayList<>(limit);
        int[] total = {0};
        jdbc.query(sql, rs -> {
            ids.add(rs.getLong(1));
            total[0] = rs.getInt(2);
        }, args.toArray());
        // копия неизменяемая: запись живёт в общем кеше
        return ids.isEmpty() ? null : new SearchResult(total[0], List.copyOf(ids));
    }

    // --- чтение сертификатов ---

    /** Строки поиска по id, в том же порядке; несуществующие пропускаются. Без DER. */
    public List<CertRow> certRows(List<Long> ids) {
        return loadRows(ids, false);
    }

    /** Сертификат целиком, вместе с DER; null, если такого id нет. */
    public CertRow certRow(long id) {
        List<CertRow> rows = loadRows(List.of(id), true);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /** Вхождения этого же сертификата (тот же sha256) во все синхронизированные логи. */
    public List<OccurrenceRow> occurrences(String sha256) {
        return jdbc.query("""
                        SELECT c.log_url, l.description, l.operator, c.log_index, c.entry_ts
                        FROM certificate c LEFT JOIN ct_log l ON l.url = c.log_url
                        WHERE c.sha256 = ?
                        ORDER BY c.entry_ts, c.log_url
                        """,
                (rs, _) -> new OccurrenceRow(
                        rs.getString(1),
                        rs.getString(2),
                        rs.getString(3),
                        rs.getLong(4),
                        rs.getObject(5, LocalDateTime.class)),
                sha256);
    }

    /** Строки certificate по id: домены читаются первым запросом и раскладываются по строкам. */
    private List<CertRow> loadRows(List<Long> ids, boolean withDer) {
        Map<Long, TreeSet<String>> domains = loadDomains(ids);
        Map<Long, CertRow> rows = new HashMap<>();
        queryByIds(ids, placeholders ->
                "SELECT c.id, c.issuer_ca_id, ca.name, c.log_url, c.log_index, c.sha256,"
                        + " c.serial, c.subject_dn, c.issuer_dn, c.not_before, c.not_after,"
                        + " c.entry_ts, c.is_precert" + (withDer ? ", c.der" : "")
                        + " FROM certificate c LEFT JOIN ca ON ca.id = c.issuer_ca_id"
                        + " WHERE c.id IN (" + placeholders + ")",
                rs -> {
                    long id = rs.getLong("id");
                    TreeSet<String> certDomains = domains.get(id);
                    rows.put(id, new CertRow(
                            id,
                            (Long) rs.getObject("issuer_ca_id"),
                            rs.getString("name"),
                            rs.getString("log_url"),
                            rs.getLong("log_index"),
                            rs.getString("sha256"),
                            rs.getString("serial"),
                            rs.getString("subject_dn"),
                            rs.getString("issuer_dn"),
                            rs.getObject("not_before", LocalDateTime.class),
                            rs.getObject("not_after", LocalDateTime.class),
                            rs.getObject("entry_ts", LocalDateTime.class),
                            rs.getBoolean("is_precert"),
                            certDomains == null ? List.of() : List.copyOf(certDomains),
                            withDer ? rs.getBytes("der") : null));
                });
        List<CertRow> out = new ArrayList<>(ids.size());
        for (Long id : ids) {
            CertRow row = rows.get(id);
            if (row != null) {
                out.add(row);
            }
        }
        return out;
    }

    private Map<Long, TreeSet<String>> loadDomains(List<Long> ids) {
        Map<Long, TreeSet<String>> out = new HashMap<>();
        queryByIds(ids,
                placeholders -> "SELECT cert_id, domain FROM cert_domain WHERE cert_id IN ("
                        + placeholders + ")",
                rs -> out.computeIfAbsent(rs.getLong(1), _ -> new TreeSet<>()).add(rs.getString(2)));
        return out;
    }

    /** H2 ограничивает число параметров запроса, поэтому IN-список режем на куски. */
    private static final int MAX_IDS_PER_QUERY = 1000;

    /** Запрос с {@code IN (...)} по списку id, нарезанный на куски; SQL собирает функция из плейсхолдеров. */
    private void queryByIds(List<Long> ids, UnaryOperator<String> sql, RowCallbackHandler handler) {
        for (int from = 0; from < ids.size(); from += MAX_IDS_PER_QUERY) {
            List<Long> chunk = ids.subList(from, Math.min(from + MAX_IDS_PER_QUERY, ids.size()));
            String placeholders = String.join(",", Collections.nCopies(chunk.size(), "?"));
            jdbc.query(sql.apply(placeholders), handler, chunk.toArray());
        }
    }
}
