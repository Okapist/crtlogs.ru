package ru.crt.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.servlet.ServletException;
import java.io.IOException;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import ru.crt.config.AppProperties;

class AuthTokenFilterTest {

    private AppProperties props;
    private AuthTokenFilter filter;
    /** Цепочка последнего {@link #run}: пустая, если фильтр запрос не пропустил. */
    private MockFilterChain chain;

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

    private MockHttpServletRequest logsRequest(String authorization) {
        MockHttpServletRequest request = logsRequest();
        request.addHeader("Authorization", authorization);
        return request;
    }

    /** Прогоняет запрос через фильтр; дошёл ли он до цепочки, говорит {@link #passed}. */
    private MockHttpServletResponse run(MockHttpServletRequest request) throws ServletException, IOException {
        chain = new MockFilterChain();
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        return response;
    }

    private boolean passed() {
        return chain.getRequest() != null;
    }

    @Test
    void correctTokenPasses() throws ServletException, IOException {
        assertEquals(200, run(logsRequest("Bearer secret-token")).getStatus());
        assertTrue(passed());
    }

    @Test
    void wrongTokenGives401WithWwwAuthenticate() throws ServletException, IOException {
        MockHttpServletResponse response = run(logsRequest("Bearer wrong"));
        assertEquals(401, response.getStatus());
        assertEquals("Bearer", response.getHeader("WWW-Authenticate"));
        assertTrue(response.getContentType().startsWith("application/json"));
        assertTrue(response.getContentAsString().contains("Authorization: Bearer"));
        assertFalse(passed());
    }

    @Test
    void missingTokenGives401() throws ServletException, IOException {
        assertEquals(401, run(logsRequest()).getStatus());
        assertFalse(passed());
    }

    @Test
    void emptyConfiguredTokenClosesEndpointCompletely() throws ServletException, IOException {
        props.getAuth().setToken("");
        assertEquals(401, run(logsRequest("Bearer ")).getStatus(), "пустой предъявленный токен");
        assertFalse(passed());
    }

    @Test
    void onlyLogsEndpointIsProtected() {
        assertFalse(filter.shouldNotFilter(logsRequest()));
        for (String uri : new String[]{"/api/certs/1", "/api/status", "/"}) {
            assertTrue(filter.shouldNotFilter(new MockHttpServletRequest("GET", uri)), uri);
        }
    }

    /** Стенд закрыт целиком: тем же токеном, но открытым остаётся только /api/status. */
    @Test
    void protectAllClosesWholeSiteExceptHealthCheck() throws ServletException, IOException {
        props.getAuth().setProtectAll(true);
        for (String uri : new String[]{"/", "/api/certs/1", "/api/logs"}) {
            assertFalse(filter.shouldNotFilter(new MockHttpServletRequest("GET", uri)),
                    uri + " на стенде должен требовать токен");
        }
        assertTrue(filter.shouldNotFilter(new MockHttpServletRequest("GET", "/api/status")),
                "проверка живости из crtlogs-deploy обязана проходить без токена");

        MockHttpServletResponse response = run(new MockHttpServletRequest("GET", "/"));
        assertEquals(401, response.getStatus());
        assertTrue(response.getHeader("WWW-Authenticate").startsWith("Basic "),
                "браузер должен показать окно ввода, а не молча получить 401");
        assertFalse(passed());
    }

    @Test
    void basicCredentialsWithTokenAsPasswordAreAccepted() {
        props.getAuth().setProtectAll(true);
        assertTrue(filter.isAuthorized(logsRequest("Basic "
                + Base64.getEncoder().encodeToString(":secret-token".getBytes()))));
        assertFalse(filter.isAuthorized(logsRequest("Basic "
                + Base64.getEncoder().encodeToString("user:wrong".getBytes()))));
    }

    /** Заголовок присылает клиент: ответ — 401, а не 500 из разбора base64. */
    @Test
    void malformedAuthorizationHeadersAreRejectedWithoutFailing() {
        for (String header : new String[]{
                "Basic !!!не-base64!!!",          // не декодируется
                "Basic " + Base64.getEncoder().encodeToString("no-colon".getBytes()),
                "Token secret-token",             // чужая схема
                "bearer secret-token",            // схема регистрозависима, как в RFC
                "Bearer",
                ""}) {
            assertFalse(filter.isAuthorized(logsRequest(header)), "заголовок «" + header + "» не должен пускать");
        }
    }

    @Test
    void bearerTokenIsTrimmed() {
        assertTrue(filter.isAuthorized(logsRequest("Bearer   secret-token  ")));
    }

    /** Сырой requestURI пропускал бы кодированные и matrix-формы мимо токена. */
    @Test
    void encodedAndMatrixFormsOfLogsPathStayProtected() {
        for (String uri : new String[]{"/api/log%73", "/api/logs;v=1", "//api/logs", "/api/logs/"}) {
            assertFalse(filter.shouldNotFilter(new MockHttpServletRequest("GET", uri)),
                    uri + " должен требовать токен");
        }
    }
}
