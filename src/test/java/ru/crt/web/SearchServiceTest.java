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

/** Внешний вид выдачи и порядок обращений к хранилищу. */
class SearchServiceTest extends AbstractIntegrationTest {

    private SearchPage search(String q) {
        return searchService.search(SearchQuery.of(q, null, null, null, null));
    }

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

    /** Проверка стоит перед обращением к БД и одна на все входы. */
    @Test
    void blankAndTooBroadQueriesAreRejectedWith400() {
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> search("   ")).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> search("%")).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> search("%_%")).getStatusCode());
        // мягкий перенос nameprep удаляет: в SQL ушло бы %a% — вся база
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> search("%a\u00AD%")).getStatusCode());
    }

    @Test
    void tooBroadPatternBoundaries() {
        assertTrue(SearchService.isTooBroadcast("%"), "один процент — это вся база");
        assertTrue(SearchService.isTooBroadcast("%._"));
        assertTrue(SearchService.isTooBroadcast("%a%"), "одного значащего символа мало");
        assertTrue(SearchService.isTooBroadcast("%\\a%"), "\\ — экранирование LIKE, для H2 это %a%");
        assertFalse(SearchService.isTooBroadcast("%vk%"), "поиск по вхождению двухбуквенного имени");
        assertFalse(SearchService.isTooBroadcast("%ru"));
        assertFalse(SearchService.isTooBroadcast("%.example.ru"));
        assertFalse(SearchService.isTooBroadcast("a"), "не шаблон — длина не важна");
    }

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

    /** И запрос, и индекс сводятся к одной форме — в любой записи имени. */
    @Test
    void cyrillicNamesAreFoundWhicheverFormIsTyped() throws Exception {
        insertCyrillicCert();

        for (String q : List.of(TestFixtures.CYRILLIC_DOMAIN, TestFixtures.CYRILLIC_DOMAIN_ASCII,
                "Антоненков.РФ", "%.рф", "%.xn--p1ai", "%xn--80aeiunabfe8a%")) {
            assertEquals(1, search(q).total(), "не нашлось по запросу " + q);
        }
        assertEquals(0, search("%.ru").total(), "зона не путается с соседней");
    }

    /** Регрессия ради юникод-индекса: знак режет кириллический ярлык как латинский. */
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

    @Test
    void cyrillicNamesAreShownDecoded() throws Exception {
        insertCyrillicCert();

        SearchRow row = search("%.рф").rows().getFirst();
        assertEquals(TestFixtures.CYRILLIC_DOMAIN, row.nameValue(), "идентификаторы — расшифрованными");
        assertEquals(TestFixtures.CYRILLIC_DOMAIN, row.commonName(), "и CN тоже");
    }

    /** {@code xn--pple-43d} — аpple с кириллической «а»: показывается как записано. */
    @Test
    void namesThatMixCyrillicWithLatinAreShownAsTheyAreWritten() throws Exception {
        repository.insertCertificates(List.of(withDomains(fixtureCerts().getFirst(), 401, "f1",
                Set.of("нашажизнь.рф", "xn--pple-43d.com"))));

        assertEquals("xn--pple-43d.com\nнашажизнь.рф", search("%.xn--p1ai").rows().getFirst().nameValue(),
                "подделка остаётся punycode'ом, соседнее имя расшифровывается;"
                        + " порядок — по показанным именам, а не по хранимым");
    }
}
