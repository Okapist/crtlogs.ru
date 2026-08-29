package ru.crt.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import ru.crt.config.AppProperties;

/**
 * Статичный Bearer-токен для GET /api/logs (после RateLimitFilter).
 * Пустой сконфигурированный токен означает «эндпоинт закрыт»: 401 на всё.
 */
@Component
@Order(2)
public class AuthTokenFilter extends OncePerRequestFilter {

    private static final String LOGS_PATH = "/api/logs";

    private final AppProperties props;

    public AuthTokenFilter(AppProperties props) {
        this.props = props;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !LOGS_PATH.equals(RequestPaths.normalized(request));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (isAuthorized(request)) {
            chain.doFilter(request, response);
        } else {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setHeader("WWW-Authenticate", "Bearer");
            RateLimitFilter.writeJson(response, "{\"error\": \"unauthorized\"}");
        }
    }

    /** Пакет-private для unit-теста. */
    boolean isAuthorized(HttpServletRequest request) {
        String expected = props.getAuth().getToken();
        if (expected == null || expected.isEmpty()) {
            return false; // токен не задан => эндпоинт закрыт
        }
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            return false;
        }
        String presented = header.substring("Bearer ".length()).trim();
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8));
    }
}
