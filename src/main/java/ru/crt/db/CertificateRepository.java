package ru.crt.db;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import javax.security.auth.x500.X500Principal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ru.crt.client.KnownLog;
import ru.crt.sync.CtEntryParser;
import ru.crt.web.CertDetails;
import ru.crt.web.CertTextDump;
import ru.crt.web.LogStatus;
import ru.crt.web.SearchRow;

@Repository
public class CertificateRepository {

    private static final Logger log = LoggerFactory.getLogger(CertificateRepository.class);

    /** ISO-дата без зоны, секунды всегда, миллисекунды при наличии — как у crt.sh. */
    private static final DateTimeFormatter ISO = new DateTimeFormatterBuilder()
            .appendPattern("yyyy-MM-dd'T'HH:mm:ss")
            .appendFraction(ChronoField.NANO_OF_SECOND, 0, 3, true)
            .toFormatter();

    private static final String INSERT_CERT = """
            INSERT INTO certificate (issuer_ca_id, log_url, log_index, sha256, serial,
                                     subject_dn, issuer_dn, not_before, not_after, entry_ts, is_precert, der)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    /**
     * Кеш issuer DN -> ca.id на время жизни приложения. Пополняется только строками,
     * которые уже зафиксированы: id из откатившейся транзакции указывал бы на
     * несуществующую строку, и все следующие пачки этого издателя падали бы на
     * внешнем ключе certificate.issuer_ca_id.
     */
    private final Map<String, Long> caIds = new ConcurrentHashMap<>();

    public CertificateRepository(JdbcTemplate jdbc, PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
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
                (rs, i) -> new KnownLog(rs.getString(1), rs.getString(2), rs.getString(3)));
    }

    public Map<String, Long> loadSyncProgress() {
        Map<String, Long> out = new HashMap<>();
        jdbc.query("SELECT url, last_synced_index FROM ct_log",
                (rs, i) -> {
                    out.put(rs.getString(1), rs.getLong(2));
                    return null;
                });
        return out;
    }

    /** Обновляет tree_size/last_error всегда; last_synced_index только если передан. */
    public void updateLogState(String url, Long treeSize, Long lastIndex, String error) {
        jdbc.update("""
                UPDATE ct_log SET tree_size = COALESCE(?, tree_size),
                                  last_synced_index = COALESCE(?, last_synced_index),
                                  last_error = ?, updated_at = CURRENT_TIMESTAMP
                WHERE url = ?
                """, treeSize, lastIndex, error, url);
    }

    public List<LogStatus> getLogStatuses() {
        return jdbc.query("""
                SELECT url, description, operator, last_synced_index, tree_size, last_error, updated_at
                FROM ct_log ORDER BY operator, url
                """, (rs, i) -> new LogStatus(
                rs.getString(1),
                rs.getString(2),
                rs.getString(3),
                rs.getLong(4),
                rs.getLong(5),
                rs.getString(6),
                format(rs.getTimestamp(7))));
    }

    // --- вставка (синк) ---

    /**
     * Вставка пачки записей лога. Быстрый путь — одна транзакция на всю пачку; если БД
     * отвергла пачку из-за отдельной «битой» записи (SAN длиннее 255, DN длиннее 1024,
     * слишком длинный serial), пачка повторяется по одной записи, и плохие пропускаются.
     * Иначе одна такая запись останавливала бы лог навсегда: транзакция откатывалась бы
     * целиком, last_synced_index не двигался, и следующий проход скачивал бы тот же
     * диапазон. Инфраструктурные ошибки (БД недоступна) сюда не попадают и уходят
     * наверх — тогда прогресс не двигается и пачка честно перечитывается позже.
     *
     * <p>Сбрасывает certById: сам сертификат иммутабелен, но в его деталях лежат
     * occurrences() — список логов, в которых он найден, а он растёт при синке.
     *
     * @return сколько записей пришлось пропустить
     */
    @CacheEvict(cacheNames = "certById", allEntries = true)
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
        tx.executeWithoutResult(status -> {
            created.clear(); // транзакция могла быть перезапущена
            for (NewCert c : certs) {
                long caId = getOrCreateCa(c.issuerDn(), created);
                Long certId = insertCertificateReturningId(caId, c);
                if (certId == null) {
                    continue; // запись уже была (уникальность log_url+log_index) — идемпотентность
                }
                List<Object[]> domainArgs = new ArrayList<>(c.domains().size());
                for (String d : c.domains()) {
                    domainArgs.add(new Object[]{certId, d});
                }
                if (!domainArgs.isEmpty()) {
                    jdbc.batchUpdate("INSERT INTO cert_domain (cert_id, domain) VALUES (?, ?)", domainArgs);
                }
            }
        });
        caIds.putAll(created);
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
                setTimestamp(ps, i++, c.notBefore());
                setTimestamp(ps, i++, c.notAfter());
                setTimestamp(ps, i++, c.entryTs());
                ps.setBoolean(i++, c.precert());
                ps.setBytes(i, c.der());
                return ps;
            }, keys);
        } catch (DuplicateKeyException e) {
            return null; // уже вставлена ранее
        }
        return keys.getKey() == null ? null : keys.getKey().longValue();
    }

    private static void setTimestamp(PreparedStatement ps, int index, LocalDateTime value)
            throws java.sql.SQLException {
        ps.setTimestamp(index, value == null ? null : Timestamp.valueOf(value));
    }

    /** {@code created} — ca, вставленные текущей, ещё не зафиксированной транзакцией. */
    private long getOrCreateCa(String issuerDn, Map<String, Long> created) {
        final String dn = issuerDn == null ? "" : issuerDn;
        Long cached = caIds.get(dn);
        if (cached != null) {
            return cached;
        }
        Long pending = created.get(dn);
        if (pending != null) {
            return pending;
        }
        List<Long> found = jdbc.query("SELECT id FROM ca WHERE name = ?",
                (rs, i) -> rs.getLong(1), dn);
        if (!found.isEmpty()) {
            caIds.put(dn, found.getFirst()); // строка зафиксирована ранее — кешируем сразу
            return found.getFirst();
        }
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO ca (name) VALUES (?)", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, dn);
            return ps;
        }, keys);
        long id = keys.getKey().longValue();
        created.put(dn, id);
        return id;
    }

    // --- чтение ---

    /**
     * Поиск в семантике crt.sh: точное совпадение identity, поддомены через wildcard
     * (символы % и _ пользователя передаются в LIKE как есть). Для тестов — целиком,
     * без кеша и пейджинга; рабочий путь идёт через searchCount + searchIdsPage.
     */
    public List<SearchRow> search(String q, boolean excludeExpired) {
        int total = searchCount(q, excludeExpired);
        return searchRows(searchIdsPage(q, excludeExpired, SORT_LOGGED_AT, true, 0, total), total);
    }

    /** Запрос считается шаблоном, если содержит подстановочные символы LIKE. */
    public static boolean isWildcard(String q) {
        return q.indexOf('%') >= 0 || q.indexOf('_') >= 0;
    }

    /**
     * Общее число уникальных сертификатов по запросу — для пейджера и result_count.
     * Кешируется вместе с нулём: иначе шаблон без совпадений пересканировал бы
     * cert_domain на каждом запросе, и лимит по IP обходился бы простым перебором.
     */
    @Cacheable(cacheNames = "search", key = "'n:' + #p0 + ':' + (#p1 ? 'expired' : '')")
    public int searchCount(String q, boolean excludeExpired) {
        Integer total = jdbc.queryForObject("""
                SELECT COUNT(*) FROM (
                  SELECT ROW_NUMBER() OVER (PARTITION BY c.sha256
                             ORDER BY c.entry_ts NULLS LAST, c.id) AS rn
                  FROM certificate c
                  JOIN cert_domain d ON d.cert_id = c.id
                  WHERE %s%s
                ) t
                WHERE t.rn = 1
                """.formatted(match(q), expiry(excludeExpired)),
                Integer.class, q.toLowerCase(Locale.ROOT));
        return total == null ? 0 : total;
    }

    /**
     * Одна страница id уникальных сертификатов в порядке сортировки sort/dir, как у
     * crt.sh: 0 = ID, 1 = Logged At, 2 = Not Before, 3 = Issuer Name, 4 = Not After.
     * Дубли (тот же sha256 в разных логах) сворачиваются в SQL — одна строка на
     * сертификат, самое раннее попадание в любой лог. Окно режется в БД (LIMIT/OFFSET),
     * а не в Java: на широком шаблоне выборка — миллионы строк, и materialize всего
     * списка ради сотни на странице клал бы кучу.
     */
    @Cacheable(cacheNames = "search",
            key = "'p:' + #p0 + ':' + (#p1 ? 'expired' : '') + ':' + #p2 + ':' + #p3"
                    + " + ':' + #p4 + ':' + #p5")
    public List<Long> searchIdsPage(String q, boolean excludeExpired, int sort, boolean descending,
                                    int offset, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        String sortExpr = switch (sort) {
            case 0 -> "t.id";
            case 2 -> "t.not_before";
            case 3 -> "LOWER(t.issuer_name)";
            case 4 -> "t.not_after";
            default -> "t.entry_ts";
        };
        String dir = descending ? "DESC" : "ASC";
        return jdbc.query("""
                SELECT t.id FROM (
                  SELECT c.id AS id, c.entry_ts AS entry_ts, c.not_before AS not_before,
                         c.not_after AS not_after, ca.name AS issuer_name,
                         ROW_NUMBER() OVER (PARTITION BY c.sha256
                             ORDER BY c.entry_ts NULLS LAST, c.id) AS rn
                  FROM certificate c
                  JOIN cert_domain d ON d.cert_id = c.id
                  LEFT JOIN ca ON ca.id = c.issuer_ca_id
                  WHERE %s%s
                ) t
                WHERE t.rn = 1
                ORDER BY %s %s, t.id %s
                LIMIT ? OFFSET ?
                """.formatted(match(q), expiry(excludeExpired), sortExpr, dir, dir),
                (rs, i) -> rs.getLong(1), q.toLowerCase(Locale.ROOT), limit, Math.max(0, offset));
    }

    private static String match(String q) {
        return isWildcard(q) ? "d.domain LIKE ?" : "d.domain = ?";
    }

    private static String expiry(boolean excludeExpired) {
        return excludeExpired ? " AND c.not_after >= CURRENT_TIMESTAMP" : "";
    }

    /**
     * Строки поиска по переданным id (обычно страница из searchIds, порядок
     * сохраняется). total — общее число совпадений: кладётся в result_count
     * каждой строки, чтобы клиенты могли построить пейджер.
     */
    public List<SearchRow> searchRows(List<Long> ids, int total) {
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<Long, CertRow> rows = loadCertRows(ids, false);
        Map<Long, TreeSet<String>> domains = loadDomains(ids);
        List<SearchRow> out = new ArrayList<>(ids.size());
        for (Long id : ids) {
            CertRow r = rows.get(id);
            if (r == null) {
                continue;
            }
            TreeSet<String> doms = domains.getOrDefault(id, new TreeSet<>());
            out.add(new SearchRow(
                    id,
                    r.issuerCaId(),
                    r.issuerName(),
                    commonName(r.subjectDn()),
                    String.join("\n", doms),
                    format(r.entryTs()),
                    format(r.notBefore()),
                    format(r.notAfter()),
                    r.serial(),
                    total));
        }
        return out;
    }

    /** Индексы сортировки как у crt.sh: 0 = ID, 1 = Logged At, 2 = Not Before, 3 = Issuer, 4 = Not After. */
    static final int SORT_LOGGED_AT = 1;

    /** Детали сертификата для UI и скачивания; null, если не существует. */
    @Cacheable(cacheNames = "certById", key = "#p0", unless = "#result == null")
    public CertDetails getCertDetails(long id) {
        List<CertRow> rows = new ArrayList<>(loadCertRows(List.of(id), true).values());
        if (rows.isEmpty()) {
            return null;
        }
        CertRow r = rows.getFirst();
        TreeSet<String> doms = loadDomains(List.of(id)).getOrDefault(id, new TreeSet<>());
        return new CertDetails(
                id,
                r.issuerCaId(),
                r.issuerName(),
                commonName(r.subjectDn()),
                String.join("\n", doms),
                List.copyOf(doms),
                r.logUrl(),
                r.logIndex(),
                r.sha256(),
                CertTextDump.sha1Hex(r.der()),
                r.serial(),
                r.subjectDn(),
                r.issuerDn(),
                format(r.notBefore()),
                format(r.notAfter()),
                format(r.entryTs()),
                r.precert(),
                loadOccurrences(r.sha256()),
                CertTextDump.render(r.der()),
                toPem(r.der()));
    }

    /** Вхождения этого же сертификата (тот же sha256) во все синхронизированные логи. */
    private List<CertDetails.LogOccurrence> loadOccurrences(String sha256) {
        return jdbc.query("""
                        SELECT c.log_url, l.description, l.operator, c.log_index, c.entry_ts
                        FROM certificate c LEFT JOIN ct_log l ON l.url = c.log_url
                        WHERE c.sha256 = ?
                        ORDER BY c.entry_ts, c.log_url
                        """,
                (rs, i) -> new CertDetails.LogOccurrence(
                        rs.getString(1),
                        rs.getString(2),
                        rs.getString(3),
                        rs.getLong(4),
                        format(rs.getTimestamp(5))),
                sha256);
    }

    private record CertRow(
            Long issuerCaId,
            String issuerName,
            String logUrl,
            long logIndex,
            String sha256,
            String serial,
            String subjectDn,
            String issuerDn,
            LocalDateTime notBefore,
            LocalDateTime notAfter,
            LocalDateTime entryTs,
            boolean precert,
            byte[] der) {
    }

    /**
     * Строки certificate по id. withDer=false — без BLOB'ов DER (поиску они не нужны,
     * а на полном wildcard это десятки мегабайт); der в CertRow тогда null.
     */
    private Map<Long, CertRow> loadCertRows(List<Long> ids, boolean withDer) {
        Map<Long, CertRow> out = new LinkedHashMap<>();
        for (List<Long> chunk : partition(ids, 1000)) {
            String placeholders = String.join(",", chunk.stream().map(v -> "?").toList());
            Object[] args = chunk.toArray();
            jdbc.query("SELECT c.id, c.issuer_ca_id, ca.name, c.log_url, c.log_index, c.sha256,"
                            + " c.serial, c.subject_dn, c.issuer_dn, c.not_before, c.not_after,"
                            + " c.entry_ts, c.is_precert" + (withDer ? ", c.der" : "")
                            + " FROM certificate c LEFT JOIN ca ON ca.id = c.issuer_ca_id"
                            + " WHERE c.id IN (" + placeholders + ")",
                    (rs, i) -> {
                        Timestamp notBefore = rs.getTimestamp("not_before");
                        Timestamp notAfter = rs.getTimestamp("not_after");
                        Timestamp entryTs = rs.getTimestamp("entry_ts");
                        out.put(rs.getLong("id"), new CertRow(
                                (Long) rs.getObject("issuer_ca_id"),
                                rs.getString("name"),
                                rs.getString("log_url"),
                                rs.getLong("log_index"),
                                rs.getString("sha256"),
                                rs.getString("serial"),
                                rs.getString("subject_dn"),
                                rs.getString("issuer_dn"),
                                notBefore == null ? null : notBefore.toLocalDateTime(),
                                notAfter == null ? null : notAfter.toLocalDateTime(),
                                entryTs == null ? null : entryTs.toLocalDateTime(),
                                rs.getBoolean("is_precert"),
                                withDer ? rs.getBytes("der") : null));
                        return null;
                    }, args);
        }
        return out;
    }

    private Map<Long, TreeSet<String>> loadDomains(List<Long> ids) {
        Map<Long, TreeSet<String>> out = new HashMap<>();
        for (List<Long> chunk : partition(ids, 1000)) {
            String placeholders = String.join(",", chunk.stream().map(v -> "?").toList());
            Object[] args = chunk.toArray();
            jdbc.query("SELECT cert_id, domain FROM cert_domain WHERE cert_id IN (" + placeholders + ")",
                    (rs, i) -> {
                        out.computeIfAbsent(rs.getLong(1), k -> new TreeSet<>())
                                .add(rs.getString(2));
                        return null;
                    }, args);
        }
        return out;
    }

    private static List<List<Long>> partition(List<Long> list, int size) {
        List<List<Long>> out = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            out.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return out;
    }

    private static String commonName(String subjectDn) {
        if (subjectDn == null) {
            return null;
        }
        try {
            return CtEntryParser.cnOf(new X500Principal(subjectDn));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String format(LocalDateTime value) {
        return value == null ? null : value.format(ISO);
    }

    private static String format(Timestamp value) {
        return value == null ? null : format(value.toLocalDateTime());
    }

    public static String toPem(byte[] der) {
        return "-----BEGIN CERTIFICATE-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der)
                + "\n-----END CERTIFICATE-----\n";
    }

    public static String sha256Hex(byte[] der) {
        try {
            return HexFormatHolder.FORMAT.formatHex(MessageDigest.getInstance("SHA-256").digest(der));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 недоступен", e);
        }
    }

    private static final class HexFormatHolder {
        private static final java.util.HexFormat FORMAT = java.util.HexFormat.of();
    }
}
