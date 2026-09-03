package ru.crt.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import tools.jackson.core.io.JsonStringEncoder;

/**
 * Отказ в том же виде, в каком клиент просил данные: {@code {"error": "..."}} тем, кто
 * пришёл за JSON, и обычная главная с плашкой — браузеру. Без этого посетитель,
 * упёршийся в лимит частоты, получал в окно браузера строку JSON.
 *
 * <p>Место одно на всех, потому что отказывают трое и в разных слоях: два фильтра —
 * до MVC, {@link ErrorAdvice} — внутри него. Разъехавшись, они отвечали бы на
 * одинаковые запросы то JSON-ом, то страницей.
 */
final class ErrorReply {

    private ErrorReply() {
    }

    /** Тип и тело ответа: фильтр пишет их в поток, {@link ErrorAdvice} заворачивает в ResponseEntity. */
    record Reply(MediaType type, String body) {
    }

    /** Клиент пришёл за JSON: явный {@code output=json} либо любой эндпоинт /api/**. */
    static boolean wantsJson(HttpServletRequest request) {
        return "json".equals(request.getParameter("output"))
                || RequestPaths.normalized(request).startsWith("/api/");
    }

    /**
     * Страница отказа — главная, где в форме остался исходный запрос вместе с фильтром
     * «только действующие». Без него отказ на {@code ?q=%&exclude=expired} возвращал
     * форму со снятым чекбоксом, и поправленный запрос молча искал заново по всем
     * сертификатам, включая просроченные.
     */
    static Reply of(HttpServletRequest request, String message) {
        if (wantsJson(request)) {
            // текст может нести данные запроса (см. ErrorAdvice.badParameter), поэтому
            // экранируется полноценным кодировщиком Jackson, а не заменой двух символов
            StringBuilder json = new StringBuilder("{\"error\": \"");
            JsonStringEncoder.getInstance().quoteAsString(message, json);
            return new Reply(MediaType.APPLICATION_JSON, json.append("\"}").toString());
        }
        return new Reply(HtmlView.CONTENT_TYPE, HtmlView.indexPage(message,
                request.getParameter("q"), SearchQuery.excludesExpired(request.getParameter("exclude"))));
    }

    /**
     * Ответ фильтра. Фильтры пишут в поток руками: MVC до них не доходит, а значит и
     * привычного ResponseEntity у них нет.
     */
    static void write(HttpServletRequest request, HttpServletResponse response,
                      int status, String message) throws IOException {
        Reply reply = of(request, message);
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(reply.type().toString());
        response.getWriter().write(reply.body());
    }
}
