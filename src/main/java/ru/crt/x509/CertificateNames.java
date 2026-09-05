package ru.crt.x509;

import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Имена сертификата: CN из subject DN и GeneralNames из SAN, прочитанные из DER
 * своими силами — JDK декодирует dNSName как US-ASCII и превращает UTF-8 кириллицу
 * в U+FFFD. Цена — свои проверки границ и пригодности имени.
 */
public final class CertificateNames {

    /** OID расширения subjectAltName: тот же и здесь, и там, где дамп решает звать разбор имён. */
    public static final String SAN_OID = "2.5.29.17";

    private CertificateNames() {
    }

    /**
     * Одно имя из GeneralNames: тег (он же тип имени) и содержимое — участок того же
     * буфера. Не record: на разборе байты не копируются (SAN читается на каждой записи
     * синка), копию отдаёт лишь {@link #value()}.
     */
    public static final class GeneralName {

        /** Теги, которые страница сертификата называет по имени; остальное — hex. */
        public static final int EMAIL = 0x81;
        public static final int DNS = 0x82;
        public static final int URI = 0x86;
        public static final int IP = 0x87;

        private static final HexFormat HEX = HexFormat.ofDelimiter(":");

        private final byte[] der;
        private final Der.Tlv tlv;

        private GeneralName(byte[] der, Der.Tlv tlv) {
            this.der = der;
            this.tlv = tlv;
        }

        public int tag() {
            return tlv.tag();
        }

        /** Содержимое строкой; кодировку задаём явно, а не платформенную. */
        public String text() {
            return tlv.text(der, StandardCharsets.UTF_8);
        }

        /** Содержимое как есть — копией: что с ней сделают, разбора уже не касается. */
        public byte[] value() {
            return tlv.value(der);
        }

        /** Текстовые типы — текстом, остальные — hex: сообщение упавшего теста должно читаться. */
        @Override
        public String toString() {
            String value = switch (tag()) {
                case EMAIL, DNS, URI -> text();
                default -> HEX.formatHex(value());
            };
            return "GeneralName[tag=0x" + Integer.toHexString(tag()) + ", " + value + "]";
        }
    }

    /**
     * Разобранные GeneralNames или null, если внешний SEQUENCE не читается. Границей
     * служит конец SEQUENCE, а не конец буфера; нечитаемый TLV внутри обрывает разбор.
     */
    public static List<GeneralName> generalNames(byte[] extensionValue) {
        if (extensionValue == null) {
            return null;
        }
        Der.Tlv sequence = Der.Tlv.at(extensionValue, 0);
        if (sequence == null || sequence.tag() != Der.SEQUENCE) {
            return null;
        }
        List<GeneralName> out = new ArrayList<>();
        int i = sequence.start(); // тег и длина SEQUENCE уже пропущены
        while (i < sequence.end()) {
            Der.Tlv name = Der.Tlv.at(extensionValue, i, sequence.end());
            if (name == null) {
                break;
            }
            out.add(new GeneralName(extensionValue, name));
            i = name.end();
        }
        return out;
    }

    /**
     * Всё, по чему сертификат ищут: dNSName'ы из SAN плюс CN из subject DN, в индексной
     * форме и без повторов. Отсутствующий или нечитаемый SAN — не ошибка.
     */
    public static Set<String> domainsOf(X509Certificate cert) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        List<GeneralName> san = generalNames(Der.unwrapOctetString(cert.getExtensionValue(SAN_OID)));
        if (san != null) {
            for (GeneralName name : san) {
                if (name.tag() != GeneralName.DNS) {
                    continue;
                }
                add(out, name.text());
            }
        }
        add(out, DistinguishedName.commonName(cert.getSubjectX500Principal().getName()));
        return out;
    }

    private static void add(Set<String> out, String name) {
        if (name == null) {
            return;
        }
        String indexed = DomainName.toIndexForm(name);
        if (indexable(indexed)) {
            out.add(indexed);
        }
    }

    /**
     * Годится ли имя в индекс cert_domain: слишком длинное или с управляющими символами
     * БД отвергла бы вместе со всей записью, и сертификат терялся бы целиком.
     */
    static boolean indexable(String dns) {
        if (dns.isBlank() || dns.length() > DomainName.MAX_LENGTH) {
            return false;
        }
        for (int i = 0; i < dns.length(); i++) {
            if (dns.charAt(i) <= ' ') {
                return false;
            }
        }
        return true;
    }
}
