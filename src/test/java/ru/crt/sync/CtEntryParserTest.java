package ru.crt.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/** Разбор реальных записей Yandex-логов: x509_entry (2027-лог) и precert_entry (2026-лог). */
class CtEntryParserTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode fixture() {
        try (InputStream in = CtEntryParserTest.class
                .getResourceAsStream("/ct-entries-fixture.json")) {
            assertNotNull(in, "фикстура ct-entries-fixture.json не найдена в classpath");
            return JSON.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void parsesX509Entry() throws IOException {
        JsonNode entry = fixture().get("entries").get(0); // [x509, precert]
        CtEntryParser.ParsedEntry parsed =
                CtEntryParser.parse(entry.get("leaf_input").asText(), entry.get("extra_data").asText());

        assertFalse(parsed.precert());
        assertNotNull(parsed.certificate().getSubjectX500Principal());
        assertNotNull(parsed.certificate().getNotBefore());
        assertFalse(parsed.domains().isEmpty(), "должен быть хотя бы один домен (CN или SAN)");
        for (String domain : parsed.domains()) {
            assertEquals(domain.toLowerCase(), domain, "домены приводятся к lower case");
        }
        // timestamp из MerkleTreeLeaf — миллисекунды epoch, разумный диапазон
        Instant ts = Instant.ofEpochMilli(parsed.entryTimestampMs());
        assertTrue(ts.isAfter(Instant.parse("2020-01-01T00:00:00Z")));
        assertTrue(ts.isBefore(Instant.parse("2030-01-01T00:00:00Z")));
    }

    @Test
    void parsesPrecertEntry() throws IOException {
        JsonNode entry = fixture().get("entries").get(1);
        CtEntryParser.ParsedEntry parsed =
                CtEntryParser.parse(entry.get("leaf_input").asText(), entry.get("extra_data").asText());

        assertTrue(parsed.precert());
        // полный precert берётся из extra_data и парсится CertificateFactory'ем
        assertNotNull(parsed.certificate().getSerialNumber());
        assertFalse(parsed.domains().isEmpty());
    }

    @Test
    void rejectsGarbage() {
        assertThrows(IOException.class, () -> CtEntryParser.parse("!!!не-base64!!!", null));
    }

    @Test
    void rejectsTruncatedLeaf() {
        byte[] tooShort = new byte[5];
        String b64 = Base64.getEncoder().encodeToString(tooShort);
        assertThrows(IOException.class, () -> CtEntryParser.parse(b64, null));
    }

    @Test
    void rejectsUnsupportedEntryType() {
        byte[] leaf = new byte[15];
        leaf[0] = 0;   // version v1
        leaf[1] = 0;   // leaf_type timestamped_entry
        // bytes 2..9 — timestamp, нули допустимы
        leaf[10] = 0x12;
        leaf[11] = 0x34; // entry_type 0x1234 — не существует
        String b64 = Base64.getEncoder().encodeToString(leaf);
        assertThrows(IOException.class, () -> CtEntryParser.parse(b64, null));
    }
}
