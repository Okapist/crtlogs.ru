package ru.crt.x509;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static ru.crt.TestFixtures.der;

import java.security.cert.CertificateException;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Отпечатки и PEM на реальном сертификате фикстуры: их формы — часть контракта crt.sh. */
class X509Test {

    @Test
    void sha256IsLowercaseAndMatchesItsWellKnownValue() {
        assertEquals("86ffeac62ee5f5f8ccca873a0019303efc194ba9104a17d4ba9eb7401aef1582",
                X509.sha256Hex(der(0)), "так он лежит в certificate.sha256 и уходит в JSON");
    }

    @Test
    void sha1IsUppercaseAndMatchesTheCertificate() {
        assertEquals("024929F390A05DE1A6578B7F596852D98AB9A459", X509.sha1Hex(der(0)));
    }

    /** PEM отдаётся и страницей, и /?d=; форма должна читаться обратно как тот же DER. */
    @Test
    void pemIsWrappedAtSixtyFourCharactersAndParsesBack() throws Exception {
        String pem = X509.toPem(der(0));

        assertTrue(pem.startsWith("-----BEGIN CERTIFICATE-----\n"));
        assertTrue(pem.endsWith("\n-----END CERTIFICATE-----\n"));
        List<String> lines = pem.lines().toList();
        assertTrue(lines.subList(1, lines.size() - 1).stream().allMatch(line -> line.length() <= 64),
                "PEM переносится по 64 символа");
        assertArrayEquals(der(0), X509.parse(pem.getBytes(US_ASCII)).getEncoded());
    }

    /** Содержимое приходит из публичного лога: битый DER — исключение, которое вызывающий решает сам. */
    @Test
    void unparseableBytesAreReportedNotGuessed() {
        assertThrows(CertificateException.class, () -> X509.parse(new byte[]{0x30, 0x00}));
        assertThrows(CertificateException.class, () -> X509.parse(new byte[0]));
    }
}
