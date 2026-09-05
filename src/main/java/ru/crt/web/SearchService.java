package ru.crt.web;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import ru.crt.db.CertRow;
import ru.crt.db.CertificateRepository;

/** Единственный вход в поиск: проверка запроса, счётчик, окно страницы и строки выдачи в контракте crt.sh. Отказ — ResponseStatusException: код 400 и текст — часть контракта. */
@Service
public class SearchService {

    /** Размер страницы результатов — общий для HTML и JSON, как у crt.sh. */
    public static final int PAGE_SIZE = 100;

    /** Сколько значащих символов обязан нести шаблон. */
    private static final int MIN_PATTERN_CHARS = 2;

    private final CertificateRepository repository;

    public SearchService(CertificateRepository repository) {
        this.repository = repository;
    }

    public SearchPage search(SearchQuery query) {
        String q = query.q();
        if (q.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Пустой запрос: введите домен целиком (example.ru) или шаблон (%.example.ru).");
        }
        if (isTooBroadcast(q)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Слишком общий шаблон: помимо подстановочных % и _ в запросе должно остаться"
                            + " не меньше " + MIN_PATTERN_CHARS
                            + " символов. Например: %vk% или %.example.ru.");
        }
        CertificateRepository.SearchResult found = repository.searchPage(
                q, query.excludeExpired(), query.sort(), query.descending(), query.page(), PAGE_SIZE);
        return new SearchPage(rows(repository.certRows(found.ids()), found.total()), found.total());
    }

    /** Шаблон с одним значащим символом или меньше ({@code %}, {@code %a%}) — «выдай базу целиком». */
    static boolean isTooBroadcast(String q) {
        return CertificateRepository.isWildcard(q)
                && q.replace("%", "").replace("_", "").length() < MIN_PATTERN_CHARS;
    }

    /** Строки БД -> строки выдачи; total кладётся в result_count каждой строки. */
    private static List<SearchRow> rows(List<CertRow> certs, int total) {
        return certs.stream()
                .map(c -> new SearchRow(
                        c.id(),
                        c.issuerCaId(),
                        c.issuerName(),
                        DisplayNames.commonName(c.subjectDn()),
                        DisplayNames.nameValue(DisplayNames.domains(c.domains())),
                        Timestamps.format(c.entryTs()),
                        Timestamps.format(c.notBefore()),
                        Timestamps.format(c.notAfter()),
                        c.serial(),
                        total))
                .toList();
    }
}
