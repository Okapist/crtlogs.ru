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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import javax.security.auth.x500.X500Principal;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import ru.crt.client.KnownLog;
import ru.crt.sync.CtEntryParser;
import ru.crt.web.CertDetails;
import ru.crt.web.LogStatus;
import ru.crt.web.SearchRow;

@Repository
public class CertificateRepository {

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
    /** Кеш issuer DN -> ca.id на время жизни приложения (пополняется при синке). */
    private final Map<String, Long> caIds = new HashMap<>();

    public CertificateRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
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

    @Transactional
    public void insertCertificates(List<NewCert> certs) {
        for (NewCert c : certs) {
            long caId = getOrCreateCa(c.issuerDn());
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

    private long getOrCreateCa(String issuerDn) {
        final String dn = issuerDn == null ? "" : issuerDn;
        Long cached = caIds.get(dn);
        if (cached != null) {
            return cached;
        }
        List<Long> found = jdbc.query("SELECT id FROM ca WHERE name = ?",
                (rs, i) -> rs.getLong(1), dn);
        if (!found.isEmpty()) {
            caIds.put(dn, found.get(0));
            return found.get(0);
        }
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO ca (name) VALUES (?)", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, dn);
            return ps;
        }, keys);
        long id = keys.getKey().longValue();
        caIds.put(dn, id);
        return id;
    }

    // --- чтение ---

    /**
     * Поиск в семантике crt.sh: точное совпадение identity, поддомены через wildcard
     * (символы % и _ пользователя передаются в LIKE как есть).
     */
    @Cacheable(cacheNames = "search",
            key = "#p0 + ':' + (#p1 ? 'expired' : '')",
            unless = "#result.isEmpty()")
    public List<SearchRow> search(String q, boolean excludeExpired) {
        String norm = q.toLowerCase(Locale.ROOT);
        boolean wildcard = norm.indexOf('%') >= 0 || norm.indexOf('_') >= 0;
        String expiry = excludeExpired ? " AND c.not_after >= CURRENT_TIMESTAMP" : "";
        String matchSql = wildcard
                ? "SELECT DISTINCT c.id FROM certificate c JOIN cert_domain d ON d.cert_id = c.id"
                        + " WHERE d.domain LIKE ?" + expiry + " ORDER BY c.id"
                : "SELECT DISTINCT c.id FROM certificate c JOIN cert_domain d ON d.cert_id = c.id"
                        + " WHERE d.domain = ?" + expiry + " ORDER BY c.id";
        List<Long> ids = jdbc.query(matchSql, (rs, i) -> rs.getLong(1), norm);
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<Long, CertRow> rows = loadCertRows(ids);
        Map<Long, TreeSet<String>> domains = loadDomains(ids);
        List<SearchRow> out = new ArrayList<>(rows.size());
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
                    ids.size()));
        }
        return out;
    }

    /** Детали сертификата для UI и скачивания; null, если не существует. */
    @Cacheable(cacheNames = "certById", key = "#p0", unless = "#result == null")
    public CertDetails getCertDetails(long id) {
        List<CertRow> rows = new ArrayList<>(loadCertRows(List.of(id)).values());
        if (rows.isEmpty()) {
            return null;
        }
        CertRow r = rows.get(0);
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
                r.serial(),
                r.subjectDn(),
                r.issuerDn(),
                format(r.notBefore()),
                format(r.notAfter()),
                format(r.entryTs()),
                r.precert(),
                toPem(r.der()));
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

    private Map<Long, CertRow> loadCertRows(List<Long> ids) {
        Map<Long, CertRow> out = new LinkedHashMap<>();
        for (List<Long> chunk : partition(ids, 1000)) {
            String placeholders = String.join(",", chunk.stream().map(v -> "?").toList());
            Object[] args = chunk.toArray();
            jdbc.query("SELECT c.id, c.issuer_ca_id, ca.name, c.log_url, c.log_index, c.sha256,"
                            + " c.serial, c.subject_dn, c.issuer_dn, c.not_before, c.not_after,"
                            + " c.entry_ts, c.is_precert, c.der"
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
                                rs.getBytes("der")));
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
