package ru.crt.x509;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Разбор DN в формате RFC 2253 — его читают дамп, Common Name выдачи и индекс доменов. */
class DistinguishedNameTest {

    /** Наивный split по ',' рвал значения с экранированной запятой, а Map терял повторный OU. */
    @Test
    void dnKeepsEscapedCommasAndRepeatedTypes() {
        var rdns = DistinguishedName.parse("CN=Foo\\, Inc.,OU=Ops,OU=IT,O=Bar");
        assertEquals(List.of("CN", "OU", "OU", "O"),
                rdns.stream().map(DistinguishedName.Rdn::type).toList());
        assertEquals("Foo, Inc.", rdns.getFirst().value(), "экранированная запятая остаётся в значении");
        assertEquals("IT", rdns.get(2).value(), "второй OU не должен затирать первый");
    }

    /** Экранированный ведущий пробел и DER-значение в форме #hex — как их печатает openssl. */
    @Test
    void dnUnescapesValuesAndDecodesHexForm() {
        var rdns = DistinguishedName.parse(
                "1.2.840.113549.1.9.1=#161161646d696e4061637469766562632e7275,O=\\ ActiveBusinessConsult");
        assertEquals("admin@activebc.ru", rdns.getFirst().value(), "#hex — это DER IA5String");
        assertEquals(" ActiveBusinessConsult", rdns.get(1).value(), "\\<пробел> — это пробел, а не слэш");
    }

    /** Непарсящийся DN не должен ронять страницу — в дампе просто не будет строк атрибутов. */
    @Test
    void unparseableDnGivesNoAttributes() {
        assertTrue(DistinguishedName.parse("это не DN").isEmpty());
    }

    @Test
    void commonNameIsTheFirstCnInWrittenOrder() {
        assertEquals("cateam.ru", DistinguishedName.commonName("CN=cateam.ru"));
        assertEquals("Foo, Inc.", DistinguishedName.commonName("CN=Foo\\, Inc.,O=Bar"),
                "экранированная запятая остаётся в значении");
        assertEquals("cateam.ru", DistinguishedName.commonName("O=Bar,cn=cateam.ru"),
                "имя атрибута регистронезависимо");
        assertEquals("first.example", DistinguishedName.commonName("CN=first.example,CN=second.example"),
                "берётся первый CN в порядке записи DN");
        assertNull(DistinguishedName.commonName("O=Bar"), "CN может и не быть");
        assertNull(DistinguishedName.commonName(""), "пустой DN — тоже");
        assertNull(DistinguishedName.commonName("это не DN"));
        assertNull(DistinguishedName.commonName(null));
    }
}
