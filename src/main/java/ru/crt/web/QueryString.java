package ru.crt.web;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Строка запроса в обе стороны: разбор пришедшей ({@link #parse}) и сборка той, что
 * уходит ссылкой в разметку ({@link #encode}).
 *
 * <p>Направления живут вместе, потому что опираются на один и тот же вопрос: считать ли
 * очередной '%' началом escape-последовательности. Разбор на этом решает, читать ли
 * дальше две цифры или взять символ как есть; сборка — оставлять ли '%' в адресе или
 * записать его как {@code %25}. Разъехавшись, две копии этого правила дали бы ссылку,
 * которая ведёт не туда, откуда её взяли, и заметить это было бы нечем.
 *
 * <p>Оба метода описывают одно свойство: {@code parse("q=" + encode(v))} возвращает
 * ровно {@code v}. Тест его и проверяет — прямо на значениях, которые собирают ссылки
 * страницы выдачи.
 */
final class QueryString {

    private QueryString() {
    }

    /** Проценты в адресе принято писать заглавными: {@code %D1%80}, а не {@code %d1%80}. */
    private static final HexFormat HEX = HexFormat.of().withUpperCase();

    // --- разбор ---

    /**
     * Параметры строки запроса: {@code %XX} в байт, '+' в пробел, разделитель '&amp;',
     * кодировка UTF-8 — всё как у контейнера, кроме одиночного '%', который здесь значит
     * сам себя. Зачем это нужно и кто этим пользуется — в {@link LenientQueryFilter}.
     */
    static Map<String, String[]> parse(String query) {
        // значения копятся в список, а не дописываются в массив копией: «q=1&q=1&…» на
        // 8 КБ — это 2000 повторов одного имени, и фильтр стоит до лимитера частоты
        Map<String, List<String>> byName = new LinkedHashMap<>();
        for (String pair : query.split("&")) {
            if (pair.isEmpty()) {
                continue; // «?&q=x» и хвостовой '&' — не имя параметра
            }
            int eq = pair.indexOf('=');
            // «?q» без '=' — параметр с пустым значением, как и у контейнера
            byName.computeIfAbsent(decode(eq < 0 ? pair : pair.substring(0, eq)), _ -> new ArrayList<>())
                    .add(eq < 0 ? "" : decode(pair.substring(eq + 1)));
        }
        Map<String, String[]> parameters = new LinkedHashMap<>();
        byName.forEach((name, values) -> parameters.put(name, values.toArray(String[]::new)));
        return Collections.unmodifiableMap(parameters);
    }

    /**
     * Разберёт ли контейнер эту строку так же, как {@link #parse}. Отличие у нас ровно
     * одно — одиночный '%', — поэтому без него подменять разбор нечем и незачем.
     *
     * <p>Проверка живёт здесь, а не у единственного вызывающего: она утверждает свойство
     * самого разбора, и появись у {@link #parse} второе отличие, править пришлось бы обе
     * строки, а не одну.
     */
    static boolean sameAsContainer(String query) {
        return query.indexOf('%') < 0;
    }

    /**
     * Само декодирование делает {@link URLDecoder} — переписывать правила %XX, '+' и
     * UTF-8 своими руками значило бы завести вторую их версию. Мы лишь заменяем
     * одиночный '%' на его же escape, после чего декодеру нечему удивляться: битых
     * последовательностей, ради которых он бросает исключение, в строке не остаётся.
     */
    private static String decode(String raw) {
        if (raw.indexOf('%') < 0 && raw.indexOf('+') < 0) {
            return raw;
        }
        return URLDecoder.decode(escapeStrayPercents(raw), StandardCharsets.UTF_8);
    }

    /**
     * Одиночный '%' — в его же escape, чтобы дальше строку можно было отдать декодеру,
     * который на битой последовательности бросает исключение. Пакет-private: тем же
     * приёмом читает свои сегменты {@link RequestPaths}, и правило «что считается
     * escape'ом» должно быть у пути и строки запроса одно.
     *
     * <p>Цифры настоящего escape'а переносит общая ветка на следующих витках: '%' среди
     * них быть не может, поэтому перепрыгивать через них незачем — и условие тут ровно
     * то же, что в {@link #encode}, где '%' решается судьба в другую сторону.
     */
    static String escapeStrayPercents(String raw) {
        StringBuilder out = new StringBuilder(raw.length() + 8);
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '%' && !isEscape(raw, i)) {
                out.append("%25");
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    // --- сборка ---

    /**
     * Значение параметра для адреса: кодируется только то, без чего адрес прочитается
     * иначе. Ссылки страницы выдачи — сортировка, страницы, JSON-выдача — ведут на тот
     * же запрос, которым посетитель искал, и {@code ?q=%25.%D1%80%D1%84} в строке
     * браузера не говорит ему ничего, тогда как {@code ?q=%.рф} — говорит.
     *
     * <p>Как есть остаются три вещи:
     * <ul>
     *   <li>{@code A-Z a-z 0-9 - . _ ~} — unreserved по RFC 3986, значат сами себя везде;
     *   <li>всё, что вне ASCII: браузер сам закодирует кириллицу, когда пойдёт по ссылке,
     *       а показывать будет её же — то есть домен остаётся читаемым;
     *   <li>'%', за которым не стоят две шестнадцатеричные цифры: прочитать его как
     *       escape нельзя, значит и неоднозначности нет. Шаблоны поиска состоят из него
     *       наполовину ({@code %.example.ru}, {@code %vk%}), ради них всё и затевалось.
     * </ul>
     *
     * <p>Остальное уходит в {@code %XX}, и '%', за которым цифры есть, — тоже: иначе
     * ссылка на {@code %ab.ru} вернула бы поиск по байту 0xAB. Цифры при этом остаются
     * цифрами (они unreserved), поэтому решение принимается по исходной строке: то, что
     * стоит за '%' на входе, стоит за ним и на выходе.
     *
     * <p>Результат можно ставить в разметку без экранирования: ни '&amp;', ни кавычек,
     * ни угловых скобок среди перечисленного нет — все они ASCII и не unreserved.
     */
    static String encode(String value) {
        StringBuilder out = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (isUnreserved(c) || c > 0x7F || (c == '%' && !isEscape(value, i))) {
                out.append(c);
            } else {
                out.append('%').append(HEX.toHexDigits((byte) c));
            }
        }
        return out.toString();
    }

    private static boolean isUnreserved(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                || c == '-' || c == '.' || c == '_' || c == '~';
    }

    // --- общее правило обеих сторон ---

    /**
     * Стоит ли на позиции {@code percent} настоящий escape. {@link HexFormat#isHexDigit}
     * принимает только ASCII-цифры — как и контейнер; {@code Character.digit} счёл бы
     * шестнадцатеричной и арабо-индийскую цифру, и escape'ы разъехались бы с ним.
     */
    private static boolean isEscape(String raw, int percent) {
        return percent + 2 < raw.length()
                && HexFormat.isHexDigit(raw.charAt(percent + 1))
                && HexFormat.isHexDigit(raw.charAt(percent + 2));
    }
}
