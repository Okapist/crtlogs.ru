package ru.crt.web;

import java.util.List;
import ru.crt.x509.DistinguishedName;
import ru.crt.x509.DomainName;

/**
 * Имена сертификата в том виде, в каком их показывают, — правило одно на выдачу
 * поиска и страницу сертификата, иначе {@code common_name} и {@code name_value} могли
 * бы разойтись между ними.
 *
 * <p>Показываются имена расшифрованными ({@link DomainName#toUnicode}): CN читается из
 * subject DN в той форме, в какой его записал УЦ (кириллическое имя — чаще punycode), —
 * а {@code xn--80aasee0af7fwb.xn--p1ai} читателю не говорит ничего. Идентификаторы из
 * cert_domain и так лежат расшифрованными, так что повторное применение на них ничего
 * не меняет. Дампа сертификата это не касается: он печатает, что записано.
 */
final class DisplayNames {

    private DisplayNames() {
    }

    /** CN из subject DN — тем же разбором, что и индекс ({@link DistinguishedName}). */
    static String commonName(String subjectDn) {
        return DomainName.toUnicode(DistinguishedName.commonName(subjectDn));
    }

    /** Идентификаторы из cert_domain, расшифрованные и по алфавиту показанных имён. */
    static List<String> domains(List<String> stored) {
        return DomainName.toUnicodeAll(stored);
    }

    /** {@code name_value} crt.sh: те же идентификаторы через '\n'. */
    static String nameValue(List<String> domains) {
        return String.join("\n", domains);
    }
}
