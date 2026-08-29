package ru.crt.web;

import java.nio.charset.StandardCharsets;
import java.util.List;
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
 */
@RestController
public class ApiController {

    private static final MediaType PEM = new MediaType("application", "x-pem-file");

    private final CertificateRepository repository;

    public ApiController(CertificateRepository repository) {
        this.repository = repository;
    }

    /** Корень без поисковых параметров — наш UI (иначе params-условия выше дают 400). */
    @GetMapping(value = "/", params = {"!q", "!d", "!id"})
    public org.springframework.web.servlet.ModelAndView index() {
        return new org.springframework.web.servlet.ModelAndView("forward:/index.html");
    }

    /** Размер страницы результатов — пейджинг в HTML и JSON (см. {@link #search}). */
    static final int PAGE_SIZE = 100;

    /**
     * Поиск как у crt.sh: по умолчанию HTML-страница с таблицей результатов,
     * JSON — только с {@code output=json}. Сортировка — {@code sort} (0 = ID,
     * 1 = Logged At, 2 = Not Before, 3 = Issuer Name, 4 = Not After) и
     * {@code dir=asc|desc}; по умолчанию Logged At по убыванию. Пейджинг —
     * {@code p} (с единицы), по {@value #PAGE_SIZE} строк; {@code result_count}
     * в JSON — общее число совпадений.
     */
    @GetMapping(value = "/", params = {"q", "!d", "!id"})
    public Object search(@RequestParam String q,
                         @RequestParam(required = false) String output,
                         @RequestParam(required = false) String exclude,
                         @RequestParam(required = false) Integer sort,
                         @RequestParam(required = false) String dir,
                         @RequestParam(required = false) Integer p) {
        if (q.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "пустой запрос q");
        }
        boolean excludeExpired = "expired".equals(exclude);
        int sortIdx = sort != null && sort >= 0 && sort <= 4 ? sort : 1;
        boolean descending = !"asc".equalsIgnoreCase(dir);
        int page = p != null && p >= 1 ? p : 1;
        List<Long> ids = repository.searchIds(q, excludeExpired, sortIdx, descending);
        int total = ids.size();
        int from = (int) Math.min((long) (page - 1) * PAGE_SIZE, total);
        int to = Math.min(from + PAGE_SIZE, total);
        List<SearchRow> rows = repository.searchRows(ids.subList(from, to), total);
        if ("json".equals(output)) {
            return rows;
        }
        int totalPages = (total + PAGE_SIZE - 1) / PAGE_SIZE;
        return ResponseEntity.ok()
                .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                .body(HtmlView.resultsPage(q, excludeExpired, rows, sortIdx, descending, page, totalPages));
    }

    /** Скачивание сертификата в PEM, как {@code /?d=<id>} у crt.sh. */
    @GetMapping(value = "/", params = {"d", "!q", "!id"})
    public ResponseEntity<byte[]> download(@RequestParam long d) {
        CertDetails details = repository.getCertDetails(d);
        if (details == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "сертификат " + d + " не найден");
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + details.sha256() + ".crt\"")
                .contentType(PEM)
                .body(details.pem().getBytes(StandardCharsets.US_ASCII));
    }

    /** Страница сертификата — HTML с таблицей полей и PEM, как {@code /?id=<id>} у crt.sh. */
    @GetMapping(value = "/", params = {"id", "!q", "!d"})
    public ResponseEntity<String> certPage(@RequestParam long id) {
        CertDetails details = repository.getCertDetails(id);
        if (details == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "сертификат " + id + " не найден");
        }
        return ResponseEntity.ok()
                .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                .body(HtmlView.certPage(details));
    }

    /** Наши расширения: JSON-детали для UI. */
    @GetMapping("/api/certs/{id}")
    public CertDetails certDetails(@PathVariable long id) {
        CertDetails details = repository.getCertDetails(id);
        if (details == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "сертификат " + id + " не найден");
        }
        return details;
    }

    /** Наши расширения: статус логов и синка (закрыт токеном, см. AuthTokenFilter). */
    @GetMapping("/api/logs")
    public List<LogStatus> logs() {
        return repository.getLogStatuses();
    }

    /**
     * Публичный статус синка без токена: сводка по логам без текстов ошибок —
     * для блока под кнопкой поиска. Полные детали — {@code /api/logs} под токеном.
     */
    @GetMapping("/api/status")
    public List<LogPublicStatus> publicStatus() {
        return repository.getLogStatuses().stream()
                .map(s -> new LogPublicStatus(s.url(), s.description(), s.operator(),
                        s.last_synced_index(), s.tree_size(), s.last_error() == null,
                        s.updated_at()))
                .toList();
    }
}
