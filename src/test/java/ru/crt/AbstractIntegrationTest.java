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
 * База для тестов, которым нужен поднятый контекст. Настройка одна на всех, поэтому
 * Spring переиспользует один контекст, а не поднимает его на каждый класс.
 *
 * <p>H2 in-memory живёт вместе с контекстом, то есть одна на все такие тесты, поэтому
 * данные чистятся перед каждым тестом: иначе классы видели бы строки друг друга и
 * результат зависел бы от порядка выполнения.
 *
 * <p>{@code @AutoConfigureMockMvc} стоит здесь, а не на классе с MockMvc: он попадает в
 * ключ кеша контекстов, поэтому на подклассе развалил бы «один контекст на всех» —
 * Spring поднимал бы второй со своим пулом Hikari. Тем, кто MockMvc не инжектит, он ничем
 * не мешает: @SpringBootTest и так поднимает mock-окружение сервлета.
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

    /**
     * Те же строки, что построил бы синк из фикстуры ({@code [0]} — x509 cateam.ru,
     * {@code [1]} — precert khaos.today), — через {@link CtSyncService#toNewCert}, а не
     * через свою копию маппинга.
     */
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

    /**
     * Вставленный сертификат Минцифры на кириллический домен: и CN, и dNSName записаны в
     * нём юникодом. Идёт тем же путём, что и записи настоящих логов ({@link #fixtureCerts}).
     */
    protected void insertCyrillicCert() throws Exception {
        X509Certificate cert = TestFixtures.pemCertificate(TestFixtures.CYRILLIC_CERT);
        repository.insertCertificates(List.of(CtSyncService.toNewCert(LOG_URL, 900,
                TestFixtures.entryOf(cert, System.currentTimeMillis()))));
    }

    /**
     * Копия строки с переопределёнными полями: у record'а нет wither'ов, а тестам нужны
     * варианты одной и той же записи. Единственное место, знающее форму конструктора.
     */
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

    /**
     * Тот же сертификат, найденный в другом логе: sha256 и все даты сохраняются, иначе
     * это был бы уже другой сертификат. Дальше — три варианта «то же самое, но с другим
     * X»: имя вызова говорит, что именно меняется, а восемь позиционных аргументов
     * {@link #copyOf} остаются в одном месте.
     */
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
