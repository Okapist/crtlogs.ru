package ru.crt;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import ru.crt.client.CtLogClient;
import ru.crt.sync.CtEntryParser;
import ru.crt.web.CertDetails;
import ru.crt.web.SearchRow;
import ru.crt.x509.X509;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Общая загрузка фикстур и сборка объектов контракта, форму конструкторов которых
 * знает только этот класс.
 *
 * <p>Разобранные записи и сертификаты считаются один раз на весь прогон: фикстура
 * неизменяемая, а base64 + разбор X.509 повторялись бы на каждый вызов — в стабах
 * Mockito это десятки перечитываний файла ради одного и того же объекта.
 */
public final class TestFixtures {

    /** Реальные записи CT-лога: [0] — x509, [1] — precert. */
    public static final String ENTRIES = "/ct-entries-fixture.json";

    /** Реальный ctlog.json: три оператора, шесть логов. */
    public static final String LOG_LIST = "/ctlog-list-fixture.json";

    /**
     * Реальный сертификат УЦ Минцифры на домен .рф: и в CN, и в dNSName имя записано
     * кириллицей в UTF-8, а не punycode — см. {@code ru.crt.x509.CertificateNames}.
     */
    public static final String CYRILLIC_CERT = "/cyrillic-san-fixture.pem";

    /** Домен этого сертификата (он же CN) как записан — и он же индексная форма имени. */
    public static final String CYRILLIC_DOMAIN = "антоненков.рф";

    /** Он же в punycode — в таком виде тот же домен записывают остальные УЦ. */
    public static final String CYRILLIC_DOMAIN_ASCII = "xn--80aeiunabfe8a.xn--p1ai";

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final List<CtLogClient.EntryDoc> ENTRY_DOCS = loadEntries();
    private static final List<X509Certificate> CERTIFICATES = parseCertificates();

    private TestFixtures() {
    }

    public static String text(String resource) {
        try (InputStream in = TestFixtures.class.getResourceAsStream(resource)) {
            assertNotNull(in, "ресурс " + resource + " не найден");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Тот же ресурс байтами — в таком виде тела ответов приходят от лога. */
    public static byte[] bytes(String resource) {
        return text(resource).getBytes(StandardCharsets.UTF_8);
    }

    private static JsonNode json(String resource) {
        return JSON.readTree(text(resource));
    }

    /** Записи фикстуры в том виде, в каком их отдаёт лог, — как их получает CtSyncService. */
    public static List<CtLogClient.EntryDoc> entries() {
        return ENTRY_DOCS;
    }

    /** Сертификат записи фикстуры: [0] — x509 cateam.ru (ECDSA), [1] — precert khaos.today. */
    public static X509Certificate certificate(int index) {
        return CERTIFICATES.get(index);
    }

    /** DER того же сертификата; getEncoded() и так отдаёт копию, менять её безопасно. */
    public static byte[] der(int index) {
        return der(certificate(index));
    }

    /** То же для сертификата, разобранного из PEM: в записях лога такого нет. */
    public static byte[] der(X509Certificate cert) {
        try {
            return cert.getEncoded();
        } catch (CertificateEncodingException e) {
            throw new IllegalStateException("сертификат фикстуры не кодируется обратно в DER", e);
        }
    }

    /** Сертификат из PEM-ресурса — так лежат фикстуры, которых нет в записях лога. */
    public static X509Certificate pemCertificate(String resource) {
        return pemCertificate(bytes(resource));
    }

    /**
     * Запись лога (x509_entry) с этим сертификатом — для фикстур, которых в настоящих
     * записях нет: так они проходят через тот же {@code CtSyncService.toNewCert}, что и
     * прод, а не через свою копию маппинга. MerkleTreeLeaf: version(1) + leaf_type(1) +
     * timestamp(8) + entry_type(2) + opaque(3-байтовая длина + DER).
     */
    public static CtLogClient.EntryDoc entryOf(X509Certificate cert, long timestampMs) {
        byte[] der = der(cert);
        byte[] leaf = new byte[15 + der.length];
        for (int i = 0; i < 8; i++) {
            leaf[2 + i] = (byte) (timestampMs >>> (8 * (7 - i)));
        }
        leaf[12] = (byte) (der.length >> 16);
        leaf[13] = (byte) (der.length >> 8);
        leaf[14] = (byte) der.length;
        System.arraycopy(der, 0, leaf, 15, der.length);
        return new CtLogClient.EntryDoc(Base64.getEncoder().encodeToString(leaf), null);
    }

    /** Тот же разбор для PEM, который тест построил сам, а не взял из ресурсов. */
    public static X509Certificate pemCertificate(byte[] pem) {
        try {
            return X509.parse(pem);
        } catch (CertificateException e) {
            throw new IllegalStateException("PEM-фикстура не разбирается", e);
        }
    }

    /**
     * Заполненная строка выдачи; параметрами вынесено то, что тесты реально меняют.
     * Единственное место, знающее форму конструктора SearchRow.
     */
    public static SearchRow searchRow(long id, String commonName, String identities, String issuer) {
        return new SearchRow(id, 7L, issuer, commonName, identities,
                "2026-08-03T09:04:23.123", "2026-01-19T10:26:30", "2027-01-19T10:26:30", "ff", 42);
    }

    /** Та же строка, но пустая везде, кроме id: даты в БД необязательны. */
    public static SearchRow blankSearchRow(long id) {
        return new SearchRow(id, null, null, null, null, null, null, null, null, 0);
    }

    /** Вхождение сертификата в лог; описание и оператор заполнены. */
    public static CertDetails.LogOccurrence occurrence(String logUrl, long logIndex,
                                                       String entryTimestamp) {
        return new CertDetails.LogOccurrence(logUrl, "Example log", "Operator", logIndex,
                entryTimestamp);
    }

    /** То же для CertDetails: все поля непустые, кроме явно переданных. */
    public static CertDetails certDetails(boolean precert, List<CertDetails.LogOccurrence> occurrences,
                                          String text, String issuerName) {
        return new CertDetails(5, 7L, issuerName, "cateam.ru", "cateam.ru\nwww.cateam.ru",
                List.of("cateam.ru", "www.cateam.ru"), "https://ct.example/log/", 17,
                "abcdefabcdef", "0011AA", "ff00", "CN=cateam.ru", "CN=Issuer DN",
                "2026-01-19T10:26:30", "2027-01-19T10:26:30", "2026-08-03T09:04:23.123",
                precert, occurrences, text, "-----BEGIN CERTIFICATE-----\nMIIB\n-----END CERTIFICATE-----\n");
    }

    private static List<CtLogClient.EntryDoc> loadEntries() {
        JsonNode entries = json(ENTRIES).get("entries");
        List<CtLogClient.EntryDoc> out = new ArrayList<>(entries.size());
        for (JsonNode e : entries) {
            out.add(new CtLogClient.EntryDoc(
                    e.get("leaf_input").stringValue(), e.get("extra_data").stringValue()));
        }
        return List.copyOf(out);
    }

    private static List<X509Certificate> parseCertificates() {
        List<X509Certificate> out = new ArrayList<>(ENTRY_DOCS.size());
        try {
            for (CtLogClient.EntryDoc e : ENTRY_DOCS) {
                out.add(CtEntryParser.parse(e.leafInput(), e.extraData()).certificate());
            }
        } catch (IOException e) {
            throw new UncheckedIOException("запись фикстуры не разбирается", e);
        }
        return List.copyOf(out);
    }
}
