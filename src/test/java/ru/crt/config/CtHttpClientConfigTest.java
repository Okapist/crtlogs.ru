package ru.crt.config;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import ru.crt.TestFixtures;
import ru.crt.x509.X509;

/**
 * Trust store клиента CT-логов: серверы Минцифры не присылают цепочку целиком,
 * поэтому к cacerts подмешиваются корень и промежуточный сертификат их УЦ.
 */
class CtHttpClientConfigTest {

    private static final DefaultResourceLoader RESOURCES = new DefaultResourceLoader();

    private static HttpClient client(String trustCert) {
        AppProperties props = new AppProperties();
        props.setTrustCert(trustCert);
        return new CtHttpClientConfig().ctHttpClient(props, RESOURCES);
    }

    private static Path pemFile(String content) throws IOException {
        Path file = Files.createTempFile("ct-trust", ".pem");
        file.toFile().deleteOnExit();
        Files.writeString(file, content);
        return file;
    }

    @Test
    void trustedSslContextLoadsExtraPem() throws Exception {
        // PEM пишем боевым кодировщиком — свой дал бы другую ширину строки
        Path pem = pemFile(X509.toPem(TestFixtures.der(0)));

        SSLContext context = CtHttpClientConfig.trustedSslContext(List.of(new FileSystemResource(pem)));
        assertNotNull(context);
        assertNotNull(context.getSocketFactory());
    }

    /** Сертификаты Минцифры едут внутри jar: на сервере их ставить не нужно. */
    @Test
    void bundledMinistryCertificatesLoadFromClasspath() throws Exception {
        List<Resource> bundled = List.of(
                RESOURCES.getResource("classpath:trust/russian_trusted_root_ca.crt"),
                RESOURCES.getResource("classpath:trust/russian_trusted_sub_ca_2024.crt"));
        for (Resource r : bundled) {
            assertTrue(r.isReadable(), r + " должен быть в classpath");
        }
        assertNotNull(CtHttpClientConfig.trustedSslContext(bundled));
    }

    @Test
    void trustedSslContextRejectsFileWithoutCertificates() {
        assertThrows(IOException.class, () -> CtHttpClientConfig.trustedSslContext(
                List.of(new FileSystemResource(pemFile("not a certificate\n")))));
    }

    /** Настроенные корни доезжают до клиента: у него собственный контекст, а не общий. */
    @Test
    void configuredTrustCertificatesReachTheClient() throws Exception {
        HttpClient configured = client("classpath:trust/russian_trusted_root_ca.crt");
        assertNotSame(SSLContext.getDefault(), configured.sslContext());
    }

    /** Битая настройка не роняет старт — клиент поднимается на стандартном cacerts. */
    @Test
    void unusableTrustSettingLeavesTheClientOnTheDefaultTrustStore() throws Exception {
        Path broken = pemFile("not a certificate\n");

        assertSame(SSLContext.getDefault(),
                client("/definitely/absent.pem, " + broken.toAbsolutePath() + ",  ").sslContext(),
                "битый файл — работаем на cacerts");
        assertSame(SSLContext.getDefault(), client("").sslContext(), "настройка пуста — тоже");
    }
}
