package ru.crt.web;

import java.util.List;
import ru.crt.x509.DistinguishedName;
import ru.crt.x509.DomainName;

/**
 * Имена сертификата в том виде, в каком их показывают, — расшифрованными
 * ({@link DomainName#toUnicode}); правило одно на выдачу и страницу сертификата.
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
