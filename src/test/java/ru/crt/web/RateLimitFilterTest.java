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
        // старт с полным бакетом: 3 запроса подряд проходят
        assertTrue(filter.tryAcquire("1.1.1.1", t0));
        assertTrue(filter.tryAcquire("1.1.1.1", t0 + 1));
        assertTrue(filter.tryAcquire("1.1.1.1", t0 + 2));
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
        // исчерпываем burst первого IP
        assertTrue(filter.tryAcquire("1.1.1.1", t0));
        assertTrue(filter.tryAcquire("1.1.1.1", t0 + 1));
        assertTrue(filter.tryAcquire("1.1.1.1", t0 + 2));
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
            MockHttpServletRequest warm = new MockHttpServletRequest("GET", "/");
            warm.setParameter("q", "x");
            warm.setRemoteAddr("9.9.9.9");
            filter.doFilter(warm, new MockHttpServletResponse(), new MockFilterChain());
        }
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/");
        request.setParameter("q", "x");
        request.setRemoteAddr("9.9.9.9");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        assertEquals(429, response.getStatus());
        assertEquals("1", response.getHeader("Retry-After"));
        assertTrue(response.getContentAsString().contains("rate limit exceeded"));
        assertNull(chain.getRequest()); // до контроллера не дошло
    }

    /**
     * Caddy дописывает реальный IP в конец X-Forwarded-For, поэтому доверять можно
     * только последнему элементу: первый прислал сам клиент и им можно управлять.
     */
    @Test
    void forwardedForTakesTheHopAddedByOurProxy() {
        props.getRateLimit().setTrustForwarded(true);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/status");
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Forwarded-For", "1.2.3.4, 203.0.113.7");
        assertEquals("203.0.113.7", filter.clientIp(request));

        MockHttpServletRequest untrusted = new MockHttpServletRequest("GET", "/api/status");
        untrusted.setRemoteAddr("127.0.0.1");
        untrusted.addHeader("X-Forwarded-For", "1.2.3.4");
        props.getRateLimit().setTrustForwarded(false);
        assertEquals("127.0.0.1", filter.clientIp(untrusted), "без trust-forwarded заголовок игнорируется");
    }

    @Test
    void staticRootAndApiAreTreatedDifferently() {
        // "/" без параметров не лимитируется
        MockHttpServletRequest root = new MockHttpServletRequest("GET", "/");
        root.setRemoteAddr("8.8.8.8");
        assertTrue(filter.shouldNotFilter(root));
        // "/api/certs/1" лимитируется
        MockHttpServletRequest api = new MockHttpServletRequest("GET", "/api/certs/1");
        api.setRemoteAddr("8.8.8.8");
        assertFalse(filter.shouldNotFilter(api));
        // "/" с q= лимитируется
        MockHttpServletRequest search = new MockHttpServletRequest("GET", "/");
        search.setParameter("q", "x");
        search.setRemoteAddr("8.8.8.8");
        assertFalse(filter.shouldNotFilter(search));
    }
}
