package ru.crt.web;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import ru.crt.db.CertRow;
import ru.crt.db.CertificateRepository;

/**
 * Единственный вход в поиск: проверка запроса, счётчик совпадений, окно страницы и
 * сборка строк выдачи в контракте crt.sh. Проверка «слишком общий шаблон» стоит здесь,
 * а не в обработчике, чтобы второй вход в поиск не дошёл до полного сканирования
 * cert_domain молча.
 *
 * <p>Отказ отдаётся {@link ResponseStatusException}: сервис живёт в веб-слое, а код
 * 400 и текст ошибки — часть контракта поиска, а не деталь обработчика.
 */
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
        // тексты пишутся посетителю, а не в лог: их показывает страница отказа
        // (HtmlView.indexPage) и повторяет JSON-ответ, поэтому каждый называет и
        // причину, и годный пример запроса
        if (q.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Пустой запрос: введите домен целиком (example.ru) или шаблон (%.example.ru).");
        }
        if (isTooBroadcast(q)) {
            // порог берём у самой проверки: литералом здесь он разъехался бы с ней
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Слишком общий шаблон: помимо подстановочных % и _ в запросе должно остаться"
                            + " не меньше " + MIN_PATTERN_CHARS
                            + " символов. Например: %vk% или %.example.ru.");
        }
        CertificateRepository.SearchResult found = repository.searchPage(
                q, query.excludeExpired(), query.sort(), query.descending(), query.page(), PAGE_SIZE);
        return new SearchPage(rows(repository.certRows(found.ids()), found.total()), found.total());
    }

    /**
     * Шаблон, в котором не осталось ничего, по чему искать: {@code %}, {@code %_},
     * {@code %a%} — это запрос «выдай базу целиком». Пакет-private для unit-теста.
     */
    static boolean isTooBroadcast(String q) {
        return CertificateRepository.isWildcard(q)
                && q.replace("%", "").replace("_", "").length() < MIN_PATTERN_CHARS;
    }

    /**
     * Строки БД -> строки выдачи: имена в форме показа ({@link DisplayNames}), даты во
     * внешнем формате. Общее число совпадений кладётся в result_count каждой строки —
     * так его видят клиенты crt.sh.
     */
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
