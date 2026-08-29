package ru.crt.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import ru.crt.config.AppProperties;

/**
 * Token bucket на IP: средний темп ct.rate-limit.rps, короткий burst ct.rate-limit.burst.
 * Лимитируются /api/** и поисковые/download-запросы crt.sh-контракта (/, параметры q/d/id);
 * статика без параметров не лимитируется.
 */
@Component
@Order(1)
public class RateLimitFilter extends OncePerRequestFilter {

    private final AppProperties props;
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    /** Неизменяемое состояние бакета заменяется целиком внутри compute() — потокобезопасно. */
    private record Bucket(double tokens, long lastAccessNanos) {
    }

    public RateLimitFilter(AppProperties props) {
        this.props = props;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!props.getRateLimit().isEnabled()) {
            return true;
        }
        String path = RequestPaths.normalized(request);
        if (path.startsWith("/api/")) {
            return false;
        }
        return !path.equals("/") || (request.getParameter("q") == null
                && request.getParameter("d") == null
                && request.getParameter("id") == null);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (tryAcquire(clientIp(request), System.nanoTime())) {
            chain.doFilter(request, response);
        } else {
            response.setStatus(429);
            response.setHeader("Retry-After", "1");
            writeJson(response, "{\"error\": \"rate limit exceeded\"}");
        }
    }

    private String clientIp(HttpServletRequest request) {
        if (props.getRateLimit().isTrustForwarded()) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                return forwarded.split(",")[0].trim();
            }
        }
        return request.getRemoteAddr();
    }

    /** Пакет-private для unit-теста с синтетическим временем. */
    boolean tryAcquire(String ip, long nowNanos) {
        double capacity = Math.max(1, props.getRateLimit().getBurst());
        double rate = props.getRateLimit().getRps();
        boolean[] allowed = {false};
        buckets.compute(ip, (key, bucket) -> {
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

    /** Чистка бакетов, не использовавшихся дольше минуты (вызов раз в 10 минут). */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 600_000)
    public void cleanupIdleBuckets() {
        // сравниваем разность, а не абсолютные значения: nanoTime переполняется,
        // и тогда «lastAccess < now - минута» выбросило бы либо всё, либо ничего
        long now = System.nanoTime();
        buckets.entrySet().removeIf(e -> now - e.getValue().lastAccessNanos() > 60_000_000_000L);
    }

    static void writeJson(HttpServletResponse response, String body) throws IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(body);
    }
}
