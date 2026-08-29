package ru.crt.web;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.List;
import org.junit.jupiter.api.Test;
import ru.crt.sync.CtEntryParser;

import static org.junit.jupiter.api.Assertions.*;

/** Дамп в духе openssl x509 -text на реальной записи из фикстуры. */
class CertTextDumpTest {

    @Test
    void rendersOpensslLikeDump() throws Exception {
        try (InputStream in = CertTextDumpTest.class.getResourceAsStream("/ct-entries-fixture.json")) {
            assertNotNull(in);
            var entry = new ObjectMapper().readTree(in).get("entries").get(1); // precert
            CtEntryParser.ParsedEntry parsed = CtEntryParser.parse(
                    entry.get("leaf_input").asText(), entry.get("extra_data").asText());

            String dump = CertTextDump.render(parsed.certificate().getEncoded());
            assertNotNull(dump);
            assertTrue(dump.contains("Certificate:"), "должен начинаться с Certificate:");
            assertTrue(dump.contains("Version: "));
            assertTrue(dump.contains("Serial Number:"));
            assertTrue(dump.contains("Issuer:"));
            assertTrue(dump.contains("Not Before:"));
            assertTrue(dump.contains("Subject:"));
            assertTrue(dump.contains("GMT"), "даты в openssl-формате с GMT");
            assertTrue(dump.contains("X509v3 extensions:"));
            assertTrue(dump.contains("Signature Algorithm:"));
            // идентификаторы сертификата присутствуют в дампе
            for (String domain : parsed.domains()) {
                assertTrue(dump.contains(domain), "дамп должен содержать идентификатор " + domain);
            }
            assertNotNull(CertTextDump.sha1Hex(parsed.certificate().getEncoded()));
            assertEquals(40, CertTextDump.sha1Hex(parsed.certificate().getEncoded()).length());
        }
    }

    /** Наивный split по ',' рвал значения с экранированной запятой, а Map терял повторный OU. */
    @Test
    void dnKeepsEscapedCommasAndRepeatedTypes() {
        var rdns = CertTextDump.parseRdns("CN=Foo\\, Inc.,OU=Ops,OU=IT,O=Bar");
        assertEquals(List.of("CN", "OU", "OU", "O"),
                rdns.stream().map(CertTextDump.Rdn::type).toList());
        assertEquals("Foo, Inc.", rdns.getFirst().value(), "экранированная запятая остаётся в значении");
        assertEquals("IT", rdns.get(2).value(), "второй OU не должен затирать первый");
    }

    /** Экранированный ведущий пробел и DER-значение в форме #hex — как их печатает openssl. */
    @Test
    void dnUnescapesValuesAndDecodesHexForm() {
        var rdns = CertTextDump.parseRdns(
                "1.2.840.113549.1.9.1=#161161646d696e4061637469766562632e7275,O=\\ ActiveBusinessConsult");
        assertEquals("admin@activebc.ru", rdns.getFirst().value(), "#hex — это DER IA5String");
        assertEquals(" ActiveBusinessConsult", rdns.get(1).value(), "\\<пробел> — это пробел, а не слэш");
    }

    /** Содержимое сертификата приходит от кого угодно: кривая длина DER не должна кидать. */
    @Test
    void malformedDerLengthsAreRejectedInsteadOfThrowing() {
        assertEquals(-1, CertTextDump.derLength(new byte[]{0x04, (byte) 0x84}, 1),
                "поле длины обещает 4 байта, а данных нет");
        assertEquals(-1, CertTextDump.derLength(new byte[]{0x04, (byte) 0x84, -1, -1, -1, -1}, 1),
                "длина 0xFFFFFFFF не влезает в int");
        byte[] truncated = {0x04, 0x7F, 0x01, 0x02}; // обещано 127 байт, есть 2
        assertSame(truncated, CertTextDump.unwrapOctetString(truncated),
                "обрезанную обёртку отдаём как есть, а не копируем за границу массива");
    }
}
