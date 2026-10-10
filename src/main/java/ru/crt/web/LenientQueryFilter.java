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

/** Разбирает строку запроса сам, если в ней есть '%': одиночный процент здесь значит сам себя, а не начало escape-последовательности. Правила — в {@link QueryString}; настройкой Jetty (UriCompliance) это не решается. */
@Component
@Order(0)
public class LenientQueryFilter extends OncePerRequestFilter {

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        String query = request.getQueryString();
        return query == null || QueryString.sameAsContainer(query) || hasFormBody(request);
    }

    /** Тело-формы разбирает контейнер, и собрать такой же набор параметров нам нечем — оставляем запрос ему. Решаем по самому телу, а не по заголовкам: поля формы появляются, только когда тело непустое. */
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

    /** Методы, у которых к параметрам добавляются поля формы, — такие запросы фильтр не трогает. */
    private static final Set<String> FORM_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    /** ERROR-диспетч тоже наш: там параметры читает страница ошибки. */
    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        chain.doFilter(new LenientQueryRequest(request, QueryString.parse(request.getQueryString())), response);
    }

    /** Запрос с нашими параметрами вместо контейнерных; переопределены все четыре метода доступа. */
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
