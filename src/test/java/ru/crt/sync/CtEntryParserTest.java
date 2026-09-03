package ru.crt.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.Base64;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import ru.crt.TestFixtures;
import ru.crt.client.CtLogClient;

/**
 * Разбор записей CT-лога: реальные записи Yandex-логов (x509_entry и precert_entry)
 * и всё, чем запись может оказаться битой. Данные приходят из публичного лога, то есть
 * от кого угодно, поэтому каждая ошибка формата обязана быть IOException, а не
 * ArrayIndexOutOfBounds где-то в середине.
 */
class CtEntryParserTest {

    /** MerkleTreeLeaf вручную: version(1) + leaf_type(1) + timestamp(8) + entry_type(2) + payload. */
    private static String leaf(int version, int leafType, int entryType, int... payload) {
        byte[] out = new byte[12 + payload.length];
        out[0] = (byte) version;
        out[1] = (byte) leafType;
        out[10] = (byte) (entryType >> 8);
        out[11] = (byte) entryType;
        for (int i = 0; i < payload.length; i++) {
            out[12 + i] = (byte) payload[i];
        }
        return Base64.getEncoder().encodeToString(out);
    }

    /** Сообщение обязательного IOException: тесты проверяют, что в нём названо битое поле. */
    private static String messageOf(Executable call) {
        return assertThrows(IOException.class, call).getMessage();
    }

    /** Запись фикстуры целиком, как её отдаёт лог: [0] — x509, [1] — precert. */
    private static CtEntryParser.ParsedEntry parseFixture(int index) throws IOException {
        CtLogClient.EntryDoc entry = TestFixtures.entries().get(index);
        return CtEntryParser.parse(entry.leafInput(), entry.extraData());
    }

    @Test
    void parsesX509Entry() throws IOException {
        CtEntryParser.ParsedEntry parsed = parseFixture(0);

        assertFalse(parsed.precert());
        assertEquals("CN=cateam.ru", parsed.certificate().getSubjectX500Principal().getName());
        // CN и все dNSName из SAN, в нижнем регистре и без повторов (CN дублирует первый SAN)
        assertEquals(Set.of("cateam.ru", "www.cateam.ru"), parsed.domains());
        // timestamp — 8 байт big-endian из MerkleTreeLeaf, миллисекунды epoch
        assertEquals(1768819544153L, parsed.entryTimestampMs());
    }

    @Test
    void parsesPrecertEntry() throws IOException {
        CtEntryParser.ParsedEntry parsed = parseFixture(1);

        assertTrue(parsed.precert());
        // в leaf у precert лежит только TBS, поэтому полный сертификат берётся из extra_data
        assertEquals("CN=khaos.today", parsed.certificate().getSubjectX500Principal().getName());
        assertEquals(Set.of("khaos.today", "www.khaos.today"), parsed.domains());
        assertEquals(1735722581122L, parsed.entryTimestampMs());
    }

    @Test
    void rejectsGarbage() {
        assertTrue(messageOf(() -> CtEntryParser.parse("!!!не-base64!!!", null)).contains("base64"));
    }

    @Test
    void rejectsTruncatedLeaf() {
        String b64 = Base64.getEncoder().encodeToString(new byte[5]);
        assertTrue(messageOf(() -> CtEntryParser.parse(b64, null)).contains("слишком короткий"));
    }

    @Test
    void rejectsUnsupportedVersionAndLeafType() {
        assertTrue(messageOf(() -> CtEntryParser.parse(leaf(1, 0, 0), null)).contains("версия"));
        assertTrue(messageOf(() -> CtEntryParser.parse(leaf(0, 7, 0), null)).contains("leaf_type"));
    }

    @Test
    void rejectsUnsupportedEntryType() {
        assertTrue(messageOf(() -> CtEntryParser.parse(leaf(0, 0, 0x1234), null)).contains("entry_type"));
    }

    /** Полный precert лежит в extra_data — без него запись разобрать нечем. */
    @Test
    void rejectsPrecertWithoutExtraData() {
        assertTrue(messageOf(() -> CtEntryParser.parse(leaf(0, 0, 1), null)).contains("без extra_data"));
        assertTrue(messageOf(() -> CtEntryParser.parse(leaf(0, 0, 1), "")).contains("без extra_data"));
    }

    /** 3-байтовый length-prefix приходит из лога: он может обещать больше, чем прислали. */
    @Test
    void rejectsOpaqueLengthOutsideTheBuffer() {
        assertTrue(messageOf(() -> CtEntryParser.parse(leaf(0, 0, 0, 0x00, 0x00), null))
                .contains("length-prefix"), "на длину не хватает даже трёх байт");
        assertTrue(messageOf(() -> CtEntryParser.parse(leaf(0, 0, 0, 0x00, 0x00, 0xFF, 0x41), null))
                .contains("выходит за границы"), "обещано 255 байт, прислан один");
    }

    @Test
    void rejectsPayloadThatIsNotACertificate() {
        assertTrue(messageOf(() -> CtEntryParser.parse(leaf(0, 0, 0, 0x00, 0x00, 0x02, 0x41, 0x42), null))
                .contains("DER-сертификат"));
    }
}
