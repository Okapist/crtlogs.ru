package ru.crt.db;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.LocalDateTime;
import java.time.Month;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import ru.crt.client.CtLogClient;
import ru.crt.sync.CtEntryParser;
import ru.crt.web.CertDetails;
import ru.crt.web.SearchRow;

/**
 * Сквозной тест хранилища на in-memory H2: вставка реальных записей лога,
 * поиск в семантике crt.sh, идемпотентность вставки, exclude=expired.
 */
@SpringBootTest
class CertificateRepositoryTest {

    private static final String LOG_URL = "https://test.example/log/";
    private static final ObjectMapper JSON = new ObjectMapper();

    @MockitoBean
    private CtLogClient ctLogClient; // синк не ходит в сеть

    @Autowired
    private CertificateRepository repository;

    @Autowired
    private CacheManager cacheManager;

    @BeforeEach
    void setUp() throws Exception {
        cacheManager.getCache("search").clear();
        cacheManager.getCache("certById").clear();
        when(ctLogClient.fetchLogList(anyString())).thenReturn(List.of());
    }

    private static JsonNode fixture() {
        try (InputStream in = CertificateRepositoryTest.class
                .getResourceAsStream("/ct-entries-fixture.json")) {
            assertNotNull(in);
            return JSON.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private List<CertificateRepository.NewCert> fixtureCerts() throws Exception {
        List<CertificateRepository.NewCert> rows = new ArrayList<>();
        JsonNode entries = fixture().get("entries");
        for (int i = 0; i < entries.size(); i++) {
            JsonNode e = entries.get(i);
            CtEntryParser.ParsedEntry parsed =
                    CtEntryParser.parse(e.get("leaf_input").asText(), e.get("extra_data").asText());
            byte[] der = parsed.certificate().getEncoded();
            rows.add(new CertificateRepository.NewCert(
                    LOG_URL,
                    i,
                    der,
                    CertificateRepository.sha256Hex(der),
                    java.util.HexFormat.of().formatHex(
                            parsed.certificate().getSerialNumber().toByteArray()),
                    parsed.certificate().getSubjectX500Principal().getName(),
                    parsed.certificate().getIssuerX500Principal().getName(),
                    java.time.LocalDateTime.ofInstant(
                            parsed.certificate().getNotBefore().toInstant(),
                            java.time.ZoneOffset.UTC),
                    java.time.LocalDateTime.ofInstant(
                            parsed.certificate().getNotAfter().toInstant(),
                            java.time.ZoneOffset.UTC),
                    java.time.LocalDateTime.ofInstant(
                            java.time.Instant.ofEpochMilli(parsed.entryTimestampMs()),
                            java.time.ZoneOffset.UTC),
                    parsed.precert(),
                    parsed.domains()));
        }
        return rows;
    }

    @Test
    void insertSearchDetailsAndIdempotency() throws Exception {
        List<CertificateRepository.NewCert> rows = fixtureCerts();
        repository.insertCertificates(rows);

        String domain = rows.getFirst().domains().iterator().next();
        List<SearchRow> found = repository.search(domain, false);
        assertFalse(found.isEmpty(), "поиск по " + domain + " должен находить вставленный сертификат");
        SearchRow row = found.getFirst();
        assertEquals(64, rows.getFirst().sha256().length());
        assertNotNull(row.issuer_ca_id());
        assertNotNull(row.issuer_name());
        assertNotNull(row.common_name());
        assertTrue(row.name_value().contains(domain));
        assertNotNull(row.not_before());
        assertNotNull(row.entry_timestamp());
        assertEquals(found.size(), row.result_count());

        long id = row.id();
        CertDetails details = repository.getCertDetails(id);
        assertNotNull(details);
        assertTrue(details.pem().startsWith("-----BEGIN CERTIFICATE-----"));
        assertTrue(details.pem().endsWith("-----END CERTIFICATE-----\n"));
        assertEquals(rows.getFirst().sha256(), details.sha256());
        assertTrue(details.domains().contains(domain));
        assertNull(repository.getCertDetails(-1));

        // поддомены wildcard-поиском: %.родительский-домен
        int dot = domain.indexOf('.');
        if (dot > 0) {
            String parent = domain.substring(dot + 1);
            assertFalse(repository.search("%." + parent, false).isEmpty(), "wildcard %. " + parent + " должен находить " + domain);
        }

        // повторная вставка тех же (log_url, log_index) ничего не добавляет
        repository.insertCertificates(rows);
        assertEquals(found.size(), repository.search(domain, false).size());
    }

    @Test
    void excludeExpiredFiltersOutOldCerts() throws Exception {
        CertificateRepository.NewCert original = fixtureCerts().getFirst();
        String domain = original.domains().iterator().next();
        // второй, истёкший сертификат на том же домене: срок задан вручную, sha256 делаем
        // другим (в реальности один DER не может иметь разные сроки — иначе дедупликация
        // по sha256 схлопнула бы строки)
        CertificateRepository.NewCert expired = new CertificateRepository.NewCert(
                original.logUrl(),
                999, // уникальный log_index
                original.der(),
                "ee" + original.sha256().substring(2),
                original.serial(),
                original.subjectDn(),
                original.issuerDn(),
                LocalDateTime.of(2019, Month.JANUARY, 1, 0, 0),
                LocalDateTime.of(2020, Month.JANUARY, 1, 0, 0), // истёк
                original.entryTs(),
                original.precert(),
                original.domains());
        repository.insertCertificates(List.of(original));
        repository.insertCertificates(List.of(expired));

        assertEquals(2, repository.search(domain, false).size());
        assertEquals(1, repository.search(domain, true).size());
    }

    @Test
    void cachesArePopulatedThroughProxy() throws Exception {
        List<CertificateRepository.NewCert> rows = fixtureCerts();
        repository.insertCertificates(rows);
        String domain = rows.getFirst().domains().iterator().next();

        int total = repository.searchCount(domain, false);
        assertTrue(total > 0);
        assertNotNull(cacheManager.getCache("search").get("n:" + domain + ":"),
                "счётчик совпадений должен попасть в кеш search");

        List<Long> ids = repository.searchIdsPage(domain, false, 1, true, 0, 100);
        assertFalse(ids.isEmpty());
        assertNotNull(cacheManager.getCache("search").get("p:" + domain + "::1:true:0:100"),
                "страница id поиска должна попасть в кеш search");

        List<SearchRow> found = repository.searchRows(ids, total);
        assertFalse(found.isEmpty());

        CertDetails details = repository.getCertDetails(found.getFirst().id());
        assertNotNull(details);
        assertNotNull(cacheManager.getCache("certById").get(found.getFirst().id()),
                "детали сертификата должны попасть в кеш certById");
        assertNull(cacheManager.getCache("certById").get(-1L),
                "null-результат не кешируется");
    }

    @Test
    void searchRowsPageKeepsTotalInResultCount() throws Exception {
        List<CertificateRepository.NewCert> rows = fixtureCerts();
        repository.insertCertificates(rows);
        String domain = rows.getFirst().domains().iterator().next();

        int total = repository.searchCount(domain, false);
        assertTrue(total > 0);
        // окно режется в SQL: страница не зависит от размера всей выборки
        List<Long> ids = repository.searchIdsPage(domain, false, 1, true, 0, 1);
        assertEquals(1, ids.size(), "LIMIT 1 должен вернуть ровно одну строку");
        List<SearchRow> page = repository.searchRows(ids, total);
        assertEquals(1, page.size(), "страница — только переданные id");
        assertEquals(total, page.getFirst().result_count(),
                "result_count — общее число совпадений, а не размер страницы");
    }

    @Test
    void sameCertificateFoundInMultipleLogs() throws Exception {
        CertificateRepository.NewCert original = fixtureCerts().getFirst();
        // тот же DER/sha256, но другой лог и записан на час раньше — как повторная подача
        // в другой CT-лог; домены в реальности берутся из DER, поэтому у копий совпадают
        CertificateRepository.NewCert inOtherLog = new CertificateRepository.NewCert(
                "https://other.example/log/",
                42,
                original.der(),
                original.sha256(),
                original.serial(),
                original.subjectDn(),
                original.issuerDn(),
                original.notBefore(),
                original.notAfter(),
                original.entryTs().minusHours(1),
                original.precert(),
                original.domains());
        repository.insertCertificates(List.of(original, inOtherLog));

        List<SearchRow> found = repository.search(original.domains().iterator().next(), false);
        assertEquals(1, found.size(), "дубликат в нескольких логах — одна строка поиска, как у crt.sh");

        CertDetails details = repository.getCertDetails(found.getFirst().id());
        assertEquals("https://other.example/log/", details.log_url(),
                "показывается самое раннее попадание в лог");
        assertEquals(2, details.occurrences().size(),
                "сертификат должен показывать вхождения из обоих логов");
        assertTrue(details.occurrences().stream()
                .anyMatch(o -> "https://other.example/log/".equals(o.log_url())));
    }

    /**
     * Одна запись, которую отвергает БД, не должна ронять пачку: иначе транзакция
     * откатывается целиком, last_synced_index не двигается и лог перестаёт синкаться
     * навсегда — следующий проход скачивает и роняет тот же диапазон.
     */
    @Test
    void badRecordIsSkippedAndRestOfBatchIsInserted() throws Exception {
        CertificateRepository.NewCert good = fixtureCerts().getFirst();
        // домен длиннее cert_domain.domain VARCHAR(255) — такую запись БД не примет
        CertificateRepository.NewCert bad = new CertificateRepository.NewCert(
                good.logUrl(),
                12345, // уникальный log_index
                good.der(),
                "bb" + good.sha256().substring(2),
                good.serial(),
                good.subjectDn(),
                good.issuerDn(),
                good.notBefore(),
                good.notAfter(),
                good.entryTs(),
                good.precert(),
                Set.of("a".repeat(300) + ".example"));

        assertEquals(1, repository.insertCertificates(List.of(bad, good)),
                "битая запись пропускается поштучно, а не роняет всю пачку");

        String domain = good.domains().iterator().next();
        assertFalse(repository.search(domain, false).isEmpty(),
                "соседняя запись из той же пачки должна быть вставлена");
        // ca издателя откатился вместе с пачкой: если бы его id остался в кеше,
        // все следующие вставки этого издателя падали бы на внешнем ключе
        assertEquals(0, repository.insertCertificates(List.of(good)),
                "повторная вставка того же издателя после отката должна проходить");
    }

    @Test
    void caIdsAreSharedPerIssuerDn() throws Exception {
        List<CertificateRepository.NewCert> rows = fixtureCerts();
        repository.insertCertificates(rows);
        Set<Long> issuerIds = new TreeSet<>();
        for (SearchRow r : repository.search(rows.getFirst().domains().iterator().next(), false)) {
            issuerIds.add(r.issuer_ca_id());
        }
        assertEquals(1, issuerIds.size(), "все сертификаты одного издателя — один issuer_ca_id");
    }
}
