package ru.crt.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import ru.crt.client.CtLogClient;

/**
 * {@code ?q=%.com} и {@code ?q=%.рф} на живом Jetty — то, ради чего написан LenientQueryFilter.
 * MockMvc строку запроса не разбирает, а HttpClient '%' не пропустит — поэтому настоящий сервер и голый сокет; кириллический случай отличает нас от UriCompliance Jetty.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"ct.rate-limit.enabled=true", "ct.rate-limit.burst=100",
                // контекст здесь второй, общую h2:testdb никто за собой не чистит
                "spring.datasource.url=jdbc:h2:mem:malformed-query;DB_CLOSE_DELAY=-1"})
class MalformedQueryHttpTest {

    private static final HexFormat HEX = HexFormat.of().withUpperCase();

    /** Синк поднимается вместе с контекстом — живой клиент ходил бы в реальные CT-логи. */
    @MockitoBean
    private CtLogClient ctLogClient;

    @LocalServerPort
    private int port;

    @BeforeEach
    void stubLogList() throws Exception {
        when(ctLogClient.fetchLogList(anyString())).thenReturn(List.of());
    }

    @Test
    void unencodedWildcardReachesTheSearchInsteadOfBeingRejected() throws Exception {
        String response = get("/?q=%.com");

        assertEquals("200", status(response));
        assertTrue(response.contains("Search: '%.com'"), "запрос должен дойти до поиска целиком");

        // так этот адрес выглядит в браузере: кириллицу он кодирует, а '%' — нет
        assertTrue(get("/?q=%.%D1%80%D1%84").contains("Search: '%.рф'"));
    }

    /** Ссылка берётся из ответа и по ней делается второй запрос — как нажал бы посетитель. */
    @Test
    void linksFromTheResultsPageLeadBackToTheSameSearch() throws Exception {
        Matcher link = Pattern.compile("href=\"(\\?q=[^\"]*&amp;sort=0)\"")
                .matcher(get("/?q=%.%D1%80%D1%84"));

        assertTrue(link.find(), "на выдаче должна быть ссылка сортировки по ID");
        String href = link.group(1).replace("&amp;", "&");
        assertEquals("?q=%.рф&sort=0", href, "ссылка читается так же, как набранный запрос");

        String followed = get("/" + href);
        assertEquals("200", status(followed));
        assertTrue(followed.contains("Search: '%.рф'"), followed);
    }

    @Test
    void otherParametersSurviveTheParsing() throws Exception {
        String response = get("/?q=%.com&output=json");

        assertEquals("200", status(response));
        assertTrue(response.contains("Content-Type: application/json"), response);
        assertEquals("[]", body(response), "база у этого класса своя и пуста — но это выдача");
    }

    /** Метод сам по себе к разбору отношения не имеет — раньше эти запросы отвечали пятисоткой. */
    @Test
    void requestsThatAreNotGetAreParsedTheSameWay() throws Exception {
        assertEquals("405", status(raw("POST /?q=%.com HTTP/1.1")), "POST'ов у нас нет");
        assertEquals("200", status(raw("OPTIONS /?q=%.com HTTP/1.1")), "OPTIONS отвечает MVC");
        String missing = raw("DELETE /nope?x=50% HTTP/1.1");
        assertEquals("404", status(missing));
        assertTrue(missing.contains("Такой страницы нет"), "и это наш ответ, а не контейнера");
    }

    /** Заголовки о теле полей не приносят — отбор по одному Content-Type возвращал бы поломку. */
    @Test
    void formHeadersWithoutABodyDoNotDisableTheParsing() throws Exception {
        String get = raw("GET /?q=%.com HTTP/1.1", "Content-Type: application/x-www-form-urlencoded");
        assertEquals("200", status(get), get);
        assertTrue(get.contains("Search: '%.com'"), get);

        assertEquals("405", status(raw("POST /?q=%.com HTTP/1.1",
                "Content-Type: application/x-www-form-urlencoded")), "пустое тело полей не несёт");
    }

    @Test
    void aQueryThatIsStillWrongGetsOurOwnExplanation() throws Exception {
        String response = get("/?q=%");

        assertEquals("400", status(response));
        assertTrue(response.contains("Слишком общий шаблон"), response);
        assertTrue(response.contains("<FORM id=\"searchForm\""), "форму с запросом видно и на отказе");
    }

    /** Адрес в том виде, в каком его отправит браузер: кодирует только байты вне ASCII. */
    private static String asBrowserSends(String target) {
        StringBuilder out = new StringBuilder(target.length() + 8);
        for (byte b : target.getBytes(StandardCharsets.UTF_8)) {
            if ((b & 0xFF) < 0x80) {
                out.append((char) b);
            } else {
                out.append('%').append(HEX.toHexDigits(b));
            }
        }
        return out.toString();
    }

    /** Код ответа из статусной строки; пустой ответ — тоже результат теста. */
    private static String status(String response) {
        String[] statusLine = response.split(" ", 3);
        return statusLine.length < 2 ? "нет статусной строки: " + response : statusLine[1];
    }

    private static String body(String response) {
        int blankLine = response.indexOf("\r\n\r\n");
        return blankLine < 0 ? "" : response.substring(blankLine + 4);
    }

    private String get(String target) throws Exception {
        return raw("GET " + target + " HTTP/1.1");
    }

    private String raw(String requestLine, String... headers) throws Exception {
        String[] parts = requestLine.split(" ", 3);
        StringBuilder request = new StringBuilder(parts[0]).append(' ')
                .append(asBrowserSends(parts[1])).append(' ').append(parts[2])
                .append("\r\nHost: localhost\r\nContent-Length: 0\r\n");
        for (String header : headers) {
            request.append(header).append("\r\n");
        }
        request.append("Connection: close\r\n\r\n");
        try (Socket socket = new Socket("localhost", port)) {
            // без таймаута повисший сервер повесил бы сборку
            socket.setSoTimeout(10_000);
            OutputStream out = socket.getOutputStream();
            out.write(request.toString().getBytes(StandardCharsets.US_ASCII));
            out.flush();
            ByteArrayOutputStream received = new ByteArrayOutputStream();
            InputStream in = socket.getInputStream();
            byte[] chunk = new byte[8192];
            for (int read = in.read(chunk); read > 0; read = in.read(chunk)) {
                received.write(chunk, 0, read);
            }
            return received.toString(StandardCharsets.UTF_8);
        }
    }
}
