package ru.crt.x509;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Разбор, отпечатки и PEM сертификата — то, что нужно и синку, и странице, и тестам.
 * Одним местом, потому что раньше разбор DER жил в трёх копиях (парсер записей лога,
 * дамп, фикстуры тестов), а отпечатки — в двух пакетах с разной политикой на
 * недоступный алгоритм.
 */
public final class X509 {

    private static final Base64.Encoder PEM_BODY =
            Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII));

    private X509() {
    }

    /** Сертификат из DER или PEM: {@link CertificateFactory} понимает обе формы. */
    public static X509Certificate parse(byte[] encoded) throws CertificateException {
        return (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(encoded));
    }

    /** SHA-256 от DER, hex в нижнем регистре — так он лежит в certificate.sha256 и уходит в JSON. */
    public static String sha256Hex(byte[] der) {
        return HexFormat.of().formatHex(digest("SHA-256", der));
    }

    /** SHA-1 от DER, hex в верхнем регистре — как на странице сертификата crt.sh. */
    public static String sha1Hex(byte[] der) {
        return HexFormat.of().withUpperCase().formatHex(digest("SHA-1", der));
    }

    /** DER -> PEM: та же форма, что отдаёт {@code openssl x509}, строки по 64 символа. */
    public static String toPem(byte[] der) {
        return "-----BEGIN CERTIFICATE-----\n" + PEM_BODY.encodeToString(der)
                + "\n-----END CERTIFICATE-----\n";
    }

    private static byte[] digest(String algorithm, byte[] data) {
        try {
            return MessageDigest.getInstance(algorithm).digest(data);
        } catch (NoSuchAlgorithmException e) {
            // SHA-1 и SHA-256 обязаны быть в любом JDK: это сбой окружения, а не данных
            throw new IllegalStateException(algorithm + " недоступен", e);
        }
    }
}
