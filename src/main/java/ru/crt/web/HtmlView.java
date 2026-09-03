package ru.crt.web;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.util.HtmlUtils;
import ru.crt.db.SortColumn;

/**
 * Серверный рендер всех трёх страниц (главная, поиск, сертификат) в стиле crt.sh.
 * Без шаблонизатора — только экранирование и простая разметка. Общий каркас (шапка,
 * копирайт, счётчик) собирает {@link #page}, чтобы строка бренда правилась в одном месте.
 *
 * <p>Все ссылки относительные ({@code ?id=42}, {@code site.css}, {@code ./}), а не
 * корневые. Это нужно стенду: он живёт под префиксом /testing/, и корневая ссылка
 * увела бы посетителя стенда на прод. Браузер разрешает такие ссылки относительно
 * каталога текущего документа, поэтому одна и та же разметка работает и в корне,
 * и под любым префиксом.
 *
 * <p>Числа подставляются через {@code %s}, а не {@code %d}: {@code formatted} берёт
 * локаль по умолчанию, и под локалью с не-латинской системой счисления id сертификата
 * ушёл бы в разметку деванагари, ломая заодно и ссылку.
 */
final class HtmlView {

    /** Тип всех HTML-ответов — и страниц, и отказов; один на контроллер и {@link ErrorReply}. */
    static final MediaType CONTENT_TYPE = new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8);

    private HtmlView() {
    }

    /**
     * Общая шапка: шрифты и общий CSS. Правила бренда лежат в static/site.css рядом
     * с логами-виджетом — так их видят все три страницы.
     */
    private static final String HEAD_LINKS = """
            <LINK href="//fonts.googleapis.com/css2?family=DM+Sans:ital,wght@0,400;0,500;0,600;0,700;1,700&family=Urbanist:wght@700&family=Roboto+Mono&display=swap" rel="stylesheet">
            <LINK href="site.css" rel="stylesheet">
            """;

    /** Счётчик подключается файлом; в разметке остаётся только noscript-пиксель. */
    private static final String METRIKA = """
            <SCRIPT src="metrika.js"></SCRIPT>
            <noscript><div><img src="https://mc.yandex.ru/watch/112064937" style="position:absolute; left:-9999px;" alt="" /></div></noscript>
            """;

    private static final String COPYRIGHT =
            "  <P class=\"copyright\">&copy; crtlogs.ru &mdash; поиск по российским CT-логам "
                    + "(Yandex, VK, Минцифры), формат API совместим с crt.sh.</P>\n";

    /** Блок статуса логов: разметку рисует сам скрипт, здесь только место под неё. */
    private static final String LOGS_WIDGET = """
              <DIV id="logs"></DIV>

            """ + COPYRIGHT + "<SCRIPT src=\"logs-status.js\"></SCRIPT>\n";

    /**
     * Каркас страницы. {@code headerExtras} — то, что висит справа в шапке (есть только
     * у выдачи), {@code head} — стили конкретной страницы, {@code body} — всё между
     * шапкой и счётчиком, включая копирайт: у страницы сертификата он идёт после
     * таблицы, а у остальных — после блока статуса логов.
     */
    private static String page(String title, String heading, String headerExtras,
                               String head, String body) {
        return page(title, heading, headerExtras, head, body, "");
    }

    /**
     * То же, но с хвостом после счётчика. Единственный его постоялец — readable-url.js, и
     * место у него именно здесь, а не в теле: он меняет {@code location.href}, а
     * metrika.js читает его при инициализации. Встань он раньше — счётчик получал бы уже
     * переписанный адрес.
     */
    private static String page(String title, String heading, String headerExtras,
                               String head, String body, String afterMetrika) {
        return """
                <!DOCTYPE HTML>
                <HTML lang="ru">
                <HEAD>
                <META http-equiv="Content-Type" content="text/html; charset=UTF-8">
                <TITLE>%s</TITLE>
                %s
                </HEAD>
                <BODY>
                  <DIV class="header">
                    <A style="text-decoration:none" href="./"><SPAN class="crtsh">crtlogs.ru</SPAN></A> <SPAN class="title">%s</SPAN>%s
                  </DIV>
                %s%s%s
                </BODY>
                </HTML>
                """.formatted(title, head, heading, headerExtras, body, METRIKA, afterMetrika);
    }

    // --- главная ---

    /** Только то, чего нет на других страницах: поле поиска, кнопка и подсказка. */
    private static final String INDEX_STYLE = """
            <META name="viewport" content="width=device-width, initial-scale=1">
            """ + HEAD_LINKS + """
            <LINK href="logs-status.css" rel="stylesheet">
            <STYLE type="text/css">
              .input {
                border: 1px solid #015258;
                border-radius: 6px;
                font: 500 14px "DM Sans", sans-serif;
                height: 44px;
                width: 528px;
                max-width: 78vw;
                box-sizing: border-box;
                padding: 0 12px;
                text-align: center
              }
              .input:focus {
                border-color: #015258;
                outline: none
              }
              .button {
                background-color: #015258;
                border: none;
                border-radius: 1280px;
                color: #FFFFFF;
                cursor: pointer;
                font: 600 16px "DM Sans", sans-serif;
                padding: 4px 8px;
                height: 44px
              }
              #searchForm { margin: 32px 0 0; }
              #searchForm label {
                font: 8pt "DM Sans", sans-serif;
                color: #888888;
                white-space: nowrap;
                vertical-align: middle;
              }
              #hint {
                font: 8pt "DM Sans", sans-serif;
                color: #888888;
                margin-top: 20px;
                line-height: 1.8
              }
              #hint code { font-family: "Roboto Mono", monospace; }
            </STYLE>
            """;

    /**
     * Форма поиска. Значение поля и состояние чекбокса — параметрами: страница отказа
     * показывает ту же форму с исходным запросом и фильтром, чтобы запрос можно было
     * поправить, а не набирать заново (и не потерять «только действующие»).
     */
    private static String searchForm(String q, boolean excludeExpired) {
        return """
                  <!-- обычный GET-submit: результаты рисует сервер (resultsPage), поэтому -->
                  <!-- прямая ссылка, F5 и «назад» работают без отдельной клиентской ветки -->
                  <FORM id="searchForm" action="./" method="get">
                    <INPUT class="input" type="text" name="q" required autofocus autocomplete="off"
                           value="%s" placeholder="домен, например example.ru">
                    <LABEL><INPUT type="checkbox" name="exclude" value="%s"%s> только действующие</LABEL>
                    <INPUT class="button" type="submit" value="Search">
                  </FORM>
                """.formatted(esc(q), SearchQuery.EXCLUDE_EXPIRED, excludeExpired ? " checked" : "");
    }

    /** Подсказка под формой — одна на главную и на страницу отказа. */
    private static final String HINT = """
              <DIV id="hint">
                Точное совпадение; шаблон — <CODE>%.example.ru</CODE> (поддомены)
                или <CODE>%vk%</CODE> (вхождение)<BR>
                JSON-выдача как у crt.sh: <CODE>/?q=example.ru&amp;output=json</CODE>
              </DIV>

            """;

    private static final String INDEX_BODY = searchForm("", false) + "\n" + HINT + LOGS_WIDGET;

    /**
     * Начало любой ссылки страницы: сам запрос и фильтр действующих. Запрос приходит уже
     * закодированным — {@link QueryString#encode} на страницу зовётся один раз, а не по
     * разу на ссылку. Адрес относительный и без «./»: так его понимают и корень, и стенд
     * под префиксом /testing/, куда «./» с пути без слэша увело бы мимо.
     */
    private static String searchUrl(String encodedQuery, boolean excludeExpired) {
        return "?q=" + encodedQuery
                + (excludeExpired ? "&amp;exclude=" + SearchQuery.EXCLUDE_EXPIRED : "");
    }

    /**
     * Сортировка и направление, если они отличаются от умолчаний. Умолчания опускаются
     * не ради красоты: этот же хвост уходит в строку браузера (см. readable-url.js), и с
     * ними обычный {@code ?q=example.ru} превращался бы там в {@code ?q=example.ru&sort=1}.
     * Ссылкам это ничем не мешает — не названный параметр сервер понимает так же.
     *
     * <p>Явно называет сортировку только заголовок колонки ({@link #sortHeader}) — он её
     * не сохраняет, а задаёт.
     */
    private static String sortSuffix(SearchQuery query) {
        return (query.sort() == SortColumn.DEFAULT ? "" : "&amp;sort=" + query.sort().index())
                + (query.hasDefaultDirection() ? ""
                        : "&amp;dir=" + (query.descending() ? "desc" : "asc"));
    }

    /**
     * Скрипт, переписывающий адрес в строке браузера на читаемый (readable-url.js).
     * Ставится после счётчика — почему именно там, сказано у {@link #page}.
     *
     * <p>Зачем это нужно: ссылки страниц собираются читаемыми сервером, но адрес, по
     * которому посетитель сюда попал, читаемым быть не обязан — форма кодирует '%' как
     * %25, да и ссылку можно набрать руками или достать из закладок. Готовый адрес
     * приходит атрибутом, поэтому правило кодирования остаётся в одном месте, на
     * сервере; без JavaScript просто ничего не происходит.
     *
     * <p>Вторым атрибутом уезжает список параметров, которые этим адресом описаны
     * ({@link SearchQuery#PARAMETERS}): всё, чего в нём нет, скрипт переносит как есть,
     * иначе переписывание молча выкидывало бы из адреса чужие метки (utm, yclid).
     */
    private static String readableUrlScript(String url) {
        return "  <SCRIPT src=\"readable-url.js\" data-url=\"%s\" data-keep=\"%s\"></SCRIPT>\n"
                .formatted(url, String.join(",", SearchQuery.PARAMETERS));
    }

    /**
     * Главная целиком — константа: страница ни от чего не зависит, а собиралась заново
     * на каждый заход в корень (самый частый HTML-запрос сайта), давая по три копии
     * одних и тех же нескольких килобайт.
     */
    private static final String INDEX_PAGE = page("crtlogs.ru | Certificate Search",
            "Certificate Search", "", INDEX_STYLE, INDEX_BODY);

    static String indexPage() {
        return INDEX_PAGE;
    }

    /** Плашка с причиной отказа — единственное, чем главная с ошибкой отличается от обычной. */
    private static final String ERROR_STYLE = INDEX_STYLE + """
            <STYLE type="text/css">
              #error {
                font: 500 14px "DM Sans", sans-serif;
                color: #7A1C1C;
                background-color: #FDECEA;
                border: 1px solid #F5C2C0;
                border-radius: 6px;
                display: inline-block;
                padding: 12px 16px;
                margin-top: 24px;
                max-width: 78vw;
                text-align: left;
                line-height: 1.5
              }
            </STYLE>
            """;

    /**
     * Та же главная, но с объяснением, что пошло не так, и с сохранённым в форме
     * запросом: отказ правится на месте, а не после возврата «назад».
     *
     * <p>Отдельной страницы ошибки нет намеренно. Всё, что нужно посетителю после
     * отказа, — прочитать причину и повторить поиск, а это ровно главная; своя страница
     * добавила бы второй макет с той же формой и лишний переход. Показать причину при
     * этом обязательно: {@code ResponseStatusException} сам по себе доезжает до браузера
     * страницей Spring'а без текста ({@code server.error.include-message=never}), то есть
     * голым кодом 400, — а текст здесь и есть инструкция, что поправить в запросе.
     */
    static String indexPage(String message, String q, boolean excludeExpired) {
        // адрес здесь не переписываем: сюда приходят и 404, и 429, а по одному лишь q
        // восстановить, на какой странице и сортировке посетителя отбили, нельзя —
        // переписав адрес, мы бы у него эту страницу и отняли
        String body = "  <DIV id=\"error\">" + esc(message) + "</DIV>\n\n"
                + searchForm(q, excludeExpired) + "\n" + HINT + LOGS_WIDGET;
        return page("crtlogs.ru | Certificate Search", "Certificate Search", "", ERROR_STYLE, body);
    }

    // --- выдача поиска ---

    /** Только то, чего нет на других страницах: шапка со ссылкой на JSON и пейджер. */
    private static final String SEARCH_STYLE = HEAD_LINKS + """
            <LINK href="logs-status.css" rel="stylesheet">
            <STYLE type="text/css">
              .header-extras {
                position: absolute;
                left: calc(50% + 250px);
                top: 50%;
                transform: translateY(-50%);
                display: flex;
                align-items: center;
                gap: 24px
              }
              .header-icons {
                display: flex;
                align-items: center;
                gap: 16px
              }
              .header-icons A {
                display: inline-flex
              }
              .header-link {
                font: 8pt "DM Sans", sans-serif;
                color: #014149;
                text-decoration: underline
              }
              .pager {
                font: 10pt "DM Sans", sans-serif;
                color: #222222;
                margin: 8px 0
              }
            </STYLE>
            """;

    /**
     * Колонки таблицы Certificates в порядке показа; {@code sort} = null у тех, по
     * которым не сортируют. Списком, а не семью ячейками в шаблоне: добавить или
     * переставить колонку — одна правка, а не две несовпадающие по порядку.
     */
    private record Column(SortColumn sort, String label, boolean nowrap) {
    }

    private static final List<Column> COLUMNS = List.of(
            new Column(SortColumn.ID, "crtlogs.ru ID", false),
            new Column(SortColumn.LOGGED_AT, "Logged At", true),
            new Column(SortColumn.NOT_BEFORE, "Not Before", true),
            new Column(SortColumn.NOT_AFTER, "Not After", true),
            new Column(null, "Common Name", false),
            new Column(null, "Matching Identities", false),
            new Column(SortColumn.ISSUER, "Issuer Name", false));

    /**
     * Страница результатов поиска — точный макет crt.sh (?q=...): шапка Identity Search,
     * Criteria-таблица, таблица Certificates с сортируемыми колонками и пейджер.
     */
    static String resultsPage(SearchQuery query, SearchPage results) {
        // адрес собирается читаемым: '%' шаблона и кириллица остаются как есть, см.
        // QueryString.encode. Экранировать результат не нужно — ни '&', ни кавычек в нём
        // по построению нет
        String encQ = QueryString.encode(query.q());
        String base = searchUrl(encQ, query.excludeExpired());
        StringBuilder trs = new StringBuilder(results.rows().size() * 512);
        for (SearchRow r : results.rows()) {
            trs.append("""
                    <TR>
                    <TD class="center"><A href="?id=%s" target="_blank" rel="noopener">%s</A></TD>
                    <TD class="center nowrap">%s</TD>
                    <TD class="center nowrap">%s</TD>
                    <TD class="center nowrap">%s</TD>
                    <TD class="nowrap">%s</TD>
                    <TD class="nowrap">%s</TD>
                    <TD class="wrap">%s</TD>
                    </TR>
                    """.formatted(r.id(), r.id(),
                    Timestamps.datePart(r.entryTimestamp()),
                    Timestamps.datePart(r.notBefore()),
                    Timestamps.datePart(r.notAfter()),
                    esc(r.commonName()),
                    escMulti(r.nameValue()),
                    esc(r.issuerName())));
        }
        if (trs.isEmpty()) {
            trs.append("<TR><TD colspan=\"7\">No certificates found.</TD></TR>\n");
        }
        String sortDir = sortSuffix(query);
        // текущий поиск целиком, одной строкой на всех: ссылку на JSON, адрес в строке
        // браузера и (без страницы) пейджер — иначе адрес расходился бы со ссылками
        String current = base + sortDir + (query.page() > 1 ? "&amp;p=" + query.page() : "");
        String headerExtras = """

                    <DIV class="header-extras">
                      <DIV class="header-icons">
                        <A href="%s&amp;output=json" target="_blank" rel="noopener" title="JSON output">%s</A>
                      </DIV>
                      <A class="header-link" href="%s">%s</A>
                    </DIV>
                """.formatted(current, JSON_ICON,
                // переключатель ведёт к обратному значению фильтра, а не к текущему.
                // Сортировку он сохраняет, а страницу сбрасывает — выборка меняет размер
                searchUrl(encQ, !query.excludeExpired()) + sortDir,
                query.excludeExpired() ? "Include Expired" : "Exclude Expired");
        String body = """
                <BR>
                <TABLE>
                  <TR>
                    <TH class="outer">Criteria</TH>
                    <TD class="outer">Type: Identity&nbsp;&nbsp;&nbsp;&nbsp;Match: ILIKE&nbsp;&nbsp;&nbsp;&nbsp;Search: '%s'%s</TD>
                  </TR>
                </TABLE>
                <BR>
                <TABLE>
                  <TR>
                    <TH class="outer">Certificates</TH>
                    <TD class="outer">
                <TABLE class="identities">
                %s%s
                </TABLE>
                    </TD>
                  </TR>
                </TABLE>
                %s

                  <BR><BR><BR>

                """.formatted(
                esc(query.q()),
                query.excludeExpired()
                        ? "&nbsp;&nbsp;&nbsp;&nbsp;Exclude: " + SearchQuery.EXCLUDE_EXPIRED : "",
                headerRow(query, base),
                trs.toString(),
                pager(base + sortDir, query.page(), results.totalPages())) + LOGS_WIDGET;
        return page("crtlogs.ru | " + esc(query.q()), "Identity Search", headerExtras,
                SEARCH_STYLE, body, readableUrlScript(current));
    }

    /** Строка заголовков таблицы Certificates: сортируемые колонки получают ссылку. */
    private static String headerRow(SearchQuery query, String base) {
        StringBuilder ths = new StringBuilder("  <TR>\n");
        for (Column c : COLUMNS) {
            ths.append(c.nowrap() ? "    <TH class=\"nowrap\">" : "    <TH>")
                    .append(c.sort() == null ? c.label() : sortHeader(c.sort(), c.label(), query, base))
                    .append("</TH>\n");
        }
        return ths.append("  </TR>\n").toString();
    }

    /** Пейджер «‹ Prev | Page N of M | Next ›» под таблицей Certificates. */
    private static String pager(String base, int page, int totalPages) {
        if (totalPages <= 1) {
            return "";
        }
        StringBuilder sb = new StringBuilder("<DIV class=\"pager\">");
        if (page > 1) {
            sb.append("<A href=\"").append(base)
                    .append("&amp;p=").append(page - 1).append("\">&#8249; Prev</A>&nbsp;&nbsp;");
        }
        sb.append("Page ").append(page).append(" of ").append(totalPages);
        if (page < totalPages) {
            sb.append("&nbsp;&nbsp;<A href=\"").append(base)
                    .append("&amp;p=").append(page + 1).append("\">Next &#8250;</A>");
        }
        sb.append("</DIV>");
        return sb.toString();
    }

    /**
     * Заголовок сортируемой колонки: ссылка flip-направления + стрелка у активной,
     * как crt.sh. Номер колонки берётся из {@link SortColumn}, а не пишется литералом
     * рядом с подписью: иначе заголовок и SQL могли бы разъехаться молча.
     */
    private static String sortHeader(SortColumn column, String label, SearchQuery query, String base) {
        boolean isActive = column == query.sort();
        // неактивная колонка — предлагаем сортировку по убыванию; активная — переворачиваем
        String dir = isActive && query.descending() ? "&amp;dir=asc" : "";
        String arrow = isActive ? (query.descending() ? " &#8679;" : " &#8681;") : "";
        return "<A href=\"" + base + "&amp;sort=" + column.index() + dir + "\">" + label + "</A>" + arrow;
    }

    /** Иконка JSON (фигурные скобки) в стиле иконок шапки crt.sh. */
    private static final String JSON_ICON = """
            <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="#015258" \
            stroke-width="2" stroke-linecap="round" stroke-linejoin="round">\
            <path d="M8 3H7a2 2 0 0 0-2 2v4a2 2 0 0 1-2 2 2 2 0 0 1 2 2v4a2 2 0 0 0 2 2h1"/>\
            <path d="M16 3h1a2 2 0 0 1 2 2v4a2 2 0 0 0 2 2 2 2 0 0 0-2 2v4a2 2 0 0 1-2 2h-1"/></svg>""";

    // --- страница сертификата ---

    /** Только то, чего нет на других страницах: таблица опций и блок дампа. */
    private static final String CERT_STYLE = HEAD_LINKS + """
            <STYLE type="text/css">
              table.options { border: none; margin-left: 0px; }
              td.text { font: 10pt "Roboto Mono", monospace; padding: 2px 20px; text-align: left;
                     white-space: pre; }
              td.mono { font-family: "Roboto Mono", monospace; }
              td.mono.sm { font-size: 9pt; }
              .small { font: 8pt "DM Sans", sans-serif; color: #888888; }
            </STYLE>
            """;

    /** Строка внешней таблицы: подпись слева, значение справа. */
    private static String row(String header, String value) {
        return row(header, "outer", value);
    }

    /** То же, но с дополнительными классами ячейки значения (моноширинный шрифт и т.п.). */
    private static String row(String header, String valueClasses, String value) {
        return """
                  <TR>
                    <TH class="outer">%s</TH>
                    <TD class="%s">%s</TD>
                  </TR>
                """.formatted(header, valueClasses, value);
    }

    /** Страница сертификата в дизайне crt.sh: шапка, Criteria, CT-таблица, отпечатки, дамп. */
    static String certPage(CertDetails d) {
        StringBuilder ct = new StringBuilder();
        for (CertDetails.LogOccurrence o : d.occurrences()) {
            // operator без запасного "" — esc() уже отдаёт "" на null
            String name = o.description() != null ? o.description() : o.logUrl();
            ct.append("""
                    <TR>
                    <TD>%s&nbsp; <FONT class="small">%s UTC</FONT></TD>
                    <TD>%s</TD>
                    <TD>%s</TD>
                    <TD><A href="%s">%s</A><BR><FONT class="small">%s</FONT></TD>
                    </TR>
                    """.formatted(
                    Timestamps.datePart(o.entryTimestamp()),
                    Timestamps.timePart(o.entryTimestamp()),
                    o.logIndex(),
                    esc(o.operator()),
                    esc(o.logUrl()),
                    esc(name),
                    esc(o.logUrl())));
        }
        String fields = row("crtlogs.ru ID", "<A href=\"?id=%s\">%s</A>".formatted(d.id(), d.id()))
                + row("Summary", "%s &mdash; <SPAN class=\"small\">%s</SPAN>".formatted(
                        d.precert() ? "Precertificate" : "Leaf certificate",
                        d.precert() ? "precert_entry" : "x509_entry"))
                + row("Matching Identifiers", "outer mono sm", escMulti(d.nameValue()))
                + row("Validity", "%s &mdash; %s".formatted(esc(d.notBefore()), esc(d.notAfter())))
                + row("Issuer Name", esc(d.issuerName() != null ? d.issuerName() : d.issuerDn()))
                + row("Certificate Transparency", """
                        <DIV style="overflow-y:scroll;height:100px">
                        <TABLE class="identities" style="margin-left:0px">
                          <TR>
                            <TH>Timestamp</TH>
                            <TH>Entry #</TH>
                            <TH>Log Operator</TH>
                            <TH>Log URL</Th>
                          </TR>
                        %s
                        </TABLE>
                        </DIV>""".formatted(ct))
                + row("Serial Number", "outer mono", esc(d.serialNumber()))
                + row("Certificate Fingerprints", """
                        <TABLE class="options" style="margin-left:0px">
                          <TR>
                            <TH>SHA-256</TH>
                            <TD class="mono sm">%s</TD>
                            <TD style="width:20px;border:none">&nbsp;</TD>
                            <TH>SHA-1</TH>
                            <TD class="mono sm">%s</TD>
                          </TR>
                        </TABLE>""".formatted(
                                esc(d.sha256().toUpperCase()),
                                esc(d.sha1())));
        String body = """
                  <BR>
                <TABLE>
                  <TR>
                    <TH class="outer">Criteria</TH>
                    <TD class="outer">ID = '%s'</TD>
                  </TR>
                </TABLE>
                <BR>
                <TABLE>
                %s  <TR>
                    <TH class="outer" style="white-space:nowrap">
                      | Certificate |
                      <SPAN class="small">
                      <BR><BR><BR>Download Certificate: <A href="?d=%s">PEM</A>
                      </SPAN>
                    </TH>
                    <TD class="text">%s</TD>
                  </TR>
                </TABLE>

                  <BR><BR><BR>

                """.formatted(d.id(), fields, d.id(),
                        esc(d.text() != null ? d.text() : d.pem()))
                + COPYRIGHT;
        return page("crtlogs.ru | " + d.id(), "Certificate Search", "", CERT_STYLE, body);
    }

    // --- вспомогательное ---

    /**
     * HTML-экранирование. Кодировку передаём явно: однопараметрический htmlEscape
     * работает как ISO-8859-1 и превратил бы кириллицу в числовые сущности.
     * Пакет-private для unit-теста, который это и закрепляет.
     */
    static String esc(String s) {
        return s == null ? "" : HtmlUtils.htmlEscape(s, "UTF-8");
    }

    /** Экранирование + переводы строк идентификаторов (name_value через '\n'). */
    private static String escMulti(String s) {
        return esc(s).replace("\n", "<br>");
    }
}
