package ru.crt.web;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * Путь запроса в той нормализации, в которой его сопоставляет Spring MVC.
 * Сравнивать getRequestURI() напрямую нельзя: по спецификации контейнер отдаёт его
 * как есть, поэтому {@code /api/log%73}, {@code /api/logs;v=1} и {@code //api/logs} —
 * разные строки, но все попадают в {@code @GetMapping("/api/logs")}, то есть проверку
 * по equals() можно обойти. Нормализация намеренно шире маршрутизации Spring
 * (схлопывает пустые сегменты, срезает завершающий слэш): лишняя проверка токена
 * безопаснее пропущенной.
 */
final class RequestPaths {

    private RequestPaths() {
    }

    static String normalized(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String context = request.getContextPath();
        if (context != null && !context.isEmpty() && uri.startsWith(context)) {
            uri = uri.substring(context.length());
        }
        StringBuilder out = new StringBuilder(uri.length());
        for (String raw : uri.split("/")) {
            if (raw.isEmpty()) {
                continue;
            }
            int matrix = raw.indexOf(';');
            out.append('/').append(decode(matrix < 0 ? raw : raw.substring(0, matrix)));
        }
        return out.isEmpty() ? "/" : out.toString();
    }

    private static String decode(String segment) {
        if (segment.indexOf('%') < 0) {
            return segment; // заодно не трогаем '+', который в пути значит сам себя
        }
        try {
            return URLDecoder.decode(segment, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return segment; // битая %-последовательность: такой запрос контейнер всё равно отвергнет
        }
    }
}
