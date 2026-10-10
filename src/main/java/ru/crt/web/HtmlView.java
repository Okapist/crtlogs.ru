package ru.crt.web;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import org.springframework.http.MediaType;
import org.springframework.web.util.HtmlUtils;
import ru.crt.db.SortColumn;

/** Серверный рендер всех трёх страниц в стиле crt.sh, без шаблонизатора. Ссылки относительные — стенд живёт под /testing/; числа через {@code %s}, а не {@code %d} (formatted берёт локаль по умолчанию). */
final class HtmlView {

    /** Тип всех HTML-ответов — и страниц, и отказов; общий для контроллера и {@link ErrorReply}. */
    static final MediaType CONTENT_TYPE = new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8);

    private HtmlView() {
    }

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

    /** Каркас страницы; копирайт — часть body (у страницы сертификата он после таблицы). */
    private static String page(String title, String heading, String headerExtras,
                               String head, String body) {
        return page(title, heading, headerExtras, head, body, "");
    }

    /** То же с хвостом после счётчика: readable-url.js должен стоять после metrika.js — иначе счётчик прочитает уже переписанный адрес. */
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

    /** Форма поиска; значение и чекбокс — параметрами: страница отказа показывает её же с исходным запросом. */
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

    /** Начало любой ссылки: запрос и фильтр; адрес относительный, без «./» — работает и в корне, и на стенде. */
    private static String searchUrl(String encodedQuery, boolean excludeExpired) {
        return "?q=" + encodedQuery
                + (excludeExpired ? "&amp;exclude=" + SearchQuery.EXCLUDE_EXPIRED : "");
    }

    /** Сортировка и направление, если отличаются от умолчаний — иначе адрес в браузере всегда нёс бы &sort=1. */
    private static String sortSuffix(SearchQuery query) {
        return (query.sort() == SortColumn.DEFAULT ? "" : "&amp;sort=" + query.sort().index())
                + (query.hasDefaultDirection() ? ""
                        : "&amp;dir=" + (query.descending() ? "desc" : "asc"));
    }

    /** Скрипт, переписывающий адрес в строке браузера на читаемый; готовый адрес приходит атрибутом, всё, чего нет в {@link SearchQuery#PARAMETERS}, переносится как есть. */
    private static String readableUrlScript(String url) {
        return "  <SCRIPT src=\"readable-url.js\" data-url=\"%s\" data-keep=\"%s\"></SCRIPT>\n"
                .formatted(url, String.join(",", SearchQuery.PARAMETERS));
    }

    /** Главная — константа: собиралась бы заново на каждый заход в корень, самый частый HTML-запрос. */
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

    /** Та же главная с причиной отказа и сохранённым в форме запросом — отказ правится на месте. */
    static String indexPage(String message, String q, boolean excludeExpired) {
        // адрес здесь не переписываем: по одному лишь q не восстановить, откуда посетителя отбили
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

    /** Колонки таблицы Certificates в порядке показа; {@code sort} = null у несортируемых. */
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

    /** Страница результатов — точный макет crt.sh: Criteria, таблица Certificates, пейджер. */
    static String resultsPage(SearchQuery query, SearchPage results) {
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
        // текущий поиск одной строкой: ссылка на JSON, адрес браузера и пейджер
        String current = base + sortDir + (query.page() > 1 ? "&amp;p=" + query.page() : "");
        String headerExtras = """

                    <DIV class="header-extras">
                      <DIV class="header-icons">
                        <A href="%s&amp;output=json" target="_blank" rel="noopener" title="JSON output">%s</A>
                      </DIV>
                      <A class="header-link" href="%s">%s</A>
                    </DIV>
                """.formatted(current, JSON_ICON,
                // переключатель ведёт к обратному значению фильтра; сортировку сохраняет, страницу сбрасывает
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

    /** Заголовок сортируемой колонки: ссылка flip-направления + стрелка у активной. */
    private static String sortHeader(SortColumn column, String label, SearchQuery query, String base) {
        boolean isActive = column == query.sort();
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
                                esc(d.sha256().toUpperCase(Locale.ROOT)),
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

    /** HTML-экранирование; кодировку передаём явно — однопараметрический htmlEscape работает как ISO-8859-1 и превращает кириллицу в числовые сущности. */
    static String esc(String s) {
        return s == null ? "" : HtmlUtils.htmlEscape(s, "UTF-8");
    }

    /** Экранирование + переводы строк идентификаторов (name_value через '\n'). */
    private static String escMulti(String s) {
        return esc(s).replace("\n", "<br>");
    }
}
