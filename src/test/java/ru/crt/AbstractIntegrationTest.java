package ru.crt;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.security.cert.X509Certificate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.cache.CacheManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import ru.crt.client.CtLogClient;
import ru.crt.db.CertificateRepository;
import ru.crt.db.SortColumn;
import ru.crt.sync.CtSyncService;
import ru.crt.web.CertDetailsService;
import ru.crt.web.SearchService;

/**
 * База для тестов с поднятым контекстом — один контекст на все классы, одна H2,
 * данные чистятся перед каждым тестом. @AutoConfigureMockMvc здесь, а не на подклассе:
 * он попадает в ключ кеша контекстов.
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class AbstractIntegrationTest {

    /** Лог, от имени которого тесты вставляют записи фикстуры. */
    protected static final String LOG_URL = "https://test.example/log/";

    /** Домен первой записи фикстуры (CN=cateam.ru, SAN + www); у второй — khaos.today. */
    protected static final String FIXTURE_DOMAIN = "cateam.ru";

    /** Синк поднимается вместе с контекстом — живой клиент ходил бы в реальные CT-логи. */
    @MockitoBean
    protected CtLogClient ctLogClient;

    @Autowired
    protected CertificateRepository repository;

    @Autowired
    protected SearchService searchService;

    @Autowired
    protected CertDetailsService certDetailsService;

    @Autowired
    protected CacheManager cacheManager;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    protected void resetDatabaseAndCaches() throws Exception {
        when(ctLogClient.fetchLogList(anyString())).thenReturn(List.of());
        jdbc.update("DELETE FROM cert_domain");
        jdbc.update("DELETE FROM certificate");
        jdbc.update("DELETE FROM ct_log");
        jdbc.update("DELETE FROM ca"); // кеш id издателей очищается строкой ниже
        cacheManager.getCacheNames().forEach(name -> cacheManager.getCache(name).clear());
    }

    /** Первая страница поиска по домену, id по возрастанию, — самый частый запрос тестов. */
    protected static CertificateRepository.SearchResult firstPage(CertificateRepository repository,
                                                                  String q) {
        return repository.searchPage(q, false, SortColumn.ID, false, 1, 100);
    }

    /** id строк, которые находит поиск по домену, по возрастанию. */
    protected List<Long> idsOf(String domain) {
        return firstPage(repository, domain).ids();
    }

    /** Строки, которые построил бы синк из фикстуры — через {@link CtSyncService#toNewCert}, а не свою копию. */
    protected List<CertificateRepository.NewCert> fixtureCerts() throws Exception {
        List<CtLogClient.EntryDoc> entries = TestFixtures.entries();
        List<CertificateRepository.NewCert> rows = new ArrayList<>(entries.size());
        for (int i = 0; i < entries.size(); i++) {
            rows.add(CtSyncService.toNewCert(LOG_URL, i, entries.get(i)));
        }
        return rows;
    }

    /** Вставленная в БД фикстура и её первая (x509, cateam.ru) строка. */
    protected CertificateRepository.NewCert insertFixture() throws Exception {
        List<CertificateRepository.NewCert> rows = fixtureCerts();
        repository.insertCertificates(rows);
        return rows.getFirst();
    }

    /** Сертификат Минцифры на кириллический домен — тем же путём, что и записи логов. */
    protected void insertCyrillicCert() throws Exception {
        X509Certificate cert = TestFixtures.pemCertificate(TestFixtures.CYRILLIC_CERT);
        repository.insertCertificates(List.of(CtSyncService.toNewCert(LOG_URL, 900,
                TestFixtures.entryOf(cert, System.currentTimeMillis()))));
    }

    /** Копия строки с переопределёнными полями; единственное место, знающее конструктор. */
    protected static CertificateRepository.NewCert copyOf(
            CertificateRepository.NewCert base, String logUrl, long logIndex, String sha256,
            LocalDateTime notBefore, LocalDateTime notAfter, LocalDateTime entryTs,
            String issuerDn, Set<String> domains) {
        return new CertificateRepository.NewCert(logUrl, logIndex, base.der(), sha256, base.serial(),
                base.subjectDn(), issuerDn, notBefore, notAfter, entryTs, base.precert(), domains);
    }

    /** Отпечаток той же длины (VARCHAR(64)), но другой — иначе строки схлопнет дедупликация. */
    protected static String otherSha256(CertificateRepository.NewCert base, String prefix) {
        return prefix + base.sha256().substring(prefix.length());
    }

    /** Тот же сертификат в другом логе: sha256 и все даты сохраняются. */
    protected static CertificateRepository.NewCert inLog(
            CertificateRepository.NewCert base, String logUrl, long logIndex) {
        return copyOf(base, logUrl, logIndex, base.sha256(), base.notBefore(), base.notAfter(),
                base.entryTs(), base.issuerDn(), base.domains());
    }

    /** Тот же сертификат с другим сроком действия (и, значит, другим отпечатком). */
    protected static CertificateRepository.NewCert withValidity(
            CertificateRepository.NewCert base, long logIndex, String sha256Prefix,
            LocalDateTime notBefore, LocalDateTime notAfter) {
        return copyOf(base, base.logUrl(), logIndex, otherSha256(base, sha256Prefix),
                notBefore, notAfter, base.entryTs(), base.issuerDn(), base.domains());
    }

    /** Тот же сертификат на других доменах. */
    protected static CertificateRepository.NewCert withDomains(
            CertificateRepository.NewCert base, long logIndex, String sha256Prefix,
            Set<String> domains) {
        return copyOf(base, base.logUrl(), logIndex, otherSha256(base, sha256Prefix),
                base.notBefore(), base.notAfter(), base.entryTs(), base.issuerDn(), domains);
    }

    /** Ещё одна запись того же сертификата в том же логе — отличается только индексом и отпечатком. */
    protected static CertificateRepository.NewCert twinOf(
            CertificateRepository.NewCert base, long logIndex, String sha256Prefix) {
        return copyOf(base, base.logUrl(), logIndex, otherSha256(base, sha256Prefix),
                base.notBefore(), base.notAfter(), base.entryTs(), base.issuerDn(), base.domains());
    }
}
