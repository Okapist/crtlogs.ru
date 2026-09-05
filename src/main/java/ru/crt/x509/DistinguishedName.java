package ru.crt.x509;

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

    private DistinguishedName() {
    }

    /** Одна пара «тип RDN — значение»; список, а не Map: один тип встречается дважды (OU=Ops,OU=IT). */
    public record Rdn(String type, String value) {
    }

    /** Разбор отдан LdapName: он снимает экранирование, раскрывает многозначные RDN и декодирует {@code #hex}; непарсящийся DN — пустой список. */
    public static List<Rdn> parse(String rfc2253) {
        List<Rdn> out = new ArrayList<>();
        try {
            // getRdns() отдаёт от корня, а RFC 2253 печатает в обратном порядке
            for (javax.naming.ldap.Rdn rdn : new LdapName(rfc2253).getRdns().reversed()) {
                if (rdn.size() == 1) {
                    out.add(new Rdn(type(rdn.getType()), value(rdn.getValue())));
                    continue;
                }
                for (var attrs = rdn.toAttributes().getAll(); attrs.hasMore(); ) {
                    Attribute a = attrs.next();
                    out.add(new Rdn(type(a.getID()), value(a.get())));
                }
            }
        } catch (NamingException e) {
            log.warn("DN {} не разобран: {}", rfc2253, e.toString());
        }
        return out;
    }

    /** Первый CN в порядке записи DN; null — если CN нет или DN не разобрался. */
    public static String commonName(String rfc2253) {
        if (rfc2253 == null) {
            return null;
        }
        for (Rdn rdn : parse(rfc2253)) {
            if ("CN".equals(rdn.type())) {
                return rdn.value();
            }
        }
        return null;
    }

    private static String type(String rdnType) {
        return rdnType.toUpperCase(Locale.ROOT);
    }

    /** LdapName отдаёт String либо byte[] — второе для формы {@code #hex}, это DER атрибута. */
    private static String value(Object rdnValue) {
        return rdnValue instanceof byte[] der ? derString(der) : String.valueOf(rdnValue);
    }

    /** DER-значение атрибута: строковые типы — текстом, прочее — исходной формой {@code #hex}. */
    private static String derString(byte[] der) {
        Der.Tlv tlv = Der.Tlv.at(der, 0);
        if (tlv == null) {
            return hexForm(der);
        }
        return switch (tlv.tag()) {
            case Der.UTF8_STRING, Der.PRINTABLE_STRING, Der.IA5_STRING ->
                    tlv.text(der, StandardCharsets.UTF_8);
            case Der.T61_STRING -> tlv.text(der, StandardCharsets.ISO_8859_1);
            case Der.BMP_STRING -> tlv.text(der, StandardCharsets.UTF_16BE);
            default -> hexForm(der);
        };
    }

    private static String hexForm(byte[] der) {
        return "#" + HexFormat.of().formatHex(der);
    }
}
