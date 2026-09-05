package ru.crt.web;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * Путь запроса в нормализации Spring MVC: {@code /api/log%73} и {@code //api/logs} попадают в один маршрут, поэтому проверку по equals() сырого getRequestURI() можно обойти.
 * Нормализация шире маршрутизации: лишняя проверка токена безопаснее пропущенной.
 */
final class RequestPaths {

    private RequestPaths() {
    }

    /** Публичный статус: один фильтр пускает его без токена (живость деплоя), другой — без лимита частоты. */
    static final String STATUS = "/api/status";

    /** Статус логов с текстами ошибок — закрыт токеном. */
    static final String LOGS = "/api/logs";

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

    /** Битую %-последовательность разбирает тем же правилом, что и строку запроса. */
    private static String decode(String segment) {
        if (segment.indexOf('%') < 0) {
            return segment;
        }
        // '+' в пути значит сам себя, а URLDecoder сделал бы из него пробел
        return URLDecoder.decode(
                QueryString.escapeStrayPercents(segment).replace("+", "%2B"), StandardCharsets.UTF_8);
    }
}
