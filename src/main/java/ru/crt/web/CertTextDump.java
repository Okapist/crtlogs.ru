package ru.crt.web;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.security.PublicKey;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.security.auth.x500.X500Principal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.crt.x509.CertificateNames;
import ru.crt.x509.Der;
import ru.crt.x509.DistinguishedName;
import ru.crt.x509.X509;

/** Текстовый дамп сертификата в духе {@code openssl x509 -text} — как на странице сертификата crt.sh. Побайтовой точности нет, структура повторяется. */
public final class CertTextDump {

    private static final Logger log = LoggerFactory.getLogger(CertTextDump.class);

    private CertTextDump() {
    }

    /** Известные имена расширений, как их называет openssl. */
    private static final Map<String, String> EXTENSIONS = Map.ofEntries(
            Map.entry(CertificateNames.SAN_OID, "X509v3 Subject Alternative Name"),
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

    /** "Aug  3 08:05:53 2026 GMT" — как печатает openssl (день — пробелом). У DateTimeFormatter цифры всегда ASCII; String.format брал бы их из локали. */
    private static final DateTimeFormatter OPENSSL_DATE = new DateTimeFormatterBuilder()
            .appendPattern("MMM ")
            .padNext(2)
            .appendValue(ChronoField.DAY_OF_MONTH)
            .appendPattern(" HH:mm:ss ")
            .appendValue(ChronoField.YEAR, 4)
            .appendLiteral(" GMT")
            .toFormatter(Locale.ENGLISH);

    private static final HexFormat LOWER_COLON = HexFormat.ofDelimiter(":");
    private static final HexFormat UPPER_COLON = LOWER_COLON.withUpperCase();

    /** Отступы блоков hex — как их печатает openssl x509 -text. */
    private static final String EXT_INDENT = "                ";
    private static final String SIG_INDENT = "             ";

    public static String render(byte[] der) {
        X509Certificate c;
        try {
            c = X509.parse(der);
        } catch (CertificateException e) {
            return null;
        }
        try {
            return dump(c);
        } catch (RuntimeException e) {
            // содержимое — от кого угодно: страница не должна падать из-за одной кривой записи
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
                .append(UPPER_COLON.formatHex(c.getSerialNumber().toByteArray())).append('\n');
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
        appendHex(s, c.getSignature(), SIG_INDENT, 18);
        return s.toString();
    }

    private static void appendDn(StringBuilder s, String label, X500Principal dn) {
        s.append("        ").append(label).append(":\n");
        for (DistinguishedName.Rdn rdn : DistinguishedName.parse(dn.getName())) {
            String name = DN_NAMES.getOrDefault(rdn.type(), rdn.type());
            // 24 — по самому длинному имени из DN_NAMES
            s.append("                ").append(String.format(Locale.ROOT, "%-24s", name))
                    .append("= ").append(rdn.value()).append('\n');
        }
    }

    private static void appendExtensions(StringBuilder s, X509Certificate c) {
        // null бывает у обоих наборов — объединяем критичные и некритичные
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
            byte[] value = Der.unwrapOctetString(c.getExtensionValue(oid));
            if (value == null) {
                continue;
            }
            if (CertificateNames.SAN_OID.equals(oid)) {
                appendSan(s, value);
            } else {
                appendHex(s, value, EXT_INDENT, 16);
            }
        }
    }

    /** Знакомые типы — текстом, остальное — hex; разбор общий с синком ({@link CertificateNames}). */
    static void appendSan(StringBuilder s, byte[] generalNames) {
        List<CertificateNames.GeneralName> names = CertificateNames.generalNames(generalNames);
        if (names == null) { // список не читается — показываем байты, а не выдумываем имена
            appendHex(s, generalNames, EXT_INDENT, 16);
            return;
        }
        for (CertificateNames.GeneralName name : names) {
            switch (name.tag()) {
                case CertificateNames.GeneralName.EMAIL -> appendName(s, "email", name.text());
                case CertificateNames.GeneralName.DNS -> appendName(s, "DNS", name.text());
                case CertificateNames.GeneralName.URI -> appendName(s, "URI", name.text());
                case CertificateNames.GeneralName.IP -> appendName(s, "IP", ipString(name.value()));
                default -> {
                    // строку закрываем здесь: у имени нулевой длины appendHex не печатает ни байта
                    s.append(EXT_INDENT).append("othername:\n");
                    appendHex(s, name.value(), EXT_INDENT, 16);
                }
            }
        }
    }

    private static void appendName(StringBuilder s, String type, String value) {
        s.append(EXT_INDENT).append(type).append(':').append(value).append('\n');
    }

    private static String ipString(byte[] b) {
        if (b.length == 4 || b.length == 16) {
            try {
                return InetAddress.getByAddress(b).getHostAddress();
            } catch (UnknownHostException ignored) {
            }
        }
        return LOWER_COLON.formatHex(b);
    }

    /** Байты по perLine в строке, через двоеточие, с отступом — как в выводе openssl. */
    private static void appendHex(StringBuilder s, byte[] value, String indent, int perLine) {
        for (int i = 0; i < value.length; i += perLine) {
            s.append(indent);
            LOWER_COLON.formatHex(s, value, i, Math.min(i + perLine, value.length));
            s.append('\n');
        }
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

    private static String opensslDate(Instant instant) {
        return OPENSSL_DATE.format(instant.atZone(ZoneOffset.UTC));
    }
}
