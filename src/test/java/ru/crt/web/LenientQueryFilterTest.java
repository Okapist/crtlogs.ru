package ru.crt.web;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** Где фильтр вмешивается в разбор и что после этого видит следующее звено. */
class LenientQueryFilterTest {

    @Test
    void wrappedRequestAnswersThroughEveryAccessor() throws ServletException, IOException {
        HttpServletRequest wrapped = passThrough(request("q=%.com&output=json"));

        assertEquals("%.com", wrapped.getParameter("q"));
        assertArrayEquals(new String[]{"%.com"}, wrapped.getParameterValues("q"));
        assertEquals(List.of("q", "output"), List.copyOf(wrapped.getParameterMap().keySet()));
        assertEquals(List.of("q", "output"), Collections.list(wrapped.getParameterNames()));
        assertNull(wrapped.getParameter("id"));
        assertEquals("q=%.com&output=json", wrapped.getQueryString(), "исходная строка запроса не переписывается");
    }

    /** Ошибиться контейнер может только на '%' — остальное проходит нетронутым. */
    @Test
    void requestsThatCannotConfuseTheContainerArePassedThroughUntouched() throws ServletException, IOException {
        LenientQueryFilter filter = new LenientQueryFilter();

        MockHttpServletRequest plain = request("q=example.ru&output=json");
        assertSame(plain, passThrough(plain), "запрос без '%' до подмены не доходит");
        assertTrue(filter.shouldNotFilter(new MockHttpServletRequest("GET", "/")), "нет строки запроса");
        assertFalse(filter.shouldNotFilter(request("q=%.com")));
    }

    /** В стороне только тело-форма: её поля собрать нам нечем. */
    @Test
    void onlyFormBodiesAreLeftToWhoeverParsesThem() {
        LenientQueryFilter filter = new LenientQueryFilter();

        for (String method : new String[]{"POST", "PUT", "DELETE", "OPTIONS", "HEAD"}) {
            MockHttpServletRequest request = request("q=%.com");
            request.setMethod(method);
            assertFalse(filter.shouldNotFilter(request), method + " без тела-формы разбираем сами");
        }
        for (String method : new String[]{"POST", "PUT", "PATCH", "DELETE"}) {
            for (String contentType : new String[]{"application/x-www-form-urlencoded",
                    "APPLICATION/X-WWW-FORM-URLENCODED", "multipart/form-data; boundary=x"}) {
                assertTrue(filter.shouldNotFilter(formRequest(method, contentType, 12)),
                        method + " + " + contentType + " — параметры собирает не фильтр");
            }
        }
        assertFalse(filter.shouldNotFilter(formRequest("POST", "application/x-www-form-urlencoded", 0)),
                "заголовки о теле без самого тела полей не приносят");
    }

    private static MockHttpServletRequest formRequest(String method, String contentType, int length) {
        MockHttpServletRequest request = request("q=%.com");
        request.setMethod(method);
        request.setContentType(contentType);
        request.setContent(new byte[length]);
        return request;
    }

    @Test
    void errorDispatchIsCoveredToo() {
        assertFalse(new LenientQueryFilter().shouldNotFilterErrorDispatch());
    }

    private static MockHttpServletRequest request(String query) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/");
        request.setQueryString(query);
        return request;
    }

    private static HttpServletRequest passThrough(MockHttpServletRequest request)
            throws ServletException, IOException {
        MockFilterChain chain = new MockFilterChain();
        new LenientQueryFilter().doFilter(request, new MockHttpServletResponse(), chain);
        return (HttpServletRequest) chain.getRequest();
    }
}
