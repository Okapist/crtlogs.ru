package ru.crt.client;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.stereotype.Component;

/**
 * HTTP-клиент CT-логов (RFC 6962): ctlog.json, get-sth, get-entries.
 * Только протокол; HttpClient с trust store собирает CtHttpClientConfig.
 */
@Component
public class CtLogClient {

    private final ObjectMapper objectMapper;
    private final HttpClient http;

    public CtLogClient(ObjectMapper objectMapper, HttpClient ctHttpClient) {
        this.objectMapper = objectMapper;
        this.http = ctHttpClient;
    }

    // --- DTO ответов логов; snake_case протокола связывает @JsonNaming на record'ах ---

    public record LogListDoc(List<OperatorDoc> operators) {
    }

    public record OperatorDoc(String name, List<LogDoc> logs) {
    }

    /** Из state берём только ключи: usable/readonly — лог participate в выдаче. */
    public record LogDoc(String description, String url, Map<String, JsonNode> state) {
        public boolean isUsableOrReadonly() {
            return state != null && (state.containsKey("usable") || state.containsKey("readonly"));
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Sth(long treeSize, long timestamp) {
    }

    public record EntriesDoc(List<EntryDoc> entries) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record EntryDoc(String leafInput, String extraData) {
    }

    // --- публичные методы ---

    /** Список логов из ctlog.json (state usable/readonly), сплющенный с именами операторов. */
    public List<KnownLog> fetchLogList(String logListUrl) throws IOException, InterruptedException {
        LogListDoc doc = parse(get(logListUrl), LogListDoc.class);
        List<KnownLog> result = new ArrayList<>();
        if (doc.operators() != null) {
            for (OperatorDoc op : doc.operators()) {
                if (op.logs() == null) {
                    continue;
                }
                for (LogDoc log : op.logs()) {
                    if (log.isUsableOrReadonly()) {
                        result.add(new KnownLog(log.url(), log.description(), op.name()));
                    }
                }
            }
        }
        return result;
    }

    public Sth getSth(String baseUrl) throws IOException, InterruptedException {
        return parse(get(ctUrl(baseUrl, "ct/v1/get-sth")), Sth.class);
    }

    public List<EntryDoc> getEntries(String baseUrl, long start, long end)
            throws IOException, InterruptedException {
        EntriesDoc doc = parse(
                get(ctUrl(baseUrl, "ct/v1/get-entries?start=" + start + "&end=" + end)), EntriesDoc.class);
        return doc.entries() == null ? List.of() : doc.entries();
    }

    // --- разбор JSON (package-private для unit-тестов) ---

    <T> T parse(byte[] json, Class<T> type) {
        return objectMapper.readValue(json, type);
    }

    // --- низкоуровневый HTTP ---

    /** Тело приходит от третьей стороны; пачка на 256 записей — единицы мегабайт. */
    private static final int MAX_RESPONSE_BYTES = 16 * 1024 * 1024;

    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(30);

    /** Предел на весь обмен: HttpRequest.timeout закрывает только ожидание заголовков. */
    private static final Duration EXCHANGE_TIMEOUT = Duration.ofSeconds(90);

    private static String ctUrl(String baseUrl, String path) {
        return baseUrl.endsWith("/") ? baseUrl + path : baseUrl + "/" + path;
    }

    private byte[] get(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(RESPONSE_TIMEOUT)
                .header("Accept", "application/json")
                .GET()
                .build();
        CompletableFuture<HttpResponse<byte[]>> exchange =
                http.sendAsync(request, _ -> new LimitedBody(MAX_RESPONSE_BYTES, url));
        HttpResponse<byte[]> response;
        try {
            response = exchange.get(EXCHANGE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            exchange.cancel(true);
            throw new IOException("лог молчит дольше " + EXCHANGE_TIMEOUT.toSeconds() + " с: " + url, e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            throw cause instanceof IOException failure ? failure : new IOException(cause);
        }
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode() + " from " + url);
        }
        return response.body();
    }

    /** Тело не длиннее предела: при превышении подписка отменяется и обмен падает ошибкой. */
    static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {

        private final HttpResponse.BodySubscriber<byte[]> body = HttpResponse.BodySubscribers.ofByteArray();
        private final int limit;
        private final String url;
        private Flow.Subscription subscription;
        private long size;
        private boolean overflowed;

        LimitedBody(int limit, String url) {
            this.limit = limit;
            this.url = url;
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return body.getBody();
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            body.onSubscribe(subscription);
        }

        @Override
        public void onNext(List<ByteBuffer> items) {
            if (overflowed) {
                return; // после отмены куски ещё могут приходить — ответ они не досоздают
            }
            for (ByteBuffer item : items) {
                size += item.remaining();
            }
            if (size > limit) {
                overflowed = true;
                subscription.cancel();
                body.onError(new IOException("ответ длиннее " + limit + " байт: " + url));
                return;
            }
            body.onNext(items);
        }

        @Override
        public void onError(Throwable failure) {
            if (!overflowed) {
                body.onError(failure);
            }
        }

        @Override
        public void onComplete() {
            if (!overflowed) {
                body.onComplete();
            }
        }
    }
}
