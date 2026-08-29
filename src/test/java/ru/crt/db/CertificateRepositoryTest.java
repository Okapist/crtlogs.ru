package ru.crt.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
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

        String domain = rows.get(0).domains().iterator().next();
        List<SearchRow> found = repository.search(domain, false);
        assertTrue(!found.isEmpty(), "поиск по " + domain + " должен находить вставленный сертификат");
        SearchRow row = found.get(0);
        assertEquals(rows.get(0).sha256().length(), 64);
        assertNotNull(row.issuer_ca_id());
        assertNotNull(row.issuer_name());
        assertNotNull(row.common_name());
        assertTrue(row.name_value().contains(domain));
        assertNotNull(row.not_before());
        assertNotNull(row.entry_timestamp());
        assertEquals(found.size(), row.result_count());

        Long id = row.id();
        CertDetails details = repository.getCertDetails(id);
        assertNotNull(details);
        assertTrue(details.pem().startsWith("-----BEGIN CERTIFICATE-----"));
        assertTrue(details.pem().endsWith("-----END CERTIFICATE-----\n"));
        assertEquals(rows.get(0).sha256(), details.sha256());
        assertTrue(details.domains().contains(domain));
        assertNull(repository.getCertDetails(-1));

        // поддомены wildcard-поиском: %.родительский-домен
        int dot = domain.indexOf('.');
        if (dot > 0) {
            String parent = domain.substring(dot + 1);
            assertTrue(!repository.search("%." + parent, false).isEmpty(),
                    "wildcard %. " + parent + " должен находить " + domain);
        }

        // повторная вставка тех же (log_url, log_index) ничего не добавляет
        repository.insertCertificates(rows);
        assertEquals(found.size(), repository.search(domain, false).size());
    }

    @Test
    void excludeExpiredFiltersOutOldCerts() throws Exception {
        CertificateRepository.NewCert original = fixtureCerts().get(0);
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
        String domain = rows.get(0).domains().iterator().next();

        List<Long> ids = repository.searchIds(domain, false, 1, true);
        org.junit.jupiter.api.Assertions.assertFalse(ids.isEmpty());
        assertNotNull(cacheManager.getCache("search").get(domain + "::1:true"),
                "список id поиска должен попасть в кеш search");

        List<SearchRow> found = repository.searchRows(ids, ids.size());
        org.junit.jupiter.api.Assertions.assertFalse(found.isEmpty());

        CertDetails details = repository.getCertDetails(found.get(0).id());
        assertNotNull(details);
        assertNotNull(cacheManager.getCache("certById").get(found.get(0).id()),
                "детали сертификата должны попасть в кеш certById");
        assertNull(cacheManager.getCache("certById").get(-1L),
                "null-результат не кешируется");
    }

    @Test
    void searchRowsPageKeepsTotalInResultCount() throws Exception {
        List<CertificateRepository.NewCert> rows = fixtureCerts();
        repository.insertCertificates(rows);
        String domain = rows.get(0).domains().iterator().next();

        List<Long> ids = repository.searchIds(domain, false, 1, true);
        assertTrue(ids.size() >= 1);
        List<SearchRow> page = repository.searchRows(ids.subList(0, 1), ids.size());
        assertEquals(1, page.size(), "страница — только переданные id");
        assertEquals(ids.size(), page.get(0).result_count(),
                "result_count — общее число совпадений, а не размер страницы");
    }

    @Test
    void sameCertificateFoundInMultipleLogs() throws Exception {
        CertificateRepository.NewCert original = fixtureCerts().get(0);
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

        CertDetails details = repository.getCertDetails(found.get(0).id());
        assertEquals("https://other.example/log/", details.log_url(),
                "показывается самое раннее попадание в лог");
        assertEquals(2, details.occurrences().size(),
                "сертификат должен показывать вхождения из обоих логов");
        assertTrue(details.occurrences().stream()
                .anyMatch(o -> "https://other.example/log/".equals(o.log_url())));
    }

    @Test
    void caIdsAreSharedPerIssuerDn() throws Exception {
        List<CertificateRepository.NewCert> rows = fixtureCerts();
        repository.insertCertificates(rows);
        Set<Long> issuerIds = new TreeSet<>();
        for (SearchRow r : repository.search(rows.get(0).domains().iterator().next(), false)) {
            issuerIds.add(r.issuer_ca_id());
        }
        assertEquals(1, issuerIds.size(), "все сертификаты одного издателя — один issuer_ca_id");
    }
}
