package ru.crt.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ru.crt.config.AppProperties;

/**
 * HTTP-клиент CT-логов (RFC 6962): ctlog.json (log_list_v3), get-sth, get-entries.
 * Все методы бросают IOException/InterruptedException — вызывающий решает, как обрабатывать.
 *
 * <p>Если задан {@code ct.trust-cert} (PEM с дополнительными доверенными корнями,
 * например Минцифры), клиент использует trust store «JDK cacerts + этот файл» —
 * сам JDK не модифицируется.
 */
@Component
public class CtLogClient {

    private static final Logger log = LoggerFactory.getLogger(CtLogClient.class);

    private final ObjectMapper objectMapper;
    private final HttpClient http;

    public CtLogClient(ObjectMapper objectMapper, AppProperties props) {
        this.objectMapper = objectMapper;
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL);
        List<Path> trustPems = extraTrustPaths(props.getTrustCert());
        if (!trustPems.isEmpty()) {
            try {
                builder.sslContext(trustedSslContext(trustPems));
                log.info("Дополнительные trust PEM: {}", trustPems);
            } catch (Exception e) {
                log.warn("Не удалось загрузить {} ({}), используем стандартный trust store JDK",
                        trustPems, e.toString());
            }
        }
        this.http = builder.build();
    }

    /** Список существующих файлов из настройки (через запятую); отсутствующие пропускаем с warn. */
    private static List<Path> extraTrustPaths(String setting) {
        List<Path> paths = new ArrayList<>();
        if (setting == null || setting.isBlank()) {
            return paths;
        }
        for (String raw : setting.split(",")) {
            String trimmed = raw.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            Path path = Path.of(trimmed);
            if (Files.isReadable(path)) {
                paths.add(path);
            } else {
                log.warn("trust-файл {} не найден/не читается — пропускаем", trimmed);
            }
        }
        return paths;
    }

    // --- DTO ответов логов ---

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record LogListDoc(List<OperatorDoc> operators) {
    }

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record OperatorDoc(String name, List<LogDoc> logs) {
    }

    /** Из state берём только ключи: usable/readonly — лог participate в выдаче. */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record LogDoc(String description, String url, Map<String, JsonNode> state) {
        public boolean isUsableOrReadonly() {
            return state != null && (state.containsKey("usable") || state.containsKey("readonly"));
        }
    }

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record Sth(long tree_size, long timestamp) {
    }

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record EntriesDoc(List<EntryDoc> entries) {
    }

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record EntryDoc(String leaf_input, String extra_data) {
    }

    // --- публичные методы ---

    /** Список логов из ctlog.json (state usable/readonly), сплющенный с именами операторов. */
    public List<KnownLog> fetchLogList(String logListUrl) throws IOException, InterruptedException {
        LogListDoc doc = parseLogListDoc(get(logListUrl));
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
        return parseSth(get(ctUrl(baseUrl, "ct/v1/get-sth")));
    }

    public List<EntryDoc> getEntries(String baseUrl, long start, long end)
            throws IOException, InterruptedException {
        EntriesDoc doc = parseEntries(
                get(ctUrl(baseUrl, "ct/v1/get-entries?start=" + start + "&end=" + end)));
        return doc.entries() == null ? List.of() : doc.entries();
    }

    // --- разбор JSON (package-private для unit-тестов) ---

    LogListDoc parseLogListDoc(String json) throws IOException {
        return objectMapper.readValue(json, LogListDoc.class);
    }

    Sth parseSth(String json) throws IOException {
        return objectMapper.readValue(json, Sth.class);
    }

    EntriesDoc parseEntries(String json) throws IOException {
        return objectMapper.readValue(json, EntriesDoc.class);
    }

    // --- низкоуровневый HTTP ---

    /**
     * SSLContext с якорями «стандартный cacerts JDK + все сертификаты из PEM-файлов»
     * (нужно и корню, и промежуточному: серверы Минцифры не присылают цепочку целиком).
     * Пакет-private для unit-теста.
     */
    static SSLContext trustedSslContext(List<Path> pems) throws IOException, GeneralSecurityException {
        KeyStore anchors = KeyStore.getInstance(KeyStore.getDefaultType());
        Path cacerts = Path.of(System.getProperty("java.home"), "lib", "security", "cacerts");
        char[] password = System.getProperty("javax.net.ssl.trustStorePassword", "changeit")
                .toCharArray();
        try (InputStream in = Files.newInputStream(cacerts)) {
            anchors.load(in, password);
        }
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        int loaded = 0;
        for (Path pem : pems) {
            Collection<? extends Certificate> extras;
            try (InputStream in = Files.newInputStream(pem)) {
                extras = factory.generateCertificates(in);
            } catch (GeneralSecurityException e) {
                throw new IOException("не удалось разобрать сертификаты в " + pem + ": " + e.getMessage(), e);
            }
            for (Certificate c : extras) {
                anchors.setCertificateEntry("ct-extra-" + loaded++, c);
            }
        }
        if (loaded == 0) {
            throw new IOException("в " + pems + " не найдено ни одного сертификата");
        }
        TrustManagerFactory tmf = TrustManagerFactory
                .getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(anchors);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, tmf.getTrustManagers(), new SecureRandom());
        return context;
    }

    private static String ctUrl(String baseUrl, String path) {
        return baseUrl.endsWith("/") ? baseUrl + path : baseUrl + "/" + path;
    }

    private String get(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode() + " from " + url);
        }
        return response.body();
    }
}
