package ru.crt.web;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Нормализация пути запроса. По спецификации контейнер отдаёт getRequestURI() как есть,
 * поэтому сравнение сырой строки с {@code /api/logs} обходится кодированием, matrix-
 * параметрами и лишними слэшами. Что именно из этого нельзя пропустить мимо токена,
 * проверяет AuthTokenFilterTest; здесь — сама нормализация.
 */
class RequestPathsTest {

    private static String normalized(String uri) {
        return RequestPaths.normalized(new MockHttpServletRequest("GET", uri));
    }

    @Test
    void collapsesEmptySegmentsAndTrailingSlash() {
        assertEquals("/api/logs", normalized("//api//logs/"));
        assertEquals("/", normalized("/"));
        assertEquals("/", normalized(""), "пустой URI — это корень, а не пустая строка");
    }

    @Test
    void dropsMatrixParametersFromEverySegment() {
        assertEquals("/a/b", normalized("/a;v=1/b;x=2;y=3"));
        assertEquals("/a", normalized("/a;"));
    }

    @Test
    void decodesPercentEscapes() {
        assertEquals("/а/б", normalized("/%D0%B0/%D0%B1"), "кириллица в пути — обычное дело");
        assertEquals("/a+b", normalized("/a+b"), "'+' в пути значит сам себя, а не пробел");
        assertEquals("/a+b c", normalized("/a+b%20c"),
                "'+' остаётся собой и рядом с escape'ами, а не только без них");
    }

    /** Битую %-последовательность разбираем как одиночный '%' — тем же правилом, что и строку запроса. */
    @Test
    void brokenPercentSequenceMeansALiteralPercent() {
        assertEquals("/a%zz", normalized("/a%zz"));
        assertEquals("/lo%gs.", normalized("/lo%gs%2E"), "соседние настоящие escape'ы разбираются");
    }

    /** Spring MVC маршрутизирует по пути без контекста — фильтры должны видеть его же. */
    @Test
    void contextPathIsNotPartOfTheMatchedPath() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/testing/api/logs");
        request.setContextPath("/testing");
        assertEquals("/api/logs", RequestPaths.normalized(request));
    }
}
