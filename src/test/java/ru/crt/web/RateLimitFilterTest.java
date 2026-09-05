package ru.crt.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.servlet.ServletException;
import java.io.IOException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import ru.crt.config.AppProperties;

/** Token bucket: burst исчерпывается, токен восстанавливается через секунду, IP независимы. */
class RateLimitFilterTest {

    private AppProperties props;
    private RateLimitFilter filter;

    @BeforeEach
    void setUp() {
        props = new AppProperties();
        props.getRateLimit().setEnabled(true);
        props.getRateLimit().setRps(1.0);
        props.getRateLimit().setBurst(3);
        filter = new RateLimitFilter(props);
    }

    @Test
    void bucketBurstsThenRefillsAfterOneSecond() {
        long t0 = 10_000_000_000L;
        drainBurst("1.1.1.1", t0); // старт с полным бакетом
        // burst исчерпан
        assertFalse(filter.tryAcquire("1.1.1.1", t0 + 3));
        assertFalse(filter.tryAcquire("1.1.1.1", t0 + 4));
        // через ~1.1 c накапливается один токен
        assertTrue(filter.tryAcquire("1.1.1.1", t0 + 1_100_000_003L));
        assertFalse(filter.tryAcquire("1.1.1.1", t0 + 1_100_000_004L));
    }

    @Test
    void differentIpsAreIndependent() {
        long t0 = 10_000_000_000L;
        drainBurst("1.1.1.1", t0);
        assertFalse(filter.tryAcquire("1.1.1.1", t0 + 3));
        // у второго IP свой полный бакет
        assertTrue(filter.tryAcquire("2.2.2.2", t0));
        assertFalse(filter.tryAcquire("1.1.1.1", t0 + 4));
    }

    @Test
    void burstOneGivesStrictOnePerSecond() {
        props.getRateLimit().setBurst(1);
        long t0 = 10_000_000_000L;
        assertTrue(filter.tryAcquire("3.3.3.3", t0));
        assertFalse(filter.tryAcquire("3.3.3.3", t0 + 1));
        assertTrue(filter.tryAcquire("3.3.3.3", t0 + 1_000_000_000L));
    }

    @Test
    void requestRateLimitedWith429AndRetryAfter() throws ServletException, IOException {
        // исчерпываем burst тремя запросами
        for (int i = 0; i < 3; i++) {
            filter.doFilter(searchRequest("9.9.9.9"), new MockHttpServletResponse(), new MockFilterChain());
        }
        MockHttpServletRequest request = searchRequest("9.9.9.9");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        assertEquals(429, response.getStatus());
        assertEquals("1", response.getHeader("Retry-After"));
        // в лимит упирается живой посетитель — ответ ему страница
        assertTrue(response.getContentType().startsWith("text/html"));
        assertTrue(response.getContentAsString().contains("Слишком много запросов"));
        assertNull(chain.getRequest()); // до контроллера не дошло
    }

    /** Клиентский — последний элемент X-Forwarded-For: его проставил наш прокси; первым лимит обходился бы. */
    @Test
    void forwardedForTakesTheHopAddedByOurProxy() {
        props.getRateLimit().setTrustForwarded(true);
        MockHttpServletRequest request = request("/api/certs/1");
        request.addHeader("X-Forwarded-For", "1.2.3.4, 203.0.113.7");
        assertEquals("203.0.113.7", filter.clientIp(request));

        MockHttpServletRequest untrusted = request("/api/certs/1");
        untrusted.addHeader("X-Forwarded-For", "1.2.3.4");
        props.getRateLimit().setTrustForwarded(false);
        assertEquals("127.0.0.1", filter.clientIp(untrusted), "без trust-forwarded заголовок игнорируется");
    }

    @Test
    void onlyApiAndSearchLikeRequestsAreLimited() {
        assertTrue(filter.shouldNotFilter(request("/")), "страница без параметров — это статика");
        assertTrue(filter.shouldNotFilter(request("/site.css")));
        assertTrue(filter.shouldNotFilter(request("/logs-status.js")));
        assertFalse(filter.shouldNotFilter(request("/api/certs/1")));
        assertFalse(filter.shouldNotFilter(request("/api/logs")));
        for (String param : new String[]{"q", "d", "id"}) {
            MockHttpServletRequest search = request("/");
            search.setParameter(param, "x");
            assertFalse(filter.shouldNotFilter(search), "/?" + param + "= — это запрос к данным");
        }
    }

    @Test
    void disabledLimiterLetsEverythingThrough() {
        props.getRateLimit().setEnabled(false);
        MockHttpServletRequest search = request("/");
        search.setParameter("q", "x");
        assertTrue(filter.shouldNotFilter(search));
        assertTrue(filter.shouldNotFilter(request("/api/certs/1")));
    }

    /** Виджет опрашивает /api/status сам — хождение по сайту не должно упираться в лимит. */
    @Test
    void publicStatusIsNotLimitedBecauseThePagesPollItThemselves() {
        assertTrue(filter.shouldNotFilter(request("/api/status")));
        assertTrue(filter.shouldNotFilter(request("/api/status/")), "нормализованный путь — тот же");
    }

    @Test
    void emptyForwardedHopFallsBackToTheSocketAddress() {
        props.getRateLimit().setTrustForwarded(true);
        MockHttpServletRequest request = request("/api/certs/1");
        request.addHeader("X-Forwarded-For", "1.2.3.4, ");
        assertEquals("127.0.0.1", filter.clientIp(request));

        MockHttpServletRequest blank = request("/api/status");
        blank.addHeader("X-Forwarded-For", "   ");
        assertEquals("127.0.0.1", filter.clientIp(blank));
    }

    private void drainBurst(String ip, long nowNanos) {
        for (int i = 0; i < props.getRateLimit().getBurst(); i++) {
            assertTrue(filter.tryAcquire(ip, nowNanos + i), "burst должен быть полным на старте");
        }
    }

    private static MockHttpServletRequest request(String uri) {
        return request(uri, "127.0.0.1");
    }

    private static MockHttpServletRequest request(String uri, String remoteAddr) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setRemoteAddr(remoteAddr);
        return request;
    }

    private static MockHttpServletRequest searchRequest(String remoteAddr) {
        MockHttpServletRequest request = request("/", remoteAddr);
        request.setParameter("q", "x");
        return request;
    }
}
