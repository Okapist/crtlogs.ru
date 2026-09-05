package ru.crt.config;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
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
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.util.StringUtils;

/**
 * HTTP-клиент CT-логов и его trust store: при {@code ct.trust-cert} — «JDK cacerts
 * + PEM-файлы»; непригодная настройка не роняет старт, клиент поднимается на cacerts.
 */
@Configuration
public class CtHttpClientConfig {

    private static final Logger log = LoggerFactory.getLogger(CtHttpClientConfig.class);

    @Bean
    public HttpClient ctHttpClient(AppProperties props, ResourceLoader resources) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL);
        List<Resource> trustPems = extraTrustResources(props.getTrustCert(), resources);
        if (!trustPems.isEmpty()) {
            try {
                builder.sslContext(trustedSslContext(trustPems));
                log.info("Дополнительные доверенные сертификаты: {}", trustPems);
            } catch (Exception e) {
                log.warn("Не удалось загрузить {} ({}), используем стандартный trust store JDK",
                        trustPems, e.toString());
            }
        }
        return builder.build();
    }

    /** Существующие ресурсы из настройки (через запятую); classpath: и пути на диске. */
    static List<Resource> extraTrustResources(String setting, ResourceLoader resources) {
        List<Resource> found = new ArrayList<>();
        for (String location : StringUtils.tokenizeToStringArray(setting, ",")) {
            // без схемы DefaultResourceLoader ищет в classpath — а настройка задаёт путь на диске
            if (!location.contains(":")) {
                location = "file:" + location;
            }
            Resource resource = resources.getResource(location);
            if (resource.isReadable()) {
                found.add(resource);
            } else {
                log.warn("trust-сертификат {} не найден/не читается — пропускаем", location);
            }
        }
        return found;
    }

    /** SSLContext «cacerts + все сертификаты из PEM»; пакет-private для unit-теста. */
    static SSLContext trustedSslContext(List<Resource> pems) throws IOException, GeneralSecurityException {
        KeyStore anchors = KeyStore.getInstance(KeyStore.getDefaultType());
        Path cacerts = Path.of(System.getProperty("java.home"), "lib", "security", "cacerts");
        char[] password = System.getProperty("javax.net.ssl.trustStorePassword", "changeit")
                .toCharArray();
        try (InputStream in = Files.newInputStream(cacerts)) {
            anchors.load(in, password);
        }
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        int loaded = 0;
        for (Resource pem : pems) {
            Collection<? extends Certificate> extras;
            try (InputStream in = pem.getInputStream()) {
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
}
