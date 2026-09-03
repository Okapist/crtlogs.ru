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
 * {@code ?q=%.com} и {@code ?q=%.рф} на живом Jetty — то, ради чего написан
 * {@link LenientQueryFilter}.
 *
 * <p>Единственный тест проекта с настоящим сервером и своим контекстом, и это
 * вынужденно: проверять тут нечего, кроме поведения контейнера. MockMvc строку запроса
 * не разбирает вовсе (параметры ему передают уже готовыми), поэтому на нём такой адрес
 * выглядит здоровым и до починки. Запросы уходят голым сокетом по той же причине, по
 * которой и появилась проблема: {@code java.net.URI} — а с ним и HttpClient — считает
 * одиночный '%' в адресе ошибкой и до сервера бы не дошёл.
 *
 * <p>Случай с кириллицей здесь не для полноты: он единственный отличает наш разбор от
 * послаблений самого Jetty, поэтому именно он ловит соблазн выкинуть фильтр и включить
 * {@code UriCompliance} одной строкой — см. javadoc {@link LenientQueryFilter}.
 *
 * <p>Лимит частоты включён, в отличие от остальных тестов: он читает параметры запроса
 * первым, и на нём же ломался прод. Так проверяется заодно и порядок фильтров — с
 * неверным {@code @Order} до подмены дело не дошло бы. Burst поднят, чтобы тест не
 * зависел от того, сколько запросов он успевает сделать за секунду.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"ct.rate-limit.enabled=true", "ct.rate-limit.burst=100",
                // своя база: контекст здесь второй, а h2:mem:testdb остальных тестов
                // никто за собой не чистит — «в базе пусто» тогда зависело бы от того,
                // что оставил после себя предыдущий класс
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

    /**
     * Незакодированный '%' доезжает до поиска как символ шаблона: страница выдачи
     * повторяет запрос в блоке Criteria, по нему и видно, что дошло.
     */
    @Test
    void unencodedWildcardReachesTheSearchInsteadOfBeingRejected() throws Exception {
        String response = get("/?q=%.com");

        assertEquals("200", status(response));
        assertTrue(response.contains("Search: '%.com'"), "запрос должен дойти до поиска целиком");

        // так этот адрес и выглядит в браузере: кириллицу он кодирует, а '%' — нет
        assertTrue(get("/?q=%.%D1%80%D1%84").contains("Search: '%.рф'"));
    }

    /**
     * Ссылки выдачи (сортировка, страницы, JSON) собираются читаемыми — с тем же
     * одиночным '%', на котором всё и ломалось. Проверяется не их вид, а работа: ссылка
     * берётся прямо из ответа и по ней делается второй запрос, как это сделал бы
     * посетитель, нажавший на заголовок колонки.
     */
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

    /** Остальные параметры при разборе не теряются — иначе JSON-клиент получил бы страницу. */
    @Test
    void otherParametersSurviveTheParsing() throws Exception {
        String response = get("/?q=%.com&output=json");

        assertEquals("200", status(response));
        assertTrue(response.contains("Content-Type: application/json"), response);
        assertEquals("[]", body(response), "база у этого класса своя и пуста — но это выдача");
    }

    /**
     * Метод сам по себе к разбору строки запроса отношения не имеет. Пока фильтр
     * пропускал всё, кроме GET и HEAD, эти запросы отвечали пятисоткой и писали в журнал
     * «Необработанная ошибка» — то есть ровно то, ради устранения чего он написан.
     */
    @Test
    void requestsThatAreNotGetAreParsedTheSameWay() throws Exception {
        // коды точные, а не «лишь бы не 500»: контейнерная четырёхсотка без объяснения —
        // это тот же отказ, от которого фильтр избавляет, и «не 500» её пропускает
        assertEquals("405", status(raw("POST /?q=%.com HTTP/1.1")), "POST'ов у нас нет");
        assertEquals("200", status(raw("OPTIONS /?q=%.com HTTP/1.1")), "OPTIONS отвечает MVC");
        String missing = raw("DELETE /nope?x=50% HTTP/1.1");
        assertEquals("404", status(missing));
        assertTrue(missing.contains("Такой страницы нет"), "и это наш ответ, а не контейнера");
    }

    /**
     * Тело формы бывает только у POST и только когда оно непустое — заголовки о нём
     * параметров не приносят. Отбор по одному типу содержимого возвращал бы поломку
     * целиком: {@code ?q=%.com} с {@code Content-Type: application/x-www-form-urlencoded}
     * снова отвечал бы контейнерным 400.
     */
    @Test
    void formHeadersWithoutABodyDoNotDisableTheParsing() throws Exception {
        String get = raw("GET /?q=%.com HTTP/1.1", "Content-Type: application/x-www-form-urlencoded");
        assertEquals("200", status(get), get);
        assertTrue(get.contains("Search: '%.com'"), get);

        assertEquals("405", status(raw("POST /?q=%.com HTTP/1.1",
                "Content-Type: application/x-www-form-urlencoded")), "пустое тело полей не несёт");
    }

    /**
     * А то, что не чинится разбором, объясняется словами: '%' в одиночку — это «выдай
     * базу целиком», и отказ на него наш, с текстом и формой, а не «400 Bad Request»
     * контейнера в пустой странице.
     */
    @Test
    void aQueryThatIsStillWrongGetsOurOwnExplanation() throws Exception {
        String response = get("/?q=%");

        assertEquals("400", status(response));
        assertTrue(response.contains("Слишком общий шаблон"), response);
        assertTrue(response.contains("<FORM id=\"searchForm\""), "форму с запросом видно и на отказе");
    }

    /**
     * Адрес в том виде, в каком его отправит браузер, увидев в разметке ссылку: кодирует
     * он только то, чему в строке запроса не место, — байты вне ASCII. Одиночный '%'
     * так и уходит на сервер, поэтому {@code ?q=%.рф} со страницы превращается ровно в
     * {@code ?q=%.%D1%80%D1%84} — тот самый адрес, с которого всё началось.
     */
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

    /**
     * Код ответа из статусной строки «HTTP/1.1 200 OK». Пустой ответ (сервер закрыл
     * соединение, не ответив) — это тоже результат теста, поэтому вместо
     * ArrayIndexOutOfBounds отдаём сам ответ: сломавшему фильтр он нужнее.
     */
    private static String status(String response) {
        String[] statusLine = response.split(" ", 3);
        return statusLine.length < 2 ? "нет статусной строки: " + response : statusLine[1];
    }

    /** Тело ответа — всё после пустой строки, отделяющей его от заголовков. */
    private static String body(String response) {
        int blankLine = response.indexOf("\r\n\r\n");
        return blankLine < 0 ? "" : response.substring(blankLine + 4);
    }

    /**
     * Запрос голым сокетом: цель уходит на сервер ровно так, как написана, — за
     * единственным исключением, которое делает и браузер (см. {@link #asBrowserSends}).
     */
    private String get(String target) throws Exception {
        return raw("GET " + target + " HTTP/1.1");
    }

    /** То же произвольной стартовой строкой и заголовками — для методов, кроме GET. */
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
            // сервер, принявший соединение и не ответивший, — это тоже отказ, который
            // тест обязан показать; без таймаута он вместо этого повесил бы сборку
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
