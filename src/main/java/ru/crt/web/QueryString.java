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
 * уходит ссылкой ({@link #encode}). Оба опираются на одно правило — что считать
 * escape'ом, — иначе ссылка вела бы не туда, откуда её взяли.
 */
final class QueryString {

    private QueryString() {
    }

    /** Проценты в адресе принято писать заглавными: {@code %D1%80}, а не {@code %d1%80}. */
    private static final HexFormat HEX = HexFormat.of().withUpperCase();

    // --- разбор ---

    /**
     * Параметры строки запроса: как у контейнера, кроме одиночного '%', который
     * здесь значит сам себя. Зачем — см. {@link LenientQueryFilter}.
     */
    static Map<String, String[]> parse(String query) {
        // значения копятся в список: «q=1&q=1…» на 8 КБ — а фильтр стоит до лимитера
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

    /** Разберёт ли контейнер так же; отличие у нас одно — одиночный '%'. */
    static boolean sameAsContainer(String query) {
        return query.indexOf('%') < 0;
    }

    /** Декодирует URLDecoder; мы лишь заменяем одиночный '%' на его же escape. */
    private static String decode(String raw) {
        if (raw.indexOf('%') < 0 && raw.indexOf('+') < 0) {
            return raw;
        }
        return URLDecoder.decode(escapeStrayPercents(raw), StandardCharsets.UTF_8);
    }

    /** Одиночный '%' — в {@code %25}; тем же приёмом читает свои сегменты {@link RequestPaths}. */
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
     * Значение параметра для адреса: как есть остаются unreserved, всё вне ASCII
     * и '%', за которым не стоят две шестнадцатеричные цифры. Прочее — в {@code %XX};
     * результат можно ставить в разметку без экранирования.
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

    /** {@link HexFormat#isHexDigit} принимает только ASCII-цифры — как и контейнер. */
    private static boolean isEscape(String raw, int percent) {
        return percent + 2 < raw.length()
                && HexFormat.isHexDigit(raw.charAt(percent + 1))
                && HexFormat.isHexDigit(raw.charAt(percent + 2));
    }
}
