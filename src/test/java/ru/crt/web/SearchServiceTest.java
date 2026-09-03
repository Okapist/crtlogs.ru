package ru.crt.web;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import ru.crt.AbstractIntegrationTest;
import ru.crt.TestFixtures;
import ru.crt.db.SortColumn;

import static org.junit.jupiter.api.Assertions.*;

/** Внешний вид выдачи и порядок обращений к хранилищу: счётчик, окно страницы, строки. */
class SearchServiceTest extends AbstractIntegrationTest {

    private SearchPage search(String q) {
        return searchService.search(SearchQuery.of(q, null, null, null, null));
    }

    /** Строка выдачи в контракте crt.sh: CN, идентификаторы, даты и общее число совпадений. */
    @Test
    void rowCarriesCrtShFieldsBuiltFromTheStoredCertificate() throws Exception {
        insertFixture();

        SearchPage page = search(FIXTURE_DOMAIN);
        assertEquals(1, page.total());
        assertEquals(1, page.totalPages());
        SearchRow row = page.rows().getFirst();
        assertNotNull(row.issuerCaId(), "id издателя — из ca");
        assertEquals("CN=TCI ECDSA B1 16-280", row.issuerName(), "issuer_name — это DN издателя целиком");
        assertEquals("cateam.ru", row.commonName(), "CN достаётся из subject DN");
        assertEquals("cateam.ru\nwww.cateam.ru", row.nameValue(),
                "идентификаторы — из cert_domain, через '\\n' и по алфавиту");
        assertEquals("2026-01-19T10:26:30", row.notBefore(), "даты — ISO без зоны, как у crt.sh");
        assertEquals("2026-01-19T10:45:44.153", row.entryTimestamp(), "миллисекунды сохраняются");
        assertEquals(1, row.resultCount(), "result_count — общее число совпадений, а не размер страницы");
    }

    /** Умолчания crt.sh: Logged At по убыванию, первая страница, истёкшие показываем. */
    @Test
    void defaultsMatchCrtShWhenParametersAreAbsent() {
        SearchQuery query = SearchQuery.of("example.ru", null, null, null, null);
        assertEquals(SortColumn.LOGGED_AT, query.sort());
        assertTrue(query.descending());
        assertEquals(1, query.page());
        assertFalse(query.excludeExpired());

        assertEquals(1, SearchQuery.of("q", null, null, null, 0).page(), "нулевая страница — первая");
        assertEquals(1, SearchQuery.of("q", null, null, null, -5).page(), "и отрицательная тоже");
        assertEquals(SortColumn.NOT_AFTER, SearchQuery.of("q", null, 4, null, null).sort());
        assertFalse(SearchQuery.of("q", null, null, "asc", null).descending());
        assertTrue(SearchQuery.of("q", "expired", null, null, null).excludeExpired());
    }

    /**
     * Проверка запроса стоит перед обращением к БД и одна на все входы: раньше она
     * жила в обработчике, и второй вход в поиск дошёл бы до полного сканирования
     * cert_domain молча.
     */
    @Test
    void blankAndTooBroadQueriesAreRejectedWith400() {
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> search("   ")).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> search("%")).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> search("%_%")).getStatusCode());
    }

    /** Границы той же проверки: что считается «слишком общим», а что — нет. */
    @Test
    void tooBroadPatternBoundaries() {
        assertTrue(SearchService.isTooBroadcast("%"), "один процент — это вся база");
        assertTrue(SearchService.isTooBroadcast("%._"));
        assertTrue(SearchService.isTooBroadcast("%a%"), "одного значащего символа мало");
        assertFalse(SearchService.isTooBroadcast("%vk%"), "поиск по вхождению двухбуквенного имени");
        assertFalse(SearchService.isTooBroadcast("%ru"));
        assertFalse(SearchService.isTooBroadcast("%.example.ru"));
        assertFalse(SearchService.isTooBroadcast("a"), "не шаблон — длина не важна");
    }

    /** Смещение зажимается по числу совпадений, иначе OFFSET уезжал бы за конец выборки. */
    @Test
    void pageBeyondTheLastOneIsEmptyButStillReportsTheTotal() throws Exception {
        insertFixture();

        SearchPage beyond = searchService.search(SearchQuery.of(FIXTURE_DOMAIN, null, null, null, 2));
        assertTrue(beyond.rows().isEmpty());
        assertEquals(1, beyond.total(), "общее число совпадений не зависит от номера страницы");
        assertEquals(1, beyond.totalPages());
    }

    @Test
    void nothingFoundIsAnEmptyPageRatherThanAnError() {
        SearchPage page = search("nothing.example");
        assertTrue(page.rows().isEmpty());
        assertEquals(0, page.total());
        assertEquals(0, page.totalPages(), "пейджер не показывается вовсе");
    }

    // --- кириллические домены ---

    /**
     * Кириллическое имя приезжает в логи в двух формах, и набрать его посетитель может
     * тоже в любой: и запрос, и индекс сводятся к одной ({@code DomainName}).
     */
    @Test
    void cyrillicNamesAreFoundWhicheverFormIsTyped() throws Exception {
        insertCyrillicCert();

        for (String q : List.of(TestFixtures.CYRILLIC_DOMAIN, TestFixtures.CYRILLIC_DOMAIN_ASCII,
                "Антоненков.РФ", "%.рф", "%.xn--p1ai", "%xn--80aeiunabfe8a%")) {
            assertEquals(1, search(q).total(), "не нашлось по запросу " + q);
        }
        assertEquals(0, search("%.ru").total(), "зона не путается с соседней");
    }

    /**
     * Регрессия, ради которой индекс и держит расшифрованные имена: подстановочный знак
     * обязан резать кириллический ярлык так же, как латинский (на punycode-индексе это
     * было невозможно, см. {@code DomainNameTest}). Здесь то же свойство сквозь БД.
     * Домены кладутся сразу в индексной форме — в ней их отдаёт {@code CertificateNames}.
     */
    @Test
    void aWildcardCutsACyrillicLabelJustLikeALatinOne() throws Exception {
        repository.insertCertificates(List.of(withDomains(fixtureCerts().getFirst(), 402, "f2",
                Set.of("достроят.рф", "корп.домрфбанк.рф"))));

        assertEquals(1, search("%троят.рф").total(), "знак режет ярлык");
        assertEquals(1, search("%домрфбанк.рф").total(), "знак сбоку — тоже находит");
        assertEquals(1, search("%.домрфбанк.рф").total(), "и привычная форма crt.sh тоже");
        assertEquals(0, search("%троят.ru").total(), "зона не путается с соседней");
        assertEquals("достроят.рф\nкорп.домрфбанк.рф",
                search("%троят.рф").rows().getFirst().nameValue());
    }

    /**
     * Показывается расшифровка, а не то, что лежит в индексе: {@code xn--80aeiunabfe8a}
     * читателю не говорит ничего.
     */
    @Test
    void cyrillicNamesAreShownDecoded() throws Exception {
        insertCyrillicCert();

        SearchRow row = search("%.рф").rows().getFirst();
        assertEquals(TestFixtures.CYRILLIC_DOMAIN, row.nameValue(), "идентификаторы — расшифрованными");
        assertEquals(TestFixtures.CYRILLIC_DOMAIN, row.commonName(), "и CN тоже");
    }

    /**
     * Расшифровка не безусловна: {@code xn--pple-43d} — это {@code аpple} с кириллической
     * «а», от {@code apple} на глаз неотличимое. Такое имя показывается как записано.
     * Домены кладутся напрямую — сертификата с подделкой в фикстурах нет.
     */
    @Test
    void namesThatMixCyrillicWithLatinAreShownAsTheyAreWritten() throws Exception {
        repository.insertCertificates(List.of(withDomains(fixtureCerts().getFirst(), 401, "f1",
                Set.of("нашажизнь.рф", "xn--pple-43d.com"))));

        assertEquals("xn--pple-43d.com\nнашажизнь.рф", search("%.xn--p1ai").rows().getFirst().nameValue(),
                "подделка остаётся punycode'ом, соседнее имя расшифровывается;"
                        + " порядок — по показанным именам, а не по хранимым");
    }
}
