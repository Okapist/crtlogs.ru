package ru.crt.client;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.crt.TestFixtures;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.NullNode;

/** Клиент CT-логов: разбор JSON на реальных фикстурах и HTTP поверх локального сервера. */
class CtLogClientTest {

    private static final CtLogClient CLIENT =
            new CtLogClient(new ObjectMapper(), HttpClient.newHttpClient());

    // --- локальный лог ---

    private static HttpServer server;
    /** Что сервер отвечает на путь; отсутствие ответа = 503, как у прилёгшего лога. */
    private static final Map<String, String> RESPONSES = Collections.synchronizedMap(new HashMap<>());
    private static final List<String> REQUESTS = Collections.synchronizedList(new ArrayList<>());

    @BeforeAll
    static void startLocalLog() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            REQUESTS.add(exchange.getRequestURI().toString());
            String body = RESPONSES.get(exchange.getRequestURI().getPath());
            byte[] bytes = (body == null ? "log is down" : body).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(body == null ? 503 : 200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
    }

    @AfterAll
    static void stopLocalLog() {
        server.stop(0);
    }

    @BeforeEach
    void resetLocalLog() {
        RESPONSES.clear();
        REQUESTS.clear();
    }

    private static String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    private static void respond(String path, String body) {
        RESPONSES.put(path, body);
    }

    // --- разбор JSON ---

    /** Jackson 3 игнорирует неизвестные поля — проверяем на живом ctlog.json. */
    @Test
    void parsesRealLogListIgnoringFieldsWeDoNotModel() {
        CtLogClient.LogListDoc doc = CLIENT.parse(TestFixtures.bytes(TestFixtures.LOG_LIST), CtLogClient.LogListDoc.class);

        assertEquals(3, doc.operators().size());
        assertEquals(6, doc.operators().stream().mapToInt(op -> op.logs().size()).sum());
        CtLogClient.LogDoc first = doc.operators().getFirst().logs().getFirst();
        assertEquals("https://ct-agate.yandex.net/2026/", first.url());
        assertNotNull(first.description());
        assertNotNull(first.state(), "state нужен фильтру usable/readonly");
    }

    @Test
    void stateFilterAcceptsUsableAndReadonlyOnly() {
        JsonNode any = NullNode.getInstance();
        assertTrue(new CtLogClient.LogDoc("d", "u", Map.of("usable", any)).isUsableOrReadonly());
        assertTrue(new CtLogClient.LogDoc("d", "u", Map.of("readonly", any)).isUsableOrReadonly());
        assertFalse(new CtLogClient.LogDoc("d", "u", Map.of("pending", any)).isUsableOrReadonly());
        assertFalse(new CtLogClient.LogDoc("d", "u", null).isUsableOrReadonly());
    }

    @Test
    void sthFieldsAreReadFromSnakeCaseNames() {
        CtLogClient.Sth sth = CLIENT.parse(("{\"tree_size\":8956,\"timestamp\":1787871725409,"
                        + "\"sha256_root_hash\":\"hzg=\",\"tree_head_signature\":\"BAMA=\"}")
                        .getBytes(StandardCharsets.UTF_8),
                CtLogClient.Sth.class);
        assertEquals(8956, sth.treeSize());
    }

    @Test
    void entryFieldsAreReadFromSnakeCaseNames() {
        CtLogClient.EntriesDoc doc = CLIENT.parse(TestFixtures.bytes(TestFixtures.ENTRIES), CtLogClient.EntriesDoc.class);
        List<CtLogClient.EntryDoc> entries = doc.entries();
        assertEquals(2, entries.size());
        for (CtLogClient.EntryDoc e : entries) {
            assertFalse(e.leafInput().isEmpty());
        }
        assertNotNull(entries.get(1).extraData(), "precert-запись должна иметь extra_data");
    }

    // --- HTTP ---

    @Test
    void buildsCtEndpointUrlsWithAndWithoutTrailingSlash() throws Exception {
        respond("/log/ct/v1/get-sth", "{\"tree_size\":7,\"timestamp\":1}");

        assertEquals(7, CLIENT.getSth(url("/log/")).treeSize());
        assertEquals(7, CLIENT.getSth(url("/log")).treeSize(),
                "базовый URL без завершающего слэша не должен склеиваться в ...logct/v1");
        assertEquals(List.of("/log/ct/v1/get-sth", "/log/ct/v1/get-sth"), REQUESTS);
    }

    @Test
    void getEntriesPassesTheRequestedRange() throws Exception {
        respond("/log/ct/v1/get-entries", TestFixtures.text(TestFixtures.ENTRIES));

        assertFalse(CLIENT.getEntries(url("/log/"), 10, 11).isEmpty());

        assertEquals(List.of("/log/ct/v1/get-entries?start=10&end=11"), REQUESTS,
                "границы диапазона уходят в query как есть");
    }

    @Test
    void responseWithoutEntriesIsAnEmptyList() throws Exception {
        respond("/log/ct/v1/get-entries", "{}");
        assertTrue(CLIENT.getEntries(url("/log/"), 0, 1).isEmpty());
    }

    @Test
    void nonOkStatusBecomesIoExceptionNamingTheLog() {
        IOException failure = assertThrows(IOException.class, () -> CLIENT.getSth(url("/log/")));
        assertTrue(failure.getMessage().contains("503"), "в тексте ошибки виден код ответа");
        assertTrue(failure.getMessage().contains("get-sth"), "и адрес, на котором она случилась");
    }

    @Test
    void logListIsFlattenedWithOperatorNamesAndFiltered() throws Exception {
        respond("/ctlog.json", """
                {"operators": [
                  {"name": "Yandex", "logs": [
                    {"description": "usable log", "url": "https://a.example/", "state": {"usable": {}}},
                    {"description": "pending log", "url": "https://b.example/", "state": {"pending": {}}}]},
                  {"name": "VK", "logs": [
                    {"description": "readonly log", "url": "https://c.example/", "state": {"readonly": {}}}]},
                  {"name": "Empty operator", "logs": null}]}
                """);

        List<KnownLog> logs = CLIENT.fetchLogList(url("/ctlog.json"));

        assertEquals(List.of(new KnownLog("https://a.example/", "usable log", "Yandex"),
                        new KnownLog("https://c.example/", "readonly log", "VK")), logs,
                "остаются только usable/readonly, каждый — со своим оператором");
    }

    @Test
    void logListWithoutOperatorsIsEmptyRatherThanNull() throws Exception {
        respond("/ctlog.json", "{}");
        assertTrue(CLIENT.fetchLogList(url("/ctlog.json")).isEmpty());
    }

    // --- предел на размер ответа ---

    private static final class RecordingSubscription implements Flow.Subscription {

        private boolean cancelled;

        @Override
        public void request(long n) {
        }

        @Override
        public void cancel() {
            cancelled = true;
        }
    }

    private static CtLogClient.LimitedBody subscribedBody(int limit, RecordingSubscription s) {
        CtLogClient.LimitedBody body = new CtLogClient.LimitedBody(limit, "http://log.example/");
        body.onSubscribe(s);
        return body;
    }

    @Test
    void bodyWithinTheLimitIsAssembledFromItsChunks() throws Exception {
        CtLogClient.LimitedBody body = subscribedBody(16, new RecordingSubscription());

        body.onNext(List.of(ByteBuffer.wrap(new byte[]{1, 2}), ByteBuffer.wrap(new byte[]{3})));
        body.onNext(List.of(ByteBuffer.wrap(new byte[]{4})));
        body.onComplete();

        assertArrayEquals(new byte[]{1, 2, 3, 4}, body.getBody().toCompletableFuture().get());
    }

    /** Ответ сверх предела обрывает чтение — стенд живёт под -Xmx96m. */
    @Test
    void oversizedBodyIsRejectedAndTheSubscriptionCancelled() {
        RecordingSubscription subscription = new RecordingSubscription();
        CtLogClient.LimitedBody body = subscribedBody(4, subscription);

        body.onNext(List.of(ByteBuffer.wrap(new byte[]{1, 2, 3})));
        assertFalse(body.getBody().toCompletableFuture().isDone(), "три байта из четырёх — ещё не предел");
        body.onNext(List.of(ByteBuffer.wrap(new byte[]{4, 5})));

        assertTrue(subscription.cancelled, "чтение обрывается, а не продолжается до конца ответа");
        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> body.getBody().toCompletableFuture().get());
        assertInstanceOf(IOException.class, failure.getCause());
        assertTrue(failure.getCause().getMessage().contains("log.example"), "в ошибке виден лог");
    }

    @Test
    void chunksAfterTheLimitDoNotResurrectTheResponse() {
        CtLogClient.LimitedBody body = subscribedBody(1, new RecordingSubscription());
        body.onNext(List.of(ByteBuffer.wrap(new byte[]{1, 2})));
        body.onNext(List.of(ByteBuffer.wrap(new byte[]{3})));
        body.onComplete();

        assertTrue(body.getBody().toCompletableFuture().isCompletedExceptionally());
    }
}
