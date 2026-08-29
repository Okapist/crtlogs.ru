package ru.crt.web;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
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
}
