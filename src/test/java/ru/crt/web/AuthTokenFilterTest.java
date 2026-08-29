package ru.crt.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

class AuthTokenFilterTest {

    private AppProperties props;
    private AuthTokenFilter filter;

    @BeforeEach
    void setUp() {
        props = new AppProperties();
        props.getAuth().setToken("secret-token");
        filter = new AuthTokenFilter(props);
    }

    private MockHttpServletRequest logsRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/logs");
        request.setRemoteAddr("127.0.0.1");
        return request;
    }

    @Test
    void correctTokenPasses() throws ServletException, IOException {
        MockHttpServletRequest request = logsRequest();
        request.addHeader("Authorization", "Bearer secret-token");
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        assertEquals(200, response.getStatus());
        assertNotNull(chain.getRequest());
    }

    @Test
    void wrongTokenGives401WithWwwAuthenticate() throws ServletException, IOException {
        MockHttpServletRequest request = logsRequest();
        request.addHeader("Authorization", "Bearer wrong");
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        assertEquals(401, response.getStatus());
        assertEquals("Bearer", response.getHeader("WWW-Authenticate"));
        assertTrue(response.getContentAsString().contains("unauthorized"));
        assertNull(chain.getRequest());
    }

    @Test
    void missingTokenGives401() throws ServletException, IOException {
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(logsRequest(), response, chain);
        assertEquals(401, response.getStatus());
        assertNull(chain.getRequest());
    }

    @Test
    void emptyConfiguredTokenClosesEndpointCompletely() throws ServletException, IOException {
        props.getAuth().setToken("");
        MockHttpServletRequest request = logsRequest();
        request.addHeader("Authorization", "Bearer "); // пустой предъявленный токен
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        assertEquals(401, response.getStatus());
        assertNull(chain.getRequest());
    }

    @Test
    void onlyLogsEndpointIsProtected() {
        assertFalse(filter.shouldNotFilter(logsRequest()));
        MockHttpServletRequest certs = new MockHttpServletRequest("GET", "/api/certs/1");
        assertTrue(filter.shouldNotFilter(certs));
        MockHttpServletRequest status = new MockHttpServletRequest("GET", "/api/status");
        assertTrue(filter.shouldNotFilter(status));
        MockHttpServletRequest root = new MockHttpServletRequest("GET", "/");
        assertTrue(filter.shouldNotFilter(root));
    }
}
