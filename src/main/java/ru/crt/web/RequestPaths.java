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

    /**
     * Публичный статус синхронизации. Знают оба фильтра: один пускает его без токена
     * (по нему деплой проверяет живость), другой — без лимита частоты. Константа одна,
     * потому что разъехавшиеся копии этой строки означали бы закрытый деплою эндпоинт
     * или, наоборот, дыру в лимите.
     */
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

    /**
     * Битую %-последовательность разбираем тем же правилом, что и строку запроса
     * ({@link QueryString#escapeStrayPercents}): одиночный '%' значит сам себя, а
     * соседние настоящие escape'ы разбираются. На проверку токена это не влияет —
     * {@code /api/lo%gs%2E} не равен {@link #LOGS} ни в каком разборе.
     */
    private static String decode(String segment) {
        if (segment.indexOf('%') < 0) {
            return segment;
        }
        // '+' в пути значит сам себя, а URLDecoder сделал бы из него пробел — поэтому он
        // уходит декодеру своим escape'ом
        return URLDecoder.decode(
                QueryString.escapeStrayPercents(segment).replace("+", "%2B"), StandardCharsets.UTF_8);
    }
}
