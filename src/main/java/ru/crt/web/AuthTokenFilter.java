package ru.crt.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

import org.jspecify.annotations.NonNull;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import ru.crt.config.AppProperties;

/**
 * Проверка статичного токена (после RateLimitFilter). По умолчанию закрыт только
 * {@code GET /api/logs}; при {@code ct.auth.protect-all} — весь сайт, этим живёт стенд.
 * Пустой сконфигурированный токен означает «закрыто»: 401 на всё.
 */
@Component
@Order(2)
public class AuthTokenFilter extends OncePerRequestFilter {

    private final AppProperties props;

    public AuthTokenFilter(AppProperties props) {
        this.props = props;
    }

    /**
     * При protect-all открытым остаётся единственный путь — {@link RequestPaths#STATUS}:
     * по нему стучится проверка живости из crtlogs-deploy. Если закрыть и его, неудачная
     * авторизация выглядела бы как «сервис не поднялся», и деплой откатывался бы вечно.
     * Данные там те же, что и на открытом проде, — прогресс синхронизации.
     */
    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        String path = RequestPaths.normalized(request);
        if (props.getAuth().isProtectAll()) {
            return RequestPaths.STATUS.equals(path);
        }
        return !RequestPaths.LOGS.equals(path);
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        if (isAuthorized(request)) {
            chain.doFilter(request, response);
            return;
        }
        // Basic — чтобы закрытый стенд можно было открыть браузером: он сам покажет
        // окно ввода, где логин любой, а пароль — тот же токен. Для API остаётся Bearer.
        response.setHeader("WWW-Authenticate", props.getAuth().isProtectAll()
                ? "Basic realm=\"crtlogs\", charset=\"UTF-8\""
                : "Bearer");
        // на стенде это видит человек (браузер покажет окно ввода, а после отмены —
        // тело ответа), на проде — клиент /api/logs; текст один и говорит, чего не хватает
        ErrorReply.write(request, response, HttpServletResponse.SC_UNAUTHORIZED,
                props.getAuth().isProtectAll()
                        ? "Стенд закрыт: логин любой, пароль — токен стенда."
                        : "Нужен токен: заголовок Authorization: Bearer <токен>.");
    }

    /** Пакет-private для unit-теста. */
    boolean isAuthorized(HttpServletRequest request) {
        String expected = props.getAuth().getToken();
        if (expected == null || expected.isEmpty()) {
            return false; // токен не задан => закрыто
        }
        String header = request.getHeader("Authorization");
        if (header == null) {
            return false;
        }
        String presented = presentedToken(header);
        return presented != null && MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8));
    }

    /** Токен из заголовка: {@code Bearer <токен>} либо Basic, где пароль — тот же токен. */
    private static String presentedToken(String header) {
        if (header.startsWith("Bearer ")) {
            return header.substring("Bearer ".length()).trim();
        }
        if (!header.startsWith("Basic ")) {
            return null;
        }
        String decoded;
        try {
            decoded = new String(Base64.getDecoder().decode(header.substring("Basic ".length()).trim()),
                    StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
        int colon = decoded.indexOf(':');
        return colon < 0 ? null : decoded.substring(colon + 1);
    }
}
