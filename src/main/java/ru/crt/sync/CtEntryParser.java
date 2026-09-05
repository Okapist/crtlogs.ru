package ru.crt.sync;

import java.io.IOException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Base64;
import java.util.Set;
import ru.crt.x509.CertificateNames;
import ru.crt.x509.X509;

/**
 * Разбор записи CT-лога (MerkleTreeLeaf + extra_data) в X509Certificate без BouncyCastle.
 * leaf_input: version(1) + leaf_type(1) + timestamp(8) + entry_type(2) + payload;
 * entry_type 0 (x509_entry) — DER сертификата, 1 (precert_entry) — DER из extra_data.
 */
public final class CtEntryParser {

    private CtEntryParser() {
    }

    /** Результат разбора одной записи лога. */
    public record ParsedEntry(
            X509Certificate certificate,
            boolean precert,
            long entryTimestampMs,
            Set<String> domains) {
    }

    public static ParsedEntry parse(String leafInputB64, String extraDataB64) throws IOException {
        byte[] leaf = decodeB64(leafInputB64, "leaf_input");
        if (leaf.length < 12) {
            throw new IOException("leaf_input слишком короткий: " + leaf.length + " байт");
        }
        int version = leaf[0] & 0xFF;
        int leafType = leaf[1] & 0xFF;
        if (version != 0) {
            throw new IOException("неподдерживаемая версия MerkleTreeLeaf: " + version);
        }
        if (leafType != 0) { // 0 = timestamped_entry
            throw new IOException("неподдерживаемый leaf_type: " + leafType);
        }
        long timestamp = 0;
        for (int i = 2; i <= 9; i++) {
            timestamp = (timestamp << 8) | (leaf[i] & 0xFF);
        }
        int entryType = ((leaf[10] & 0xFF) << 8) | (leaf[11] & 0xFF);

        byte[] der;
        boolean precert;
        switch (entryType) {
            case 0 -> {
                der = readOpaque(leaf, 12);
                precert = false;
            }
            case 1 -> {
                if (extraDataB64 == null || extraDataB64.isEmpty()) {
                    throw new IOException("precert-запись без extra_data");
                }
                der = readOpaque(decodeB64(extraDataB64, "extra_data"), 0);
                precert = true;
            }
            default -> throw new IOException("неподдерживаемый entry_type: " + entryType);
        }

        X509Certificate cert;
        try {
            cert = X509.parse(der);
        } catch (CertificateException e) {
            throw new IOException("не удалось разобрать DER-сертификат: " + e.getMessage(), e);
        }
        return new ParsedEntry(cert, precert, timestamp, CertificateNames.domainsOf(cert));
    }

    /** 3-байтовый length-prefix + полезная нагрузка (TLS-стиль opaque). */
    private static byte[] readOpaque(byte[] buf, int off) throws IOException {
        if (off + 3 > buf.length) {
            throw new IOException("повреждённый length-prefix на смещении " + off);
        }
        int len = ((buf[off] & 0xFF) << 16) | ((buf[off + 1] & 0xFF) << 8) | (buf[off + 2] & 0xFF);
        if (off + 3 + len > buf.length) {
            throw new IOException("длина " + len + " выходит за границы буфера");
        }
        return Arrays.copyOfRange(buf, off + 3, off + 3 + len);
    }

    private static byte[] decodeB64(String value, String field) throws IOException {
        try {
            return Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException e) {
            throw new IOException("повреждённый base64 в " + field + ": " + e.getMessage(), e);
        }
    }
}
