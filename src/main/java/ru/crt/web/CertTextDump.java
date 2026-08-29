package ru.crt.web;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.security.auth.x500.X500Principal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Текстовый дамп сертификата в духе {@code openssl x509 -text} — как на странице
 * сертификата crt.sh. Не стремится к побайтовой точности openssl, но повторяет
 * структуру: Data / Version / Serial / Issuer / Validity / Subject / Public Key /
 * X509v3 extensions / Signature Algorithm.
 */
public final class CertTextDump {

    private static final Logger log = LoggerFactory.getLogger(CertTextDump.class);

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
        try {
            return dump(c);
        } catch (RuntimeException e) {
            // содержимое сертификата приходит из публичного CT-лога, то есть от кого угодно:
            // страница не должна отдавать 500 из-за одной кривой записи — вызывающий покажет PEM
            log.warn("Не удалось построить дамп сертификата: {}", e.toString());
            return null;
        }
    }

    private static String dump(X509Certificate c) {
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
        for (Rdn rdn : parseRdns(dn.getName())) {
            String name = DN_NAMES.getOrDefault(rdn.type(), rdn.type());
            // 24 — по самому длинному имени из DN_NAMES (organizationalUnitName), иначе
            // у длинных имён пропадал пробел перед '='
            s.append("                ").append(String.format("%-24s", name))
                    .append("= ").append(rdn.value()).append('\n');
        }
    }

    /** Одна пара «тип RDN — значение»; список, а не Map: один тип встречается дважды (OU=Ops,OU=IT). */
    record Rdn(String type, String value) {
    }

    /**
     * RFC 2253 DN -> упорядоченные пары (тип, значение). Разделители RDN (',' и '+')
     * ищутся только неэкранированные: в {@code CN=Foo\, Inc.,O=Bar} запятая внутри CN
     * экранирована обратным слэшем, и наивный split по ',' разорвал бы значение.
     * Пакет-private для unit-теста.
     */
    static List<Rdn> parseRdns(String rfc2253) {
        List<Rdn> out = new ArrayList<>();
        for (String rdn : splitUnescaped(rfc2253)) {
            int eq = indexOfUnescaped(rdn, '=');
            if (eq <= 0) {
                continue;
            }
            String raw = rdn.substring(eq + 1).trim();
            out.add(new Rdn(rdn.substring(0, eq).trim().toUpperCase(Locale.ROOT),
                    raw.startsWith("#") ? decodeHexValue(raw) : unescape(raw)));
        }
        return out;
    }

    /**
     * Значение в форме {@code #hex} (RFC 2253) — это DER-кодирование атрибута; для
     * строковых типов печатаем текст, как openssl, иначе оставляем hex как есть.
     */
    private static String decodeHexValue(String value) {
        byte[] der;
        try {
            der = HexFormat.of().parseHex(value.substring(1));
        } catch (IllegalArgumentException e) {
            return value;
        }
        int lenBytes = derLengthBytes(der, 1);
        int len = derLength(der, 1);
        int start = lenBytes < 0 ? -1 : 1 + lenBytes;
        if (len < 0 || start < 0 || start > der.length || len > der.length - start) {
            return value;
        }
        return switch (der[0] & 0xFF) {
            case 0x0C, 0x13, 0x16 -> // UTF8String, PrintableString, IA5String
                    new String(der, start, len, StandardCharsets.UTF_8);
            case 0x14 -> new String(der, start, len, StandardCharsets.ISO_8859_1); // T61String
            case 0x1E -> new String(der, start, len, StandardCharsets.UTF_16BE);   // BMPString
            default -> value;
        };
    }

    /** Делит DN по неэкранированным ',' и '+'; само экранирование снимается позже в unescape. */
    private static List<String> splitUnescaped(String dn) {
        List<String> parts = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean escaped = false;
        for (int i = 0; i < dn.length(); i++) {
            char ch = dn.charAt(i);
            if (escaped) {
                cur.append('\\').append(ch);
                escaped = false;
            } else if (ch == '\\') {
                escaped = true;
            } else if (ch == ',' || ch == '+') {
                parts.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(ch);
            }
        }
        if (escaped) {
            cur.append('\\'); // висящий слэш в конце — отдаём как есть
        }
        parts.add(cur.toString());
        return parts;
    }

    private static int indexOfUnescaped(String s, char target) {
        boolean escaped = false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (escaped) {
                escaped = false;
            } else if (ch == '\\') {
                escaped = true;
            } else if (ch == target) {
                return i;
            }
        }
        return -1;
    }

    /** Снимает экранирование RFC 2253: {@code \c} и цепочки {@code \XX} (байты UTF-8). */
    private static String unescape(String value) {
        if (value.indexOf('\\') < 0) {
            return value;
        }
        StringBuilder out = new StringBuilder(value.length());
        int i = 0;
        while (i < value.length()) {
            if (value.charAt(i) != '\\' || i + 1 >= value.length()) {
                out.append(value.charAt(i++));
                continue;
            }
            if (hexPairAt(value, i) < 0) {
                out.append(value.charAt(i + 1)); // обычное \c
                i += 2;
                continue;
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            int b;
            while ((b = hexPairAt(value, i)) >= 0) {
                bytes.write(b);
                i += 3;
            }
            out.append(bytes.toString(StandardCharsets.UTF_8));
        }
        return out.toString();
    }

    /** Байт из {@code \XX} по смещению off или -1, если там не эта форма. */
    private static int hexPairAt(String s, int off) {
        if (off + 2 >= s.length() || s.charAt(off) != '\\') {
            return -1;
        }
        int hi = Character.digit(s.charAt(off + 1), 16);
        int lo = Character.digit(s.charAt(off + 2), 16);
        return hi < 0 || lo < 0 ? -1 : (hi << 4) | lo;
    }

    private static void appendExtensions(StringBuilder s, X509Certificate c) {
        // объединение критичных и некритичных: проверять на null только критичные нельзя,
        // иначе у сертификата без критичных расширений пропадали бы SAN и SCT
        LinkedHashMap<String, Boolean> all = new LinkedHashMap<>();
        Set<String> critical = c.getCriticalExtensionOIDs();
        if (critical != null) {
            critical.forEach(oid -> all.put(oid, true));
        }
        Set<String> nonCritical = c.getNonCriticalExtensionOIDs();
        if (nonCritical != null) {
            nonCritical.forEach(oid -> all.putIfAbsent(oid, false));
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

    /**
     * GeneralNames: печатаем знакомые типы (DNS/URI/IP/email), остальное — hex.
     * Байты приходят из публичного CT-лога, поэтому каждая длина проверяется по границам:
     * на испорченном TLV печатаем разобранное и останавливаемся, а не падаем.
     */
    private static void appendSan(StringBuilder s, byte[] generalNames) {
        int seqLenBytes = derLengthBytes(generalNames, 1);
        if (generalNames.length < 2 || generalNames[0] != 0x30 || seqLenBytes < 0) {
            appendHexIndented(s, generalNames, 16);
            return;
        }
        int i = 1 + seqLenBytes; // пропускаем тег и длину SEQUENCE
        while (i < generalNames.length) {
            int tag = generalNames[i] & 0xFF;
            int lenBytes = derLengthBytes(generalNames, i + 1);
            int len = derLength(generalNames, i + 1);
            if (lenBytes < 0 || len < 0) {
                return;
            }
            int start = i + 1 + lenBytes;
            if (start > generalNames.length || len > generalNames.length - start) {
                return; // TLV обрезан
            }
            byte[] value = Arrays.copyOfRange(generalNames, start, start + len);
            switch (tag) {
                case 0x81 -> s.append("                email:").append(text(value)).append('\n');
                case 0x82 -> s.append("                DNS:").append(text(value)).append('\n');
                case 0x86 -> s.append("                URI:").append(text(value)).append('\n');
                case 0x87 -> s.append("                IP:").append(ipString(value)).append('\n');
                default -> {
                    s.append("                othername:");
                    appendHexIndented(s, value, 16);
                }
            }
            i = start + len;
        }
    }

    /** Значения GeneralName — IA5String; кодировку задаём явно, а не платформенную. */
    private static String text(byte[] value) {
        return new String(value, StandardCharsets.UTF_8);
    }

    /** getExtensionValue возвращает DER OCTET STRING — снимаем внешнюю обёртку. Пакет-private для теста. */
    static byte[] unwrapOctetString(byte[] der) {
        if (der == null || der.length < 2 || der[0] != 0x04) {
            return der;
        }
        int lenBytes = derLengthBytes(der, 1);
        int len = derLength(der, 1);
        if (lenBytes < 0 || len < 0) {
            return der;
        }
        int start = 1 + lenBytes;
        if (start > der.length || len > der.length - start) {
            return der; // обёртка обрезана — отдаём как есть
        }
        return Arrays.copyOfRange(der, start, start + len);
    }

    /**
     * Длина DER-значения по смещению off или -1, если поле длины не читается:
     * выход за границы, неопределённая форма или длина, не влезающая в int.
     * Пакет-private для unit-теста.
     */
    static int derLength(byte[] der, int off) {
        if (off < 0 || off >= der.length) {
            return -1;
        }
        int first = der[off] & 0xFF;
        if ((first & 0x80) == 0) {
            return first;
        }
        int count = first & 0x7F;
        if (count == 0 || count > 4 || off + count >= der.length) {
            return -1;
        }
        long len = 0;
        for (int i = 0; i < count; i++) {
            len = (len << 8) | (der[off + 1 + i] & 0xFF);
        }
        return len > Integer.MAX_VALUE ? -1 : (int) len;
    }

    /** Сколько байт занимает поле длины по смещению off, или -1, если оно за границей. */
    private static int derLengthBytes(byte[] der, int off) {
        if (off < 0 || off >= der.length) {
            return -1;
        }
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
