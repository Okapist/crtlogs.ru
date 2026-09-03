package ru.crt.web;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Любой отказ MVC — с объяснением на понятном языке, а не кодом ответа в пустой
 * странице. Без этого Spring Boot отдаёт браузеру whitelabel-страницу, где нет ни
 * причины, ни способа продолжить: {@code reason} исключения Boot по умолчанию наружу
 * не выносит ({@code server.error.include-message=never}), а включать его глобально
 * значит показывать заодно и тексты внутренних сбоев.
 *
 * <p>Обработчики перечислены поимённо, а не одним {@code Exception}: тип исключения —
 * единственное, что отличает «сам виноват» (400/404) от «виноваты мы» (500), и
 * сваливать их в одну ветку значит либо врать посетителю про 500, либо молча съедать
 * настоящие сбои. Общий {@code Exception} внизу остаётся сеткой безопасности.
 *
 * <p>Формат ответа выбирает {@link ErrorReply} — тот же, которым отвечают фильтры.
 */
@ControllerAdvice
class ErrorAdvice {

    private static final Logger log = LoggerFactory.getLogger(ErrorAdvice.class);

    /** Наши собственные отказы: пустой и слишком общий запрос, ненайденный сертификат. */
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<String> rejected(ResponseStatusException failure, HttpServletRequest request) {
        return reply(request, failure.getStatusCode(),
                failure.getReason() == null ? "Запрос отклонён." : failure.getReason());
    }

    /**
     * {@code ?id=abc}, {@code ?p=x} — Spring не смог разобрать параметр. Стандартный
     * ответ на это — 400 без тела вообще, хотя чинится он одним словом в адресе.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<String> badParameter(MethodArgumentTypeMismatchException failure,
                                        HttpServletRequest request) {
        return reply(request, HttpStatus.BAD_REQUEST, "Параметр «" + failure.getName()
                + "» должен быть целым числом, а получено «" + failure.getValue() + "».");
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    ResponseEntity<String> missingParameter(MissingServletRequestParameterException failure,
                                            HttpServletRequest request) {
        return reply(request, HttpStatus.BAD_REQUEST,
                "В запросе не хватает параметра «" + failure.getParameterName() + "».");
    }

    /** Несуществующий путь: до контроллеров он не доходит, страницу рисуем сами. */
    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<String> notFound(NoResourceFoundException failure, HttpServletRequest request) {
        return reply(request, HttpStatus.NOT_FOUND,
                "Такой страницы нет. Поиск — на главной, описание API — в README.");
    }

    /**
     * Всё остальное. Текст ошибки посетителю не показываем — он про наши внутренности,
     * а не про его запрос, — но в журнал он попадает целиком: иначе сбой виден только
     * как «пятисотка» в чужом браузере.
     *
     * <p>Штатные отказы самого MVC (метод не поддерживается, тип не принимается) несут
     * свой код в {@link ErrorResponse} — отдаём его, а не 500: 500 означал бы, что
     * сломались мы, и попал бы в мониторинг ошибок.
     */
    @ExceptionHandler(Exception.class)
    ResponseEntity<String> unexpected(Exception failure, HttpServletRequest request) {
        if (failure instanceof ErrorResponse known && known.getStatusCode().is4xxClientError()) {
            // detail заполняет сам MVC («Method 'POST' is not supported»); он может быть
            // и пустым, поэтому фраза целиком из него не собирается
            String detail = known.getBody().getDetail();
            return reply(request, known.getStatusCode(),
                    detail == null ? "Запрос отклонён." : "Запрос отклонён: " + detail);
        }
        log.error("Необработанная ошибка на {}", RequestPaths.normalized(request), failure);
        return reply(request, HttpStatus.INTERNAL_SERVER_ERROR,
                "Внутренняя ошибка сервиса. Она уже записана в журнал — попробуйте повторить запрос позже.");
    }

    private static ResponseEntity<String> reply(HttpServletRequest request, HttpStatusCode status,
                                                String message) {
        ErrorReply.Reply reply = ErrorReply.of(request, message);
        return ResponseEntity.status(status).contentType(reply.type()).body(reply.body());
    }
}
