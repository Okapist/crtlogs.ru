package ru.crt.web;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Правила строки запроса: разбор пришедшей и сборка уходящей ссылкой.
 *
 * <p>Разбор здесь проверяется как чистая функция; то, что эти параметры видит дальше
 * поиск и что на живом Jetty адрес с одиночным '%' больше не даёт 400, закрепляет
 * {@link MalformedQueryHttpTest} — MockMvc для этого не годится, он получает уже
 * разобранные параметры и строку запроса не трогает вовсе.
 */
class QueryStringTest {

    /** То, ради чего всё и написано: '%' сам по себе — обычный символ шаблона LIKE. */
    @Test
    void strayPercentIsAnOrdinaryCharacter() {
        assertEquals("%.com", value("q=%.com"));
        assertEquals("%.рф", value("q=%.%D1%80%D1%84"));
        assertEquals("%vk%", value("q=%vk%"));
        assertEquals("abc%", value("q=abc%"), "'%' в самом конце — тоже просто символ");
        assertEquals("%2", value("q=%2"), "escape оборван: цифр не хватает на пару");
    }

    /** Правильно закодированный запрос обязан разбираться ровно как раньше. */
    @Test
    void ordinaryEscapesKeepTheirMeaning() {
        assertEquals("%.com", value("q=%25.com"));
        assertEquals("%.рф", value("q=%25.%D1%80%D1%84"));
        assertEquals("a b", value("q=a+b"), "'+' в строке запроса — пробел");
        assertEquals("a&b", value("q=a%26b"), "закодированный разделитель значение не режет");
        assertEquals("пример.рф", value("q=%D0%BF%D1%80%D0%B8%D0%BC%D0%B5%D1%80.%D1%80%D1%84"));
    }

    /**
     * Байты, которые в UTF-8 не складываются (одиночный {@code %D1}), — единственный
     * случай, где ответить исходной строкой нельзя: что имел в виду отправитель,
     * неизвестно. Важно, что это не отказ: запрос доходит до поиска и просто ничего
     * не находит, а не превращается в 400 без объяснения.
     */
    @Test
    void undecodableBytesBecomeTheReplacementCharacterInsteadOfAFailure() {
        assertEquals("�.ru", value("q=%D1.ru"));
    }

    @Test
    void namesValuesAndSeparatorsFollowTheUsualRules() {
        assertEquals(List.of("q", "output"), List.copyOf(QueryString.parse("q=%.com&output=json").keySet()));
        assertEquals("json", value("q=%.com&output=json", "output"));
        assertEquals("", value("q=%.com&exclude", "exclude"), "параметр без '=' — с пустым значением");
        assertEquals("%.com", value("&q=%.com&"), "пустые куски между '&' параметрами не считаются");
        assertEquals("%.com", value("%71=%.com", "q"), "имя декодируется так же, как значение");
        assertArrayEquals(new String[]{"%.com", "%.ru"},
                QueryString.parse("q=%.com&q=%.ru").get("q"), "повторы сохраняют порядок");
        assertNull(QueryString.parse("q=%.com").get("output"));
    }

    /** Обычный запрос в ссылке остаётся собой — кодировать в нём нечего. */
    @Test
    void readableQueriesAreLeftAlone() {
        assertEquals("example.ru", QueryString.encode("example.ru"));
        assertEquals("%.example.ru", QueryString.encode("%.example.ru"));
        assertEquals("%vk%", QueryString.encode("%vk%"));
        assertEquals("_.example.ru", QueryString.encode("_.example.ru"), "'_' — тоже подстановочный знак");
        assertEquals("%.рф", QueryString.encode("%.рф"), "кириллицу браузер покажет как есть");
        assertEquals("почта.онлайн", QueryString.encode("почта.онлайн"));
    }

    /**
     * Кодируется ровно то, что иначе прочитается по-другому: '&' разрезал бы параметр,
     * '+' стал бы пробелом, '#' — началом якоря, а '%' перед парой цифр — escape'ом.
     */
    @Test
    void onlyCharactersThatWouldChangeTheMeaningAreEscaped() {
        assertEquals("a%26b", QueryString.encode("a&b"));
        assertEquals("a%2Bb", QueryString.encode("a+b"));
        assertEquals("a%20b", QueryString.encode("a b"));
        assertEquals("a%23b", QueryString.encode("a#b"));
        assertEquals("%25ab.ru", QueryString.encode("%ab.ru"), "'%ab' иначе стал бы байтом 0xAB");
        assertEquals("%25D1%2580", QueryString.encode("%D1%80"), "запрос из процентов — весь в escape'ах");
        assertEquals("%2525.com", QueryString.encode("%25.com"), "искали буквально '%25' — его и найдём");
        assertEquals("%%2541", QueryString.encode("%%41"), "решение принимается по каждому '%' отдельно");
        assertEquals("%2541%", QueryString.encode("%41%"), "последнему цифр уже не досталось");
    }

    /**
     * Главное свойство пары: ссылка ведёт туда, откуда её взяли. Проверяется тем же
     * разбором, которым читается пришедший запрос, — иначе «красиво» могло бы значить
     * «не находит».
     *
     */
    @Test
    void everyEncodedValueParsesBackToItself() {
        for (String q : AWKWARD_QUERIES) {
            String encoded = QueryString.encode(q);
            assertEquals(q, QueryString.parse("q=" + encoded).get("q")[0], "ссылка ?q=" + encoded);
        }
    }

    /** Запросы, на которых кодирование и разбор ведут себя интереснее всего. */
    private static final List<String> AWKWARD_QUERIES = List.of(
            "example.ru", "%.example.ru", "%vk%", "_.example.ru", "%.рф", "почта.онлайн",
            "a&b", "a+b", "a b", "a#b", "a=b", "a;b", "a?b", "a/b", "a\nb", "'\"<>",
            "%ab.ru", "%D1%80", "%25.com", "%%41", "%41%", "%%", "%2", "%1", "%G0",
            "abc%", "a%zz", "%", "ü", "😀.рф");

    /**
     * Собранное значение уходит прямо в атрибут href, поэтому в нём не должно оставаться
     * ничего, что разметка прочитает по-своему: экранировать его на месте сборки —
     * значит помнить про это в каждой из пяти ссылок страницы выдачи.
     */
    @Test
    void encodedValueIsSafeToPutIntoMarkupAsIs() {
        // перебираем весь ASCII, а не горсть подозрительных символов: расширь кто-нибудь
        // isUnreserved() (скажем, до sub-delims RFC 3986) — и выборочные значения новую
        // ветку просто не задели бы, а разметка молча получила бы сырой '&' в href
        for (int code = 0; code < 128; code++) {
            String q = "a" + (char) code + "b";
            String encoded = QueryString.encode(q);
            // esc() и есть то, чем экранируется остальная разметка, поэтому спрашиваем
            // именно его: «в строке нет & " < > '» — это ровно «esc() её не меняет»
            assertEquals(encoded, HtmlView.esc(encoded), "экранировать в '" + encoded + "' нечего");
        }
        for (String q : AWKWARD_QUERIES) {
            String encoded = QueryString.encode(q);
            assertEquals(encoded, HtmlView.esc(encoded), "экранировать в '" + encoded + "' нечего");
        }
    }

    /** Значение параметра {@code q} (или другого названного) из строки запроса. */
    private static String value(String query) {
        return value(query, "q");
    }

    private static String value(String query, String name) {
        String[] values = QueryString.parse(query).get(name);
        return values == null ? null : values[0];
    }
}
