package ru.crt.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static ru.crt.TestFixtures.der;

import org.junit.jupiter.api.Test;

/** Дамп в духе {@code openssl x509 -text} на реальных записях: структура и форматы закреплены. */
class CertTextDumpTest {

    @Test
    void rendersOpensslLikeStructure() {
        String dump = CertTextDump.render(der(0));

        assertTrue(dump.startsWith("Certificate:\n    Data:\n"));
        assertTrue(dump.contains("Version: 3 (0x2)"));
        assertTrue(dump.contains("Serial Number:\n            01:8A:8C:E5:85:4E:25:04:82:13:8E:0D"),
                "серийник — hex в верхнем регистре через двоеточие");
        assertTrue(dump.contains("Signature Algorithm: ecdsa-with-SHA256"),
                "имя алгоритма переводится из джавовского SHA256withECDSA");
        assertTrue(dump.contains("Public Key Algorithm: id-ecPublicKey"));
        assertTrue(dump.contains("\n            256 bit\n"));
        // DN печатается по одному атрибуту в строке, с полными именами и выравниванием
        assertTrue(dump.contains("Issuer:\n                commonName              = TCI ECDSA B1 16-280"));
        assertTrue(dump.contains("Subject:\n                commonName              = cateam.ru"));
        assertTrue(dump.contains("Not Before: Jan 19 10:26:30 2026 GMT"));
        assertTrue(dump.contains("Not After : Jan 19 10:26:30 2027 GMT"));
    }

    @Test
    void rendersKnownExtensionsByNameAndSanAsHostnames() {
        String dump = CertTextDump.render(der(0));

        assertTrue(dump.contains("X509v3 extensions:"));
        assertTrue(dump.contains("X509v3 Key Usage: critical"), "критичность расширения видна");
        assertTrue(dump.contains("CT Precertificate SCTs: "), "OID переводится в имя, как у openssl");
        assertTrue(dump.contains("Authority Information Access: "));
        // SAN разбирается в имена, а не печатается hex-дампом
        assertTrue(dump.contains("""
                X509v3 Subject Alternative Name:\s
                                DNS:cateam.ru
                                DNS:www.cateam.ru
                """));
        // остальные расширения — hex по 16 байт в строке, в нижнем регистре
        assertTrue(dump.contains("\n                03:02:07:80\n"), "X509v3 Key Usage");
    }

    /** День дополняется пробелом, а не нулём: у openssl это "Jan  1", а не "Jan 01". */
    @Test
    void singleDigitDayIsPaddedWithSpace() {
        assertTrue(CertTextDump.render(der(1)).contains("Not Before: Jan  1 09:09:12 2025 GMT"));
    }

    /** Содержимое приходит из публичного лога: неразобранный DER — это null, а не 500. */
    @Test
    void unparseableCertificateGivesNoDump() {
        assertNull(CertTextDump.render(new byte[]{0x30, 0x00}));
        assertNull(CertTextDump.render(new byte[0]));
    }

    /** Форма строки имени; границы самого разбора закреплены в CertificateNamesTest. */
    @Test
    void sanNamesArePrintedWithTheirTypeAndIndent() {
        StringBuilder s = new StringBuilder();

        CertTextDump.appendSan(s, new byte[]{0x30, 0x06, (byte) 0x82, 0x04, 'a', '.', 'r', 'u'});

        assertEquals("                DNS:a.ru\n", s.toString());
    }

    /** Метка закрывает свою строку и при пустом значении — иначе приклеилась бы следующая. */
    @Test
    void othernameLabelEndsItsLine() {
        StringBuilder empty = new StringBuilder();
        StringBuilder hex = new StringBuilder();

        CertTextDump.appendSan(empty, new byte[]{0x30, 0x02, (byte) 0x84, 0x00});
        CertTextDump.appendSan(hex, new byte[]{0x30, 0x05, (byte) 0x84, 0x03, 0x11, 0x22, 0x33});

        assertEquals("                othername:\n", empty.toString());
        assertEquals("                othername:\n                11:22:33\n", hex.toString());
    }
}
