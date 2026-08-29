package ru.crt.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import ru.crt.config.AppProperties;
import ru.crt.sync.CtEntryParser;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Разбор JSON без сети: на реальной фикстуре ctlog.json и ответов логов. */
class CtLogClientTest {

    private final CtLogClient client = new CtLogClient(new ObjectMapper(), new AppProperties());

    private static String resource(String name) {
        try (InputStream in = CtLogClientTest.class.getResourceAsStream(name)) {
            assertNotNull(in, "ресурс " + name + " не найден");
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void parsesRealLogList() throws IOException {
        CtLogClient.LogListDoc doc = client.parseLogListDoc(resource("/ctlog-list-fixture.json"));
        assertEquals(3, doc.operators().size());
        int logs = doc.operators().stream().mapToInt(op -> op.logs().size()).sum();
        assertEquals(6, logs);
        assertTrue(doc.operators().stream()
                .flatMap(op -> op.logs().stream())
                .allMatch(CtLogClient.LogDoc::isUsableOrReadonly));
    }

    @Test
    void stateFilterAcceptsUsableAndReadonlyOnly() {
        ObjectMapper mapper = new ObjectMapper();
        assertTrue(new CtLogClient.LogDoc("d", "u", Map.of("usable", mapper.nullNode()))
                .isUsableOrReadonly());
        assertTrue(new CtLogClient.LogDoc("d", "u", Map.of("readonly", mapper.nullNode()))
                .isUsableOrReadonly());
        assertFalse(new CtLogClient.LogDoc("d", "u", Map.of("pending", mapper.nullNode()))
                .isUsableOrReadonly());
        assertFalse(new CtLogClient.LogDoc("d", "u", null).isUsableOrReadonly());
    }

    @Test
    void parsesSth() throws IOException {
        CtLogClient.Sth sth = client.parseSth(
                "{\"tree_size\":8956,\"timestamp\":1787871725409,"
                        + "\"sha256_root_hash\":\"hzg=\",\"tree_head_signature\":\"BAMA=\"}");
        assertEquals(8956, sth.tree_size());
        assertEquals(1787871725409L, sth.timestamp());
    }

    @Test
    void parsesEntries() throws IOException {
        CtLogClient.EntriesDoc doc = client.parseEntries(resource("/ct-entries-fixture.json"));
        List<CtLogClient.EntryDoc> entries = doc.entries();
        assertEquals(2, entries.size());
        for (CtLogClient.EntryDoc e : entries) {
            assertFalse(e.leaf_input().isEmpty());
        }
        assertNotNull(entries.get(1).extra_data(), "precert-запись должна иметь extra_data");
    }

    @Test
    void trustedSslContextLoadsExtraPem() throws Exception {
        // сертификат из фикстуры записываем во временный PEM и строим контекст «cacerts + PEM»
        String fixture = resource("/ct-entries-fixture.json");
        CtEntryParser.ParsedEntry parsed = CtEntryParser.parse(
                new ObjectMapper().readTree(fixture).get("entries").get(0).get("leaf_input").asText(),
                new ObjectMapper().readTree(fixture).get("entries").get(0).get("extra_data").asText());
        Path pem = Files.createTempFile("ct-extra-trust", ".pem");
        pem.toFile().deleteOnExit();
        Files.writeString(pem, "-----BEGIN CERTIFICATE-----\n"
                + Base64.getMimeEncoder().encodeToString(parsed.certificate().getEncoded())
                + "\n-----END CERTIFICATE-----\n");

        SSLContext context = CtLogClient.trustedSslContext(List.of(pem));
        assertNotNull(context);
        assertNotNull(context.getSocketFactory());
    }

    @Test
    void trustedSslContextRejectsFileWithoutCertificates() throws Exception {
        Path empty = Files.createTempFile("ct-empty-trust", ".pem");
        empty.toFile().deleteOnExit();
        Files.writeString(empty, "not a certificate\n");
        assertThrows(IOException.class,
                () -> CtLogClient.trustedSslContext(List.of(empty)));
    }
}
