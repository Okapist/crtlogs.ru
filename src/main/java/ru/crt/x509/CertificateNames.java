package ru.crt.x509;

import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Имена сертификата: CN из subject DN и GeneralNames из Subject Alternative Name.
 *
 * <p>SAN читается из DER расширения, а не через
 * {@link X509Certificate#getSubjectAlternativeNames()}. По RFC 5280 dNSName — это
 * IA5String, и JDK декодирует его как US-ASCII; но сертификаты на кириллические домены
 * (.рф от УЦ Минцифры) кладут в dNSName имя как есть, в UTF-8, и каждый его байт
 * превращается тогда в U+FFFD. Из такой строки исходное имя уже не восстановить:
 * сертификат не находится по своему имени и показывается в выдаче «ромбиками» —
 * поэтому байты разбираются здесь и декодируются как UTF-8.
 *
 * <p>Цена такого разбора — потерянные проверки JDK: он отвергал расширение целиком,
 * а здесь границы и содержимое каждого имени приходится держать самим. Отсюда две
 * дисциплины, без которых свой разбор опаснее чужого: имя не может выйти за свой
 * SEQUENCE ({@link #generalNames}), а в индекс доменов попадает только то, что может
 * быть доменом ({@link #indexable}).
 *
 * <p>В индекс имя едет в единой форме ({@link DomainName#toIndexForm}) — почему именно
 * такой, рассказано там.
 */
public final class CertificateNames {

    /** OID расширения subjectAltName: тот же и здесь, и там, где дамп решает звать разбор имён. */
    public static final String SAN_OID = "2.5.29.17";

    private CertificateNames() {
    }

    /**
     * Одно имя из GeneralNames: тег (он же тип имени) и содержимое — участок того же
     * буфера, из которого имя разобрано. Не record: на разборе байты не копируются
     * (SAN читается на каждой синхронизируемой записи лога, а нужен из него обычно
     * только текст dNSName), копию отдаёт лишь {@link #value()} и только тому, кто её
     * просит, — а сгенерированный для byte[] toString печатал бы {@code [B@1b6d3586}.
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
     * Разобранные GeneralNames или null, если внешний SEQUENCE не читается — тогда
     * вызывающий печатает hex, а не выдумывает имена.
     *
     * <p>Границей служит конец SEQUENCE, а не конец буфера: байты после него в список
     * не входят — ни отдельным именем, ни хвостом последнего, чья длина за SEQUENCE
     * вылезает. Считать их именем значило бы приписывать сертификату идентификаторы,
     * которых в нём нет. Нечитаемый TLV внутри обрывает разбор — отдаём то, что уже
     * прочитано.
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
        int i = sequence.start(); // содержимое SEQUENCE: тег и длина уже пропущены
        while (i < sequence.end()) {
            // граница — конец SEQUENCE: имя, длина которого вылезает за него, не читается
            // вовсе, а не читается до конца буфера
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
     * Всё, по чему сертификат ищут: dNSName'ы из SAN плюс CN из subject DN, в
     * индексной форме и без повторов, в порядке записи. Отсутствующий или нечитаемый
     * SAN — не ошибка: у сертификата остаётся CN. Единая форма заодно схлопывает
     * повторы: имя, записанное в CN юникодом, а в SAN — punycode'ом, даёт один
     * идентификатор, а не два.
     *
     * <p>CN читается тем же разбором, каким его показывает страница сертификата
     * ({@link DistinguishedName}), чтобы индекс и выдача не разошлись в том, что
     * считать общим именем.
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

    /**
     * Имя в индекс — в той форме, в которой его там ищут ({@link DomainName#toIndexForm}),
     * и только если оно на имя похоже. Проверка идёт по этой форме, а не по исходной
     * записи: приведение меняет длину имени в обе стороны — punycode при расшифровке
     * укорачивается, а nameprep умеет и удлинить ({@code ß} становится {@code ss}).
     * Имя, у которого индексная форма в колонку не влезает, {@code toIndexForm} отдаёт
     * как записано — почему его нельзя отбросить, сказано там же.
     *
     * <p>CN проходит те же проверки, что и dNSName: слишком длинное имя БД отвергает
     * одинаково, и тогда синк терял бы сертификат целиком — а так теряется одно из имён.
     */
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
     * Годится ли имя в индекс доменов. Разбирая SAN сами, мы лишились проверок, которые
     * делал за нас JDK: в dNSName лежит ровно то, что записал подавший сертификат в
     * публичный лог, — любой длины и из любых байт. Имя, не влезающее в
     * cert_domain.domain, БД отвергла бы вместе со всей записью — сертификат терялся бы
     * навсегда, хотя найти его можно хотя бы по другому имени. Пробелы и управляющие
     * символы отсекаются заодно — доменом такое не бывает, а в выдаче ломало бы строку.
     *
     * <p>Дампа страницы это не касается: он показывает, что записано в сертификате,
     * а не то, что мы согласны считать именем. Пакет-private ради unit-теста:
     * сертификата с таким dNSName в фикстурах нет.
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
