package ru.crt.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import tools.jackson.core.io.JsonStringEncoder;

/** Отказ в том же виде, в каком клиент просил данные: {@code {"error": "..."}} тем, кто пришёл за JSON, и главная с плашкой — браузеру. Место одно на всех. */
final class ErrorReply {

    private ErrorReply() {
    }

    /** Тип и тело ответа: фильтр пишет их в поток, {@link ErrorAdvice} заворачивает в ResponseEntity. */
    record Reply(MediaType type, String body) {
    }

    /** Клиент пришёл за JSON: явный {@code output=json} либо любой эндпоинт /api/**. */
    static boolean wantsJson(HttpServletRequest request) {
        return "json".equals(request.getParameter("output"))
                || RequestPaths.isApi(RequestPaths.normalized(request));
    }

    /** Страница отказа — та же главная, в форме которой остался исходный запрос и фильтр. */
    static Reply of(HttpServletRequest request, String message) {
        if (wantsJson(request)) {
            // текст может нести данные запроса — экранирует Jackson, а не замена двух символов
            StringBuilder json = new StringBuilder("{\"error\": \"");
            JsonStringEncoder.getInstance().quoteAsString(message, json);
            return new Reply(MediaType.APPLICATION_JSON, json.append("\"}").toString());
        }
        return new Reply(HtmlView.CONTENT_TYPE, HtmlView.indexPage(message,
                request.getParameter("q"), SearchQuery.excludesExpired(request.getParameter("exclude"))));
    }

    /** Ответ фильтра: MVC до него не доходит, пишем в поток руками. */
    static void write(HttpServletRequest request, HttpServletResponse response,
                      int status, String message) throws IOException {
        Reply reply = of(request, message);
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(reply.type().toString());
        response.getWriter().write(reply.body());
    }
}
