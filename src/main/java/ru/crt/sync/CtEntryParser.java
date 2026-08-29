package ru.crt.sync;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.naming.InvalidNameException;
import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;
import javax.security.auth.x500.X500Principal;

/**
 * Разбор записи CT-лога (MerkleTreeLeaf + extra_data) в X509Certificate без BouncyCastle.
 *
 * <p>leaf_input = MerkleTreeLeaf: version(1) + leaf_type(1) + TimestampedEntry,
 * где TimestampedEntry = timestamp(8) + entry_type(2) + payload.
 *
 * <ul>
 *   <li>entry_type = 0 (x509_entry): payload = opaque&lt;X509Certificate&gt; (3-байтовая длина + DER);</li>
 *   <li>entry_type = 1 (precert_entry): в leaf лежит только TBS, полный precert берём из
 *       extra_data = PrecertChainEntry: pre_certificate (opaque, 3-байтовая длина + DER) + chain.</li>
 * </ul>
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
            cert = (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(der));
        } catch (CertificateException e) {
            throw new IOException("не удалось разобрать DER-сертификат: " + e.getMessage(), e);
        }
        return new ParsedEntry(cert, precert, timestamp, domainsOf(cert));
    }

    /** 3-байтовый length-prefix + полезная нагрузка (TLS-стиль opaque). */
    private static byte[] readOpaque(byte[] buf, int off) throws IOException {        if (off + 3 > buf.length) {
            throw new IOException("повреждённый length-prefix на смещении " + off);
        }
        int len = ((buf[off] & 0xFF) << 16) | ((buf[off + 1] & 0xFF) << 8) | (buf[off + 2] & 0xFF);
        if (off + 3 + len > buf.length) {
            throw new IOException("длина " + len + " выходит за границы буфера");
        }
        byte[] out = new byte[len];
        System.arraycopy(buf, off + 3, out, 0, len);
        return out;
    }

    private static byte[] decodeB64(String value, String field) throws IOException {
        try {
            return Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException e) {
            throw new IOException("повреждённый base64 в " + field + ": " + e.getMessage(), e);
        }
    }

    /** CN из subject DN + все dNSName из SAN, в lower case, без повторов. */
    public static Set<String> domainsOf(X509Certificate cert) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        try {
            Collection<List<?>> san = cert.getSubjectAlternativeNames();
            if (san != null) {
                for (List<?> generalName : san) {
                    if (generalName.size() >= 2
                            && Integer.valueOf(2).equals(generalName.get(0)) // 2 = dNSName
                            && generalName.get(1) instanceof String dns
                            && !dns.isBlank()) {
                        out.add(dns.toLowerCase(Locale.ROOT));
                    }
                }
            }
        } catch (java.security.cert.CertificateParsingException e) {
            // непарсящийся SAN — оставляем только CN
        }
        String cn = cnOf(cert.getSubjectX500Principal());
        if (cn != null && !cn.isBlank()) {
            out.add(cn.toLowerCase(Locale.ROOT));
        }
        return out;
    }

    /** CN из DN в формате RFC 2253 (как его отдаёт X500Principal.getName()). */
    public static String cnOf(X500Principal principal) {
        if (principal == null) {
            return null;
        }
        try {
            for (Rdn rdn : new LdapName(principal.getName()).getRdns()) {
                if ("CN".equalsIgnoreCase(rdn.getType())) {
                    return rdn.getValue().toString();
                }
            }
        } catch (InvalidNameException e) {
            return null; // непарсящийся DN — просто нет CN
        }
        return null;
    }
}
