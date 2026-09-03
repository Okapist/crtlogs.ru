package ru.crt.web;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import ru.crt.db.CertificateRepository;

/**
 * REST API. Основные эндпоинты повторяют контракт crt.sh, чтобы работали
 * привычные клиенты: {@code /?q=...&output=json}, {@code /?d=<id>}, {@code /?id=<id>}.
 *
 * <p>Здесь только HTTP: разбор параметров, коды ответов и выбор представления.
 * Порядок вызовов поиска держит {@link SearchService}, сборку деталей сертификата —
 * {@link CertDetailsService}; репозиторий остаётся напрямую только у статуса логов,
 * где ответ — построчная проекция ct_log без всякой оркестрации.
 */
@RestController
public class ApiController {

    private static final MediaType PEM = new MediaType("application", "x-pem-file");

    private final SearchService search;
    private final CertDetailsService certificates;
    private final CertificateRepository repository;

    public ApiController(SearchService search, CertDetailsService certificates,
                         CertificateRepository repository) {
        this.search = search;
        this.certificates = certificates;
        this.repository = repository;
    }

    private static ResponseEntity<String> html(String body) {
        return ResponseEntity.ok().contentType(HtmlView.CONTENT_TYPE).body(body);
    }

    /** Корень без поисковых параметров — наш UI (иначе params-условия выше дают 400). */
    @GetMapping(value = "/", params = {"!q", "!d", "!id"})
    public ResponseEntity<String> index() {
        return html(HtmlView.indexPage());
    }

    /**
     * Поиск как у crt.sh: сортировка {@code sort} (0 = ID, 1 = Logged At, 2 = Not Before,
     * 3 = Issuer Name, 4 = Not After) и {@code dir=asc|desc}, по умолчанию Logged At по
     * убыванию; пейджинг — {@code p} (с единицы). Умолчания и проверки запроса —
     * в {@link SearchQuery} и {@link SearchService}.
     *
     * <p>JSON и HTML — разные обработчики, а не один с {@code Object} на выходе: у метода
     * с двумя контрактами сигнатура не говорила, чем он отвечает, и Spring не мог
     * применить к нему обычное преобразование по типу.
     */
    @GetMapping(value = "/", params = {"q", "output=json", "!d", "!id"})
    public List<SearchRow> searchJson(@RequestParam String q,
                                      @RequestParam(required = false) String exclude,
                                      @RequestParam(required = false) Integer sort,
                                      @RequestParam(required = false) String dir,
                                      @RequestParam(required = false) Integer p) {
        return search.search(SearchQuery.of(q, exclude, sort, dir, p)).rows();
    }

    /** Та же выдача страницей результатов — ответ по умолчанию, как у crt.sh. */
    @GetMapping(value = "/", params = {"q", "output!=json", "!d", "!id"})
    public ResponseEntity<String> searchHtml(@RequestParam String q,
                                             @RequestParam(required = false) String exclude,
                                             @RequestParam(required = false) Integer sort,
                                             @RequestParam(required = false) String dir,
                                             @RequestParam(required = false) Integer p) {
        SearchQuery query = SearchQuery.of(q, exclude, sort, dir, p);
        return html(HtmlView.resultsPage(query, search.search(query)));
    }

    /** Ответ на несуществующий id — один на три эндпоинта сертификата. */
    private static <T> T require(T found, long id) {
        if (found == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "сертификат " + id + " не найден");
        }
        return found;
    }

    private CertDetails require(long id) {
        return require(certificates.details(id), id);
    }

    /**
     * Скачивание сертификата в PEM, как {@code /?d=<id>} у crt.sh. Полные детали здесь
     * не собираются: дамп и вхождения в логи файлу не нужны.
     */
    @GetMapping(value = "/", params = {"d", "!q", "!id"})
    public ResponseEntity<byte[]> download(@RequestParam long d) {
        CertDetailsService.PemFile file = require(certificates.pem(d), d);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + file.sha256() + ".crt\"")
                .contentType(PEM)
                .body(file.pem().getBytes(StandardCharsets.US_ASCII));
    }

    /** Страница сертификата — HTML с таблицей полей и PEM, как {@code /?id=<id>} у crt.sh. */
    @GetMapping(value = "/", params = {"id", "!q", "!d"})
    public ResponseEntity<String> certPage(@RequestParam long id) {
        return html(HtmlView.certPage(require(id)));
    }

    /** Наши расширения: JSON-детали для UI. */
    @GetMapping("/api/certs/{id}")
    public CertDetails certDetails(@PathVariable long id) {
        return require(id);
    }

    /** Наши расширения: статус логов и синка (закрыт токеном, см. AuthTokenFilter). */
    @GetMapping("/api/logs")
    public List<LogStatus> logs() {
        return repository.logRows().stream().map(LogStatus::of).toList();
    }

    /**
     * Публичный статус синка без токена: сводка по логам без текстов ошибок —
     * для блока под кнопкой поиска. Полные детали — {@code /api/logs} под токеном.
     *
     * <p>Ответ кешируемый: виджет опрашивает его раз в 30 секунд с каждой открытой
     * страницы, а данные меняются не чаще, чем идёт синк. Лимит частоты на этот путь
     * не распространяется — почему, см. {@code RateLimitFilter.shouldNotFilter}.
     */
    @GetMapping("/api/status")
    public ResponseEntity<List<LogPublicStatus>> publicStatus() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofSeconds(15)).cachePublic())
                .body(repository.logRows().stream().map(LogPublicStatus::of).toList());
    }
}
