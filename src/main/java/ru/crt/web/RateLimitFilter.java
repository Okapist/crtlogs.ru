package ru.crt.web;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;

import org.jspecify.annotations.NonNull;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import ru.crt.config.AppProperties;

/**
 * Token bucket на IP: средний темп ct.rate-limit.rps, burst ct.rate-limit.burst.
 * Лимитируются /api/** и поисковые/download-запросы crt.sh-контракта; статика и
 * /api/status — нет.
 */
@Component
@Order(1)
public class RateLimitFilter extends OncePerRequestFilter {

    /** Параметры, по которым корень отличается от статики: crt.sh-контракт поиска. */
    private static final String[] DATA_PARAMS = {"q", "d", "id"};

    private final AppProperties props;

    /** Простаивающие бакеты выселяет Caffeine — иначе карта росла бы всё время работы. */
    private final Cache<String, Bucket> buckets = Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofMinutes(1))
            .build();

    /** Неизменяемое состояние бакета заменяется целиком внутри compute() — потокобезопасно. */
    private record Bucket(double tokens, long lastAccessNanos) {
    }

    public RateLimitFilter(AppProperties props) {
        this.props = props;
    }

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        if (!props.getRateLimit().isEnabled()) {
            return true;
        }
        String path = RequestPaths.normalized(request);
        // виджет опрашивает /api/status сам с каждой страницы — без исключения обычное
        // хождение по сайту упиралось бы в лимит
        if (RequestPaths.STATUS.equals(path)) {
            return true;
        }
        if (path.startsWith("/api/")) {
            return false;
        }
        if (!path.equals("/")) {
            return true; // не наш маршрут — это статика
        }
        for (String param : DATA_PARAMS) {
            if (request.getParameter(param) != null) {
                return false; // /?q=, /?d=, /?id= — запрос к данным
            }
        }
        return true;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        if (tryAcquire(clientIp(request), System.nanoTime())) {
            chain.doFilter(request, response);
        } else {
            response.setHeader("Retry-After", "1");
            ErrorReply.write(request, response, 429,
                    "Слишком много запросов: подождите секунду и повторите.");
        }
    }

    /**
     * При trust-forwarded клиентский IP — ПОСЛЕДНИЙ элемент X-Forwarded-For: его
     * проставил ближайший прокси, то есть наш. Первый небезопасен — его присылает
     * сам клиент, и лимит обходился бы произвольным заголовком.
     */
    String clientIp(HttpServletRequest request) {
        if (props.getRateLimit().isTrustForwarded()) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                String[] hops = forwarded.split(",");
                String last = hops[hops.length - 1].trim();
                if (!last.isEmpty()) {
                    return last;
                }
            }
        }
        return request.getRemoteAddr();
    }

    /** Пакет-private для unit-теста с синтетическим временем. */
    boolean tryAcquire(String ip, long nowNanos) {
        double capacity = Math.max(1, props.getRateLimit().getBurst());
        double rate = props.getRateLimit().getRps();
        boolean[] allowed = {false};
        buckets.asMap().compute(ip, (_, bucket) -> {
            double tokens = bucket == null ? capacity : Math.min(capacity,
                    bucket.tokens() + (nowNanos - bucket.lastAccessNanos()) / 1_000_000_000.0 * rate);
            if (tokens >= 1.0) {
                allowed[0] = true;
                return new Bucket(tokens - 1.0, nowNanos);
            }
            return new Bucket(tokens, nowNanos);
        });
        return allowed[0];
    }
}
