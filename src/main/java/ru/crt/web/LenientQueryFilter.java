package ru.crt.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.NonNull;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Разбирает строку запроса сам, если в ней есть '%': одиночный процент здесь значит
 * сам себя, а не начало escape-последовательности. Правила разбора — в
 * {@link QueryString}, здесь только решение, где их применить.
 *
 * <p>Шаблон поиска у нас — это LIKE, то есть '%' в запросе законен, и подсказка под
 * формой сама предлагает {@code %.example.ru}. Но в адресе '%' начинает escape, и для
 * контейнера {@code ?q=%.com} — битая строка запроса: Jetty бросает «400: Bad query» из
 * первого же обращения к параметрам, а посетитель получал 400 без объяснения или 500.
 * Набравший адрес руками этого не избежит, а браузер кодирует только то, что отправлено
 * формой. Ссылки выдачи с тех пор собираются с '%' как есть ({@link QueryString#encode}).
 *
 * <p>Настройкой контейнера это не решается: с {@code UriCompliance.Violation.BAD_PERCENT_ENCODING}
 * Jetty перестаёт отвечать 400, но, восстановившись после одиночного '%', переносит
 * следующие два символа как есть — и {@code ?q=%.%D1%80%D1%84} (набранный руками
 * {@code ?q=%.рф}) разбирается в «%.%D1» с испорченным хвостом. Ни одна комбинация
 * послаблений UriCompliance этого не меняет (проверено на {@code UrlEncoded.decodeUtf8To}),
 * поэтому {@code MalformedQueryHttpTest} держит тест именно с кириллицей.
 *
 * <p>Фильтр стоит первым (см. {@link Order}): {@link RateLimitFilter} и всё, что за ним,
 * читают параметры. Отбор по методу здесь не годится: параметры читает и страница
 * ошибки ({@link ErrorReply#wantsJson}), так что {@code POST /?q=%.com} без подмены
 * падал пятисоткой; в стороне остаются только запросы с телом-формой, см. {@link #hasFormBody}.
 */
@Component
@Order(0)
public class LenientQueryFilter extends OncePerRequestFilter {

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        String query = request.getQueryString();
        return query == null || QueryString.sameAsContainer(query) || hasFormBody(request);
    }

    /**
     * Единственный случай, когда параметров больше, чем в строке запроса: тело формы,
     * поданное POST'ом. Разбирает его контейнер, и собрать такой же набор нам нечем —
     * поэтому подмену тут не делаем и оставляем запрос ему. Ни один наш эндпоинт таких
     * запросов не принимает.
     *
     * <p>Спрашивается именно тело, а не заголовки о нём: поля формы попадают в параметры
     * только когда тело непустое. Отбор по одному типу содержимого означал бы, что
     * {@code GET /?q=%.com} с заголовком
     * {@code Content-Type: application/x-www-form-urlencoded} и без всякого тела проходит
     * мимо подмены и снова отвечает 400, — то есть один заголовок возвращал бы ровно ту
     * поломку, ради которой фильтр написан. Длина 0 значит «тела нет», -1 — «неизвестна»
     * (chunked), и вот в этом случае поля быть могут.
     */
    private static boolean hasFormBody(HttpServletRequest request) {
        String contentType = request.getContentType();
        if (contentType == null || !FORM_METHODS.contains(request.getMethod())
                || request.getContentLengthLong() == 0) {
            return false;
        }
        String type = contentType.toLowerCase(Locale.ROOT);
        return type.startsWith("application/x-www-form-urlencoded")
                || type.startsWith("multipart/form-data");
    }

    /**
     * Методы, у которых к параметрам добавляются поля формы. POST разбирает сам
     * контейнер; PUT, PATCH и DELETE — {@code OrderedFormContentFilter} Spring'а, и он
     * стоит на -9900, то есть раньше нас. Ограничившись POST'ом, мы забирали бы у него
     * разобранное тело: наша обёртка собрана из одной строки запроса.
     */
    private static final Set<String> FORM_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    /**
     * ERROR-диспетч тоже наш: {@link OncePerRequestFilter} по умолчанию его пропускает,
     * а это ровно тот проход, где параметры читает страница ошибки. Пропустив его, мы
     * возвращали бы разбор контейнеру именно там, где уже что-то пошло не так.
     */
    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        chain.doFilter(new LenientQueryRequest(request, QueryString.parse(request.getQueryString())), response);
    }

    /**
     * Запрос с нашими параметрами вместо контейнерных. Переопределены все четыре метода
     * доступа: Spring добирается до значений то одним, то другим (условия
     * {@code params} у @GetMapping — через getParameterMap, @RequestParam — через
     * getParameterValues), и оставленный без подмены отдавал бы пустоту.
     */
    private static final class LenientQueryRequest extends HttpServletRequestWrapper {

        private final Map<String, String[]> parameters;

        LenientQueryRequest(HttpServletRequest request, Map<String, String[]> parameters) {
            super(request);
            this.parameters = parameters;
        }

        @Override
        public String getParameter(String name) {
            String[] values = parameters.get(name);
            return values == null ? null : values[0];
        }

        /**
         * Массивы и карта отдаются без копий, как и у контейнеров: getParameterMap()
         * зовут на каждое условие {@code params} каждого маршрута — с десяток раз за
         * запрос, и всё это до того, как лимитер спишет токен.
         */
        @Override
        public String[] getParameterValues(String name) {
            return parameters.get(name);
        }

        @Override
        public Enumeration<String> getParameterNames() {
            return Collections.enumeration(parameters.keySet());
        }

        @Override
        public Map<String, String[]> getParameterMap() {
            return parameters;
        }
    }
}
