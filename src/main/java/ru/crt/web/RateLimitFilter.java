package ru.crt.web;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.InetAddress;
import java.time.Duration;
import java.util.HexFormat;

import org.jspecify.annotations.NonNull;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import ru.crt.config.AppProperties;

/** Token bucket на IP: средний темп ct.rate-limit.rps, burst ct.rate-limit.burst. Лимитируются /api/** и запросы crt.sh-контракта (q/d/id); статика и /api/status — нет. */
@Component
@Order(1)
public class RateLimitFilter extends OncePerRequestFilter {

    /** Параметры crt.sh-контракта поиска: по ним корень отличается от статики. */
    private static final String[] DATA_PARAMS = {"q", "d", "id"};

    private final AppProperties props;

    /** Простаивающие бакеты выселяет Caffeine — иначе карта росла бы всё время работы; потолок — на случай потока запросов с разных адресов. */
    private final Cache<String, Bucket> buckets = Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofMinutes(1))
            .maximumSize(100_000)
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
        // /api/status не лимитируем: виджет опрашивает его сам с каждой страницы
        if (RequestPaths.STATUS.equals(path)) {
            return true;
        }
        if (RequestPaths.isApi(path)) {
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
        if (tryAcquire(bucketKey(clientIp(request)), System.nanoTime())) {
            chain.doFilter(request, response);
        } else {
            response.setHeader("Retry-After", "1");
            ErrorReply.write(request, response, 429,
                    "Слишком много запросов: подождите секунду и повторите.");
        }
    }

    /** При trust-forwarded клиентский IP — ПОСЛЕДНИЙ элемент X-Forwarded-For: его проставил наш прокси. Первый небезопасен — его присылает сам клиент. */
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

    /** Ключ бакета: IPv4 — сам адрес, IPv6 — вся /64: клиенту обычно выдана именно она, и лимит на один из её 2^64 адресов — не лимит. */
    static String bucketKey(String ip) {
        if (ip.indexOf(':') < 0) {
            return ip;
        }
        try {
            byte[] address = InetAddress.ofLiteral(ip).getAddress();
            if (address.length == 16) {
                return HexFormat.of().formatHex(address, 0, 8) + "/64";
            }
        } catch (IllegalArgumentException e) {
            // не литерал адреса — лимитируем как есть, лишь бы не ходить в DNS
        }
        return ip;
    }

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
