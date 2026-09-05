package ru.crt.x509;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import ru.crt.TestFixtures;

/** Имена сертификата: dNSName из SAN и CN из subject DN. */
class CertificateNamesTest {

    /** U+FFFD, в который JDK превращает каждый не-ASCII байт dNSName. */
    private static final char REPLACEMENT = '\uFFFD';

    @Test
    void domainsAreLowercasedAndDeduplicatedWithTheCommonName() {
        // CN=cateam.ru дублирует первый dNSName, поэтому доменов два, а не три
        assertEquals(List.of("cateam.ru", "www.cateam.ru"),
                List.copyOf(CertificateNames.domainsOf(TestFixtures.certificate(0))),
                "порядок записи SAN сохраняется");
    }

    /** Регрессия: кириллический dNSName, ради которой SAN разбирается своими силами. */
    @Test
    void cyrillicDnsNameIsDecodedAsUtf8AndIndexedInOneForm() throws Exception {
        X509Certificate cert = TestFixtures.pemCertificate(TestFixtures.CYRILLIC_CERT);

        assertEquals(Set.of(TestFixtures.CYRILLIC_DOMAIN), CertificateNames.domainsOf(cert));
        assertTrue(cert.getSubjectAlternativeNames().stream()
                        .anyMatch(name -> name.get(1).toString().indexOf(REPLACEMENT) >= 0),
                "иначе JDK уже читает такие имена сам и тест перестал что-либо значить");
    }

    /** Граница списка — конец SEQUENCE, а не конца буфера. */
    @Test
    void generalNamesStopAtTheEndOfTheirSequence() {
        byte[] withTrailingName = {
                0x30, 0x06, (byte) 0x82, 0x04, 'a', '.', 'r', 'u', // SEQUENCE { DNS:a.ru }
                (byte) 0x82, 0x05, 'e', 'v', 'i', 'l', '!'};       // хвост за SEQUENCE

        List<CertificateNames.GeneralName> names = CertificateNames.generalNames(withTrailingName);

        assertEquals(1, names.size());
        assertEquals(CertificateNames.GeneralName.DNS, names.getFirst().tag());
        assertEquals("a.ru", names.getFirst().text());
    }

    /** Длина имени, вылезающая за SEQUENCE, — такого имени нет вовсе. */
    @Test
    void namesThatOverrunTheirSequenceAreNotRead() {
        byte[] overrunning = {
                0x30, 0x04, (byte) 0x82, 0x08, 'a', 'b', // в SEQUENCE 4 байта, внутри только "ab"
                'c', 'd', 'e', 'f', 'g', 'h'};           // dNSName обещает 8 — это уже за SEQUENCE

        assertEquals(List.of(), CertificateNames.generalNames(overrunning));
    }

    @Test
    void namesAreReadableInReports() {
        byte[] san = {0x30, 0x06, (byte) 0x82, 0x04, 'a', '.', 'r', 'u'};

        CertificateNames.GeneralName name = CertificateNames.generalNames(san).getFirst();

        assertEquals("GeneralName[tag=0x82, a.ru]", name.toString());
    }

    @Test
    void onlyPlausibleDomainsGoIntoTheIndex() {
        assertTrue(CertificateNames.indexable("*.кот.рф"), "кириллица — ровно то, ради чего свой разбор");
        assertFalse(CertificateNames.indexable("a".repeat(254)), "длиннее, чем бывает имя по RFC 1035");
        assertFalse(CertificateNames.indexable("good.ru\0.evil.ru"));
        assertFalse(CertificateNames.indexable("a.ru\n                DNS:evil.ru"));
        assertFalse(CertificateNames.indexable(" "));
    }

    /** Нечитаемый список — это null, а не пустой: вызывающий покажет hex. */
    @Test
    void unreadableGeneralNamesAreNotGuessed() {
        assertNull(CertificateNames.generalNames(null), "расширения нет");
        assertNull(CertificateNames.generalNames(new byte[]{(byte) 0x82, 0x04, 'a', '.', 'r', 'u'}),
                "снаружи должен быть SEQUENCE");
        assertNull(CertificateNames.generalNames(new byte[]{0x30, 0x7F, 0x01}),
                "SEQUENCE обещает 127 байт, есть 1");
    }

    @Test
    void truncatedNameEndsTheList() {
        List<CertificateNames.GeneralName> names = CertificateNames.generalNames(new byte[]{
                0x30, 0x08, (byte) 0x82, 0x04, 'a', '.', 'r', 'u', (byte) 0x82, 0x7F});

        assertEquals(List.of("a.ru"), names.stream().map(CertificateNames.GeneralName::text).toList());
    }
}
