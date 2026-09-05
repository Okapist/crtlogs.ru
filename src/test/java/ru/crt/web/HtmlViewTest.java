package ru.crt.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static ru.crt.TestFixtures.certDetails;
import static ru.crt.TestFixtures.searchRow;

import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import ru.crt.TestFixtures;
import ru.crt.db.SortColumn;

/** Серверный рендер страниц: разметка crt.sh, экранирование и пейджер. */
class HtmlViewTest {

    /** Страница результатов; число страниц задаётся через total. */
    private static String results(String q, boolean excludeExpired, SortColumn sort,
                                  boolean descending, int page, int totalPages, List<SearchRow> rows) {
        return HtmlView.resultsPage(new SearchQuery(q, excludeExpired, sort, descending, page),
                new SearchPage(rows, totalPages * SearchService.PAGE_SIZE));
    }

    /** Страница результатов без строк: у пейджера остаются только номер и общее число. */
    private static String emptyResults(int page, int totalPages) {
        return results("q", false, SortColumn.LOGGED_AT, true, page, totalPages, List.of());
    }

    /** esc() — защита от XSS во всех подстановках; кириллица проходит как есть. */
    @Test
    void escEscapesMetacharactersAndKeepsCyrillic() {
        assertEquals("&amp;&lt;&gt;&quot;&#39;", HtmlView.esc("&<>\"'"));
        assertEquals("АО «Тест», Москва", HtmlView.esc("АО «Тест», Москва"));
        assertEquals("", HtmlView.esc(null));
    }

    // --- главная ---

    @Test
    void indexPageCarriesTheSearchFormAndTheSharedFrame() {
        String html = HtmlView.indexPage();
        assertTrue(html.contains("<FORM id=\"searchForm\" action=\"./\" method=\"get\">"),
                "обычный GET-submit: без JS запрос отправляет сама форма");
        assertTrue(html.contains("name=\"q\""));
        assertTrue(html.contains("value=\"expired\""), "чекбокс «только действующие»");
        assertTrue(html.contains("<DIV id=\"logs\"></DIV>"), "место под виджет статуса логов");
        assertTrue(html.contains("class=\"copyright\""));
        assertTrue(html.contains("<SCRIPT src=\"metrika.js\"></SCRIPT>"));
        assertFalse(html.contains("readable-url.js"), "на пустой главной переписывать нечего");
    }

    @Test
    void theReadableUrlScriptExistsInStatic() {
        assertNotNull(HtmlView.class.getResource("/static/readable-url.js"),
                "разметка ссылается на файл, которого нет в static");
    }

    @Test
    void theReadableUrlCarriesEveryNonDefaultParameterAndNothingElse() {
        assertEquals("?q=%.рф",
                readableUrl(results("%.рф", false, SortColumn.LOGGED_AT, true, 1, 1, List.of())),
                "поиск с умолчаниями — только ?q=, и '%' в нём не закодирован");
        assertEquals("?q=%.рф&amp;exclude=expired&amp;sort=2&amp;dir=asc&amp;p=3",
                readableUrl(results("%.рф", true, SortColumn.NOT_BEFORE, false, 3, 5, List.of())));
        assertEquals("?q=q&amp;p=2", readableUrl(emptyResults(2, 3)), "страница — тоже часть поиска");
    }

    /** На странице отказа адрес не переписывается. */
    @Test
    void theErrorPageLeavesTheAddressAlone() {
        assertFalse(HtmlView.indexPage("причина", "%a%", true).contains("readable-url.js"));
    }

    /** metrika.js читает location.href при инициализации — переписывать адрес можно только после. */
    @Test
    void theAddressIsRewrittenOnlyAfterTheAnalyticsScriptHasReadIt() {
        String html = emptyResults(1, 1);

        assertTrue(html.indexOf("readable-url.js") > html.indexOf("metrika.js"),
                "readable-url.js обязан идти после metrika.js");
        assertTrue(html.contains("data-keep=\"q,exclude,sort,dir,p\""),
                "скрипт должен знать, какие параметры описаны адресом, чтобы перенести остальные");
    }

    /** Адрес, который страница передаёт readable-url.js. */
    private static String readableUrl(String html) {
        String prefix = "<SCRIPT src=\"readable-url.js\" data-url=\"";
        int start = html.indexOf(prefix);
        assertTrue(start >= 0, "скрипт с адресом на странице не выведен");
        String tail = html.substring(start + prefix.length());
        return tail.substring(0, tail.indexOf('"'));
    }

    @Test
    void indexPageWithErrorShowsTheReasonAndKeepsTheQueryInTheForm() {
        String html = HtmlView.indexPage("Слишком общий шаблон: <нужно> 2 символа",
                "\"><script>x</script>", false);

        assertTrue(html.contains("Слишком общий шаблон: &lt;нужно&gt; 2 символа"),
                "причина отказа — текстом на странице, а не только в коде ответа");
        assertTrue(html.contains("<FORM id=\"searchForm\" action=\"./\" method=\"get\">"),
                "поправить запрос можно прямо здесь, без возврата «назад»");
        assertTrue(html.contains("<DIV id=\"logs\"></DIV>"), "это та же главная, а не отдельный макет");
        assertTrue(html.contains("value=\"&quot;&gt;&lt;script&gt;x&lt;/script&gt;\""),
                "запрос возвращается в поле экранированным");
        assertFalse(html.contains("<script>"), "иначе показ запроса обратно — это XSS через ?q=");
    }

    @Test
    void rejectedSearchKeepsTheExpiredFilterTicked() {
        assertTrue(HtmlView.indexPage("причина", "%a%", true)
                        .contains("<INPUT type=\"checkbox\" name=\"exclude\" value=\"expired\" checked>"),
                "включённый фильтр возвращается включённым");
        assertTrue(HtmlView.indexPage("причина", "%a%", false)
                        .contains("<INPUT type=\"checkbox\" name=\"exclude\" value=\"expired\">"),
                "выключенный — выключенным");
        assertFalse(HtmlView.indexPage().contains("checked"), "на пустой главной фильтр снят");
    }

    // --- страница результатов ---

    @Test
    void resultsPageRendersRowWithLinkAndCrtShDateFormat() {
        String html = results("cateam.ru", false, SortColumn.LOGGED_AT, true, 1, 1,
                List.of(searchRow(42, "cateam.ru", "cateam.ru\nwww.cateam.ru", "CN=Issuer")));

        assertTrue(html.contains("<A href=\"?id=42\""), "id ведёт на страницу сертификата");
        assertTrue(html.contains("2026-08-03"), "Logged At — только дата, как в таблице crt.sh");
        assertFalse(html.contains("2026-08-03T09:04:23.123"), "ISO-строка целиком в таблицу не попадает");
        assertTrue(html.contains("cateam.ru<br>www.cateam.ru"), "идентификаторы разделяются переводом строки");
        assertTrue(html.contains("CN=Issuer"));
        assertFalse(html.contains("No certificates found."));
    }

    @Test
    void emptyResultShowsCrtShPlaceholder() {
        assertTrue(emptyResults(1, 0).contains("No certificates found."));
    }

    /** Запрос попадает в заголовок, Criteria и все ссылки — это главная точка XSS. */
    @Test
    void queryIsEscapedEverywhereItIsRendered() {
        String html = results("<script>alert(1)</script>", false, SortColumn.LOGGED_AT, true,
                1, 1, List.of());
        assertFalse(html.contains("<script>alert(1)</script>"), "сырой тег в разметку не попадает");
        assertTrue(html.contains("&lt;script&gt;"));
        assertTrue(html.contains("%3Cscript%3E"), "в ссылках запрос уходит URL-кодированным");
    }

    @Test
    void identitiesAndIssuerFromCertificateAreEscaped() {
        String html = results("q", false, SortColumn.LOGGED_AT, true, 1, 1,
                List.of(searchRow(1, "<b>cn</b>", "a\n<img src=x onerror=alert(1)>", "<i>issuer</i>")));
        assertFalse(html.contains("<img src=x"), "SAN приходит из публичного лога, то есть от кого угодно");
        assertTrue(html.contains("&lt;img src=x onerror=alert(1)&gt;"));
        assertTrue(html.contains("&lt;b&gt;cn&lt;/b&gt;"));
        assertTrue(html.contains("&lt;i&gt;issuer&lt;/i&gt;"));
    }

    /** Переключатель ведёт к обратному значению фильтра; сортировку сохраняет, страницу сбрасывает. */
    @Test
    void expiredToggleSwitchesBetweenIncludeAndExclude() {
        String all = results("cateam.ru", false, SortColumn.NOT_BEFORE, true, 3, 5, List.of());
        assertTrue(all.contains("<A class=\"header-link\" href=\"?q=cateam.ru&amp;exclude=expired"
                + "&amp;sort=2\">Exclude Expired</A>"), all);

        String active = results("cateam.ru", true, SortColumn.NOT_BEFORE, true, 3, 5, List.of());
        assertTrue(active.contains("<A class=\"header-link\" href=\"?q=cateam.ru&amp;sort=2\">"
                + "Include Expired</A>"), "включённый фильтр предлагает обратное действие");
        assertTrue(active.contains("Exclude: expired"), "и виден в Criteria");
        assertTrue(active.contains("?q=cateam.ru&amp;exclude=expired&amp;sort=2"),
                "остальные ссылки несут текущий фильтр и сортировку");
    }

    @Test
    void sortableHeadersFlipDirectionAndMarkTheActiveColumn() {
        String descending = emptyResults(1, 1);
        assertTrue(descending.contains("&amp;sort=1&amp;dir=asc\">Logged At</A> &#8679;"),
                "активная колонка по убыванию: стрелка вверх и ссылка на asc");
        assertTrue(descending.contains("&amp;sort=0\">crtlogs.ru ID</A>"),
                "неактивная колонка предлагает сортировку по убыванию, без dir");

        String ascending = results("q", false, SortColumn.LOGGED_AT, false, 1, 1, List.of());
        assertTrue(ascending.contains("&amp;sort=1\">Logged At</A> &#8681;"),
                "активная колонка по возрастанию: стрелка вниз и ссылка обратно на desc");
    }

    @Test
    void pagerAppearsOnlyWhenThereIsMoreThanOnePage() {
        assertFalse(emptyResults(1, 1).contains("class=\"pager\""));

        String first = emptyResults(1, 3);
        assertTrue(first.contains("Page 1 of 3"));
        assertFalse(first.contains("Prev"), "на первой странице некуда назад");
        assertTrue(first.contains("&amp;p=2\">Next"));

        String middle = emptyResults(2, 3);
        assertTrue(middle.contains("&amp;p=1\">&#8249; Prev"));
        assertTrue(middle.contains("&amp;p=3\">Next"));

        String last = emptyResults(3, 3);
        assertTrue(last.contains("&amp;p=2\">&#8249; Prev"));
        assertFalse(last.contains("Next"), "на последней странице некуда вперёд");
    }

    @Test
    void linksRepeatTheQueryInAReadableForm() {
        String html = results("%.рф", false, SortColumn.LOGGED_AT, true, 1, 2, List.of());

        assertTrue(html.contains("?q=%.рф&amp;sort=1&amp;dir=asc\">Logged At"), "в заголовке колонки");
        assertTrue(html.contains("?q=%.рф&amp;p=2\">Next"), "в пейджере");
        assertTrue(html.contains("?q=%.рф&amp;output=json"), "и в ссылке на JSON-выдачу");
        assertFalse(html.contains("%25"), "'%' перед точкой прочитать как escape нельзя — кодировать нечего");
        assertFalse(html.contains("%D1%80"), "кириллицу браузер закодирует сам, а покажет как есть");
    }

    @Test
    void jsonLinkRepeatsCurrentSearchIncludingPage() {
        String html = results("cateam.ru", true, SortColumn.NOT_BEFORE, false, 3, 5, List.of());
        assertTrue(html.contains("?q=cateam.ru&amp;exclude=expired&amp;sort=2&amp;dir=asc&amp;p=3&amp;output=json"),
                "ссылка на JSON повторяет текущие фильтр, сортировку и страницу");
    }

    // --- страница сертификата ---

    @Test
    void certificatePageShowsFieldsAndTransparencyEntries() {
        String html = HtmlView.certPage(certDetails(false, List.of(TestFixtures.occurrence(
                "https://ct.example/log/", 17, "2026-08-03T09:04:23.123")),
                "dump text", "CN=Issuer Name"));

        assertTrue(html.contains("Leaf certificate"));
        assertTrue(html.contains("x509_entry"));
        assertTrue(html.contains("cateam.ru<br>www.cateam.ru"));
        assertTrue(html.contains("CN=Issuer Name"));
        assertFalse(html.contains("CN=Issuer DN"), "issuer_dn нужен только когда имени издателя нет");
        assertTrue(html.contains("2026-08-03&nbsp; <FONT class=\"small\">09:04:23 UTC</FONT>"),
                "метка времени разбита на дату и время, как на crt.sh");
        assertTrue(html.contains("Example log"));
        assertTrue(html.contains("Operator"));
        assertTrue(html.contains("<TD>17</TD>"), "номер записи в логе");
        assertTrue(html.contains("ABCDEFABCDEF"), "SHA-256 печатается в верхнем регистре");
        assertTrue(html.contains("0011AA"));
        assertTrue(html.contains("?d=5\">PEM</A>"), "ссылка на скачивание");
        assertTrue(html.contains("dump text"));
    }

    @Test
    void precertificateIsLabelledAsSuch() {
        String html = HtmlView.certPage(certDetails(true, List.of(), "dump", "CN=Issuer Name"));
        assertTrue(html.contains("Precertificate"));
        assertTrue(html.contains("precert_entry"));
    }

    @Test
    void unparseableCertificateFallsBackFromDumpToPem() {
        String html = HtmlView.certPage(certDetails(false, List.of(), null, "CN=Issuer Name"));
        assertTrue(html.contains("-----BEGIN CERTIFICATE-----"), "дамп не построился — показываем PEM");
    }

    @Test
    void missingIssuerNameFallsBackToIssuerDn() {
        String html = HtmlView.certPage(certDetails(false, List.of(), "dump", null));
        assertTrue(html.contains("CN=Issuer DN"));
    }

    /** Стенд живёт под /testing/ — корневая ссылка увела бы его посетителя на прод. */
    @Test
    void allLinksAreRelativeSoThatThePrefixedStandKeepsWorking() {
        Pattern rootRelative = Pattern.compile("(?:href|src)=\"/(?!/)");
        String results = results("q", false, SortColumn.LOGGED_AT, true, 2, 3,
                List.of(searchRow(1, "cn", "cn", "issuer")));
        String cert = HtmlView.certPage(certDetails(false, List.of(TestFixtures.occurrence(
                "https://ct.example/log/", 1, "2026-01-01T00:00:00")), "dump", "CN=Issuer Name"));

        assertFalse(rootRelative.matcher(HtmlView.indexPage()).find(), "на главной есть корневая ссылка");
        assertFalse(rootRelative.matcher(results).find(), "на странице результатов есть корневая ссылка");
        assertFalse(rootRelative.matcher(cert).find(), "на странице сертификата есть корневая ссылка");
    }

    @Test
    void missingTimestampsRenderAsEmptyCells() {
        String html = results("q", false, SortColumn.LOGGED_AT, true, 1, 1,
                List.of(TestFixtures.blankSearchRow(1)));
        assertTrue(html.contains("<A href=\"?id=1\""));
        assertFalse(html.contains("null"), "null не должен просачиваться в разметку");
    }
}
