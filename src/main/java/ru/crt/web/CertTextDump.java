package ru.crt.web;

import java.io.ByteArrayInputStream;
import java.security.PublicKey;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.security.auth.x500.X500Principal;

/**
 * Текстовый дамп сертификата в духе {@code openssl x509 -text} — как на странице
 * сертификата crt.sh. Не стремится к побайтовой точности openssl, но повторяет
 * структуру: Data / Version / Serial / Issuer / Validity / Subject / Public Key /
 * X509v3 extensions / Signature Algorithm.
 */
public final class CertTextDump {

    private CertTextDump() {
    }

    /** Известные имена расширений, как их называет openssl. */
    private static final Map<String, String> EXTENSIONS = Map.ofEntries(
            Map.entry("2.5.29.17", "X509v3 Subject Alternative Name"),
            Map.entry("2.5.29.19", "X509v3 Basic Constraints"),
            Map.entry("2.5.29.15", "X509v3 Key Usage"),
            Map.entry("2.5.29.37", "X509v3 Extended Key Usage"),
            Map.entry("2.5.29.14", "X509v3 Subject Key Identifier"),
            Map.entry("2.5.29.35", "X509v3 Authority Key Identifier"),
            Map.entry("2.5.29.31", "X509v3 CRL Distribution Points"),
            Map.entry("2.5.29.32", "X509v3 Certificate Policies"),
            Map.entry("1.3.6.1.5.5.7.1.1", "Authority Information Access"),
            Map.entry("1.3.6.1.4.1.11129.2.4.2", "CT Precertificate SCTs"),
            Map.entry("1.3.6.1.5.5.7.48.1.5", "OCSP No Check"));

    private static final Map<String, String> DN_NAMES = Map.ofEntries(
            Map.entry("CN", "commonName"),
            Map.entry("O", "organizationName"),
            Map.entry("OU", "organizationalUnitName"),
            Map.entry("C", "countryName"),
            Map.entry("ST", "stateOrProvinceName"),
            Map.entry("L", "localityName"),
            Map.entry("STREET", "streetAddress"),
            Map.entry("DC", "domainComponent"),
            Map.entry("SN", "surname"),
            Map.entry("GN", "givenName"),
            Map.entry("1.2.840.113549.1.9.1", "emailAddress"),
            Map.entry("1.2.643.3.131.1.1", "INN"),
            Map.entry("1.2.643.100.1", "OGRN"),
            Map.entry("1.2.643.100.3", "SNILS"));

    private static final DateTimeFormatter OPENSSL_DATE =
            DateTimeFormatter.ofPattern("MMM", Locale.ENGLISH);

    public static String render(byte[] der) {
        X509Certificate c;
        try {
            c = (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(der));
        } catch (CertificateException e) {
            return null;
        }
        StringBuilder s = new StringBuilder();
        s.append("Certificate:\n    Data:\n");
        s.append("        Version: ").append(c.getVersion())
                .append(" (0x").append(Integer.toHexString(c.getVersion() - 1)).append(")\n");
        s.append("        Serial Number:\n            ")
                .append(colonHex(c.getSerialNumber().toByteArray(), true)).append('\n');
        s.append("        Signature Algorithm: ").append(sigAlg(c.getSigAlgName())).append('\n');
        appendDn(s, "Issuer", c.getIssuerX500Principal());
        s.append("        Validity\n");
        s.append("            Not Before: ").append(opensslDate(c.getNotBefore().toInstant())).append('\n');
        s.append("            Not After : ").append(opensslDate(c.getNotAfter().toInstant())).append('\n');
        appendDn(s, "Subject", c.getSubjectX500Principal());
        s.append("        Subject Public Key Info:\n");
        s.append("            Public Key Algorithm: ").append(keyAlg(c.getPublicKey())).append('\n');
        s.append("            ").append(keyBits(c.getPublicKey())).append(" bit\n");
        appendExtensions(s, c);
        s.append("    Signature Algorithm: ").append(sigAlg(c.getSigAlgName())).append('\n');
        appendWrapped(s, colonHex(c.getSignature(), false));
        return s.toString();
    }

    private static void appendDn(StringBuilder s, String label, X500Principal dn) {
        s.append("        ").append(label).append(":\n");
        for (Map.Entry<String, String> e : parseRdns(dn.getName()).entrySet()) {
            String name = DN_NAMES.getOrDefault(e.getKey(), e.getKey());
            s.append("                ").append(String.format("%-18s", name))
                    .append("= ").append(e.getValue()).append('\n');
        }
    }

    /** RFC 2253 DN -> упорядоченные пары (тип, значение); один тип может встретиться дважды. */
    private static LinkedHashMap<String, String> parseRdns(String rfc2253) {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        for (String rdn : rfc2253.split(",")) {
            int eq = rdn.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            out.put(rdn.substring(0, eq).trim().toUpperCase(Locale.ROOT),
                    rdn.substring(eq + 1).trim());
        }
        return out;
    }

    private static void appendExtensions(StringBuilder s, X509Certificate c) {
        Set<String> oids = c.getCriticalExtensionOIDs();
        if (oids == null) {
            return;
        }
        LinkedHashMap<String, Boolean> all = new LinkedHashMap<>();
        oids.forEach(oid -> all.put(oid, true));
        if (c.getNonCriticalExtensionOIDs() != null) {
            c.getNonCriticalExtensionOIDs().forEach(oid -> all.putIfAbsent(oid, false));
        }
        if (all.isEmpty()) {
            return;
        }
        s.append("        X509v3 extensions:\n");
        for (Map.Entry<String, Boolean> e : all.entrySet()) {
            String oid = e.getKey();
            String name = EXTENSIONS.getOrDefault(oid, oid);
            s.append("            ").append(name).append(e.getValue() ? ": critical\n" : ": \n");
            byte[] value = unwrapOctetString(c.getExtensionValue(oid));
            if (value == null) {
                continue;
            }
            if ("2.5.29.17".equals(oid)) {
                appendSan(s, value);
            } else {
                appendHexIndented(s, value, 16);
            }
        }
    }

    /** GeneralNames: печатаем знакомые типы (DNS/URI/IP/email), остальное — hex. */
    private static void appendSan(StringBuilder s, byte[] generalNames) {
        int i = 0;
        if (i == generalNames.length || generalNames[i] != 0x30) {
            appendHexIndented(s, generalNames, 16);
            return;
        }
        i++;
        i += derLengthBytes(generalNames, i); // пропускаем длину SEQUENCE
        while (i < generalNames.length) {
            int tag = generalNames[i] & 0xFF;
            int lenBytes = derLengthBytes(generalNames, i + 1);
            int len = derLength(generalNames, i + 1);
            int start = i + 1 + lenBytes;
            byte[] value = new byte[len];
            System.arraycopy(generalNames, start, value, 0, Math.min(len, generalNames.length - start));
            switch (tag) {
                case 0x81 -> s.append("                email:").append(new String(value)).append('\n');
                case 0x82 -> s.append("                DNS:").append(new String(value)).append('\n');
                case 0x86 -> s.append("                URI:").append(new String(value)).append('\n');
                case 0x87 -> s.append("                IP:").append(ipString(value)).append('\n');
                default -> {
                    s.append("                othername:");
                    appendHexIndented(s, value, 16);
                }
            }
            i = start + len;
        }
    }

    /** getExtensionValue возвращает DER OCTET STRING — снимаем внешнюю обёртку. */
    private static byte[] unwrapOctetString(byte[] der) {
        if (der == null || der.length < 2 || der[0] != 0x04) {
            return der;
        }
        int lenBytes = derLengthBytes(der, 1);
        int len = derLength(der, 1);
        byte[] out = new byte[len];
        System.arraycopy(der, 1 + lenBytes, out, 0, Math.min(len, der.length - 1 - lenBytes));
        return out;
    }

    private static int derLength(byte[] der, int off) {
        int first = der[off] & 0xFF;
        if ((first & 0x80) == 0) {
            return first;
        }
        int count = first & 0x7F;
        int len = 0;
        for (int i = 0; i < count; i++) {
            len = (len << 8) | (der[off + 1 + i] & 0xFF);
        }
        return len;
    }

    private static int derLengthBytes(byte[] der, int off) {
        int first = der[off] & 0xFF;
        return (first & 0x80) == 0 ? 1 : 1 + (first & 0x7F);
    }

    private static String ipString(byte[] b) {
        if (b.length == 4 || b.length == 16) {
            try {
                return java.net.InetAddress.getByAddress(b).getHostAddress();
            } catch (java.net.UnknownHostException ignored) {
                // fallback ниже
            }
        }
        return colonHex(b, false);
    }

    private static void appendHexIndented(StringBuilder s, byte[] value, int perLine) {
        for (int i = 0; i < value.length; i += perLine) {
            s.append("                ");
            int min = Math.min(i + perLine, value.length);
            for (int j = i; j < min; j++) {
                s.append(String.format("%02x", value[j] & 0xFF));
                if (j < min - 1) {
                    s.append(':');
                }
            }
            s.append('\n');
        }
    }

    private static void appendWrapped(StringBuilder s, String colonHex) {
        String[] bytes = colonHex.split(":");
        for (int i = 0; i < bytes.length; i += 18) {
            s.append("             ");
            for (int j = i; j < Math.min(i + 18, bytes.length); j++) {
                s.append(bytes[j]);
                if (j < Math.min(i + 18, bytes.length) - 1) {
                    s.append(':');
                }
            }
            s.append('\n');
        }
    }

    private static String colonHex(byte[] bytes, boolean upper) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < bytes.length; i++) {
            if (i > 0) {
                s.append(':');
            }
            s.append(String.format(upper ? "%02X" : "%02x", bytes[i] & 0xFF));
        }
        return s.toString();
    }

    private static String sigAlg(String javaName) {
        return switch (javaName) {
            case "SHA256withRSA" -> "sha256WithRSAEncryption";
            case "SHA384withRSA" -> "sha384WithRSAEncryption";
            case "SHA512withRSA" -> "sha512WithRSAEncryption";
            case "SHA1withRSA" -> "sha1WithRSAEncryption";
            case "SHA256withECDSA" -> "ecdsa-with-SHA256";
            case "SHA384withECDSA" -> "ecdsa-with-SHA384";
            case "SHA512withECDSA" -> "ecdsa-with-SHA512";
            case "SHA1withECDSA" -> "ecdsa-with-SHA1";
            default -> javaName;
        };
    }

    private static String keyAlg(PublicKey key) {
        return switch (key.getAlgorithm().toUpperCase(Locale.ROOT)) {
            case "RSA" -> "rsaEncryption";
            case "EC" -> "id-ecPublicKey";
            case "ED25519" -> "ED25519";
            default -> key.getAlgorithm();
        };
    }

    private static int keyBits(PublicKey key) {
        if (key instanceof RSAPublicKey rsa) {
            return rsa.getModulus().bitLength();
        }
        if (key instanceof ECPublicKey ec) {
            return ec.getParams().getCurve().getField().getFieldSize();
        }
        if (key.getAlgorithm().equalsIgnoreCase("Ed25519")) {
            return 256;
        }
        return 0;
    }

    /** "Aug  3 08:05:53 2026 GMT" — как openssl (день дополняется пробелом). */
    private static String opensslDate(Instant instant) {
        var utc = instant.atZone(ZoneOffset.UTC);
        return String.format("%s %2d %02d:%02d:%02d %d GMT",
                OPENSSL_DATE.format(utc), utc.getDayOfMonth(),
                utc.getHour(), utc.getMinute(), utc.getSecond(), utc.getYear());
    }

    /** PEM полезен в дампе последней строкой, как делают некоторые вьюверы. */
    static String pemOf(byte[] der) {
        return "-----BEGIN CERTIFICATE-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der)
                + "\n-----END CERTIFICATE-----\n";
    }

    public static String sha1Hex(byte[] der) {
        try {
            var md = java.security.MessageDigest.getInstance("SHA-1");
            return HexUpper.of(md.digest(der));
        } catch (java.security.NoSuchAlgorithmException e) {
            return null;
        }
    }

    private static final class HexUpper {
        static String of(byte[] bytes) {
            StringBuilder s = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                s.append(String.format("%02X", b & 0xFF));
            }
            return s.toString();
        }
    }
}
