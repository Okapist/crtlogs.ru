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
 * Token bucket на IP: средний темп ct.rate-limit.rps, короткий burst ct.rate-limit.burst.
 * Лимитируются /api/** и поисковые/download-запросы crt.sh-контракта (/, параметры q/d/id);
 * статика без параметров и публичный статус синка не лимитируются.
 */
@Component
@Order(1)
public class RateLimitFilter extends OncePerRequestFilter {

    /**
     * Параметры запроса, по которым корень отличается от статики: это crt.sh-контракт
     * поиска и скачивания. Списком, а не тремя проверками подряд, — тот же список
     * перебирает закрепляющий это правило тест.
     */
    private static final String[] DATA_PARAMS = {"q", "d", "id"};

    private final AppProperties props;

    /**
     * Бакеты по одному на IP. Простаивающие выселяет сам Caffeine: без этого карта росла
     * бы всё время работы демона — за сутки это все IP, которые к нам приходили.
     */
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
        // Виджет статуса висит на каждой странице и опрашивает /api/status сам, поэтому
        // в общем бюджете обычное поведение посетителя упиралось в лимит: загрузка
        // главной, поиск и открытие сертификата — это уже четыре запроса при burst=3.
        // По цене эндпоинт сопоставим со статикой (несколько строк ct_log), и ответ
        // кешируется на 15 секунд — см. ApiController.publicStatus
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
            // 429 числом: в jakarta.servlet константы кончаются на кодах HTTP/1.1
            ErrorReply.write(request, response, 429,
                    "Слишком много запросов: подождите секунду и повторите.");
        }
    }

    /**
     * IP клиента — и единственное место, где записан инвариант X-Forwarded-For;
     * остальные файлы на него ссылаются, а не пересказывают его своими словами.
     *
     * <p>При trust-forwarded берём ПОСЛЕДНИЙ элемент заголовка. Последний элемент
     * всегда проставлен ближайшим прокси, то есть нашим: что бы ни прислал клиент,
     * его значения оказываются левее. Поэтому правило одинаково верно и когда прокси
     * перезаписывает заголовок целиком (так настроен наш Caddy), и когда дописывает
     * себя в конец. Небезопасен ровно противоположный выбор — первый элемент: его
     * присылает сам клиент, и лимит по IP обходился бы произвольным заголовком.
     *
     * <p>Предполагается ровно один доверенный прокси непосредственно перед приложением
     * (см. deploy/Caddyfile); при двух вложенных прокси последним окажется внутренний,
     * и лимит станет общим — тогда правило придётся пересматривать.
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
