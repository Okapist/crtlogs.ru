package ru.crt.x509;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.ldap.LdapName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Subject/issuer DN сертификата: строка RFC 2253 -> упорядоченные пары «тип атрибута — значение». Разбор один на всех: дамп, колонка Common Name и CN в индексе доменов. */
public final class DistinguishedName {

    private static final Logger log = LoggerFactory.getLogger(DistinguishedName.class);

    private static final Charset UTF_32BE = Charset.forName("UTF-32BE");

    private DistinguishedName() {
    }

    /** Одна пара «тип RDN — значение»; список, а не Map: один тип встречается дважды (OU=Ops,OU=IT). {@code text} — значение прочитано как строка, а не оставлено в форме {@code #hex}. */
    public record Rdn(String type, String value, boolean text) {
    }

    /** Разбор отдан LdapName: он снимает экранирование, раскрывает многозначные RDN и декодирует {@code #hex}; непарсящийся DN — пустой список. */
    public static List<Rdn> parse(String rfc2253) {
        List<Rdn> out = new ArrayList<>();
        try {
            // getRdns() отдаёт от корня, а RFC 2253 печатает в обратном порядке
            for (javax.naming.ldap.Rdn rdn : new LdapName(rfc2253).getRdns().reversed()) {
                if (rdn.size() == 1) {
                    out.add(rdnOf(rdn.getType(), rdn.getValue()));
                    continue;
                }
                // toAttributes() склеивает одинаковые типы в один атрибут — берём все его значения, а не первое
                for (var attrs = rdn.toAttributes().getAll(); attrs.hasMore(); ) {
                    Attribute a = attrs.next();
                    for (var values = a.getAll(); values.hasMore(); ) {
                        out.add(rdnOf(a.getID(), values.next()));
                    }
                }
            }
        } catch (NamingException e) {
            log.warn("DN {} не разобран: {}", rfc2253, e.toString());
        }
        return out;
    }

    /** Первый текстовый CN в порядке записи DN; null — если CN нет, он не строка (в индекс доменов {@code #hex} не место) или DN не разобрался. */
    public static String commonName(String rfc2253) {
        if (rfc2253 == null) {
            return null;
        }
        for (Rdn rdn : parse(rfc2253)) {
            if ("CN".equals(rdn.type())) {
                return rdn.text() ? rdn.value() : null;
            }
        }
        return null;
    }

    /** LdapName отдаёт String либо byte[] — второе для формы {@code #hex}, это DER атрибута. */
    private static Rdn rdnOf(String type, Object rdnValue) {
        String upperType = type.toUpperCase(Locale.ROOT);
        if (!(rdnValue instanceof byte[] der)) {
            return new Rdn(upperType, String.valueOf(rdnValue), true);
        }
        String decoded = derString(der);
        return decoded != null
                ? new Rdn(upperType, decoded, true)
                : new Rdn(upperType, "#" + HexFormat.of().formatHex(der), false);
    }

    /** DER-значение атрибута строковых типов текстом; прочее — null, останется в форме {@code #hex}. */
    private static String derString(byte[] der) {
        Der.Tlv tlv = Der.Tlv.at(der, 0);
        if (tlv == null) {
            return null;
        }
        return switch (tlv.tag()) {
            case Der.UTF8_STRING, Der.PRINTABLE_STRING, Der.IA5_STRING ->
                    tlv.text(der, StandardCharsets.UTF_8);
            // ОГРН, ИНН, СНИЛС в российских DN — NumericString
            case Der.NUMERIC_STRING, Der.VISIBLE_STRING -> tlv.text(der, StandardCharsets.US_ASCII);
            case Der.T61_STRING -> tlv.text(der, StandardCharsets.ISO_8859_1);
            case Der.BMP_STRING -> tlv.text(der, StandardCharsets.UTF_16BE);
            case Der.UNIVERSAL_STRING -> tlv.text(der, UTF_32BE);
            default -> null;
        };
    }

}
