package ru.crt.web;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Серверный рендер HTML-страниц в стиле crt.sh (поиск и просмотр сертификата).
 * Без шаблонизатора — только экранирование и простая разметка.
 */
final class HtmlView {

    private HtmlView() {
    }

    /** Шрифты и CSS страницы результатов — по образцу crt.sh (?q=...). */
    private static final String SEARCH_STYLE = """
            <LINK href="//fonts.googleapis.com/css2?family=DM+Sans:ital,wght@0,400;0,500;0,600;0,700;1,700&family=Urbanist:wght@700&family=Roboto+Mono&display=swap" rel="stylesheet">
            <LINK href="/logs-status.css" rel="stylesheet">
            <STYLE type="text/css">
              a {
                color: #015258;
                text-decoration: underline;
                white-space: nowrap;
              }
              body {
                font: 12pt "DM Sans", sans-serif;
                padding-top: 10px;
                text-align: center
              }
              form {
                margin: 0px
              }
              span.crtsh {
                background-color: #015258;
                color: #FFFFFF;
                font: bold 30px "Urbanist", sans-serif;
                padding: 2px 20px;
                border-radius: 6px
              }
              span.title {
                color: #333132;
                font: bold 30px "Urbanist", sans-serif;
                padding: 10px 12px
              }
              .header {
                position: relative;
                display: flex;
                align-items: center;
                justify-content: center;
                gap: 32px;
                flex-wrap: wrap;
              }
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
              table {
                border-collapse: collapse;
                color: #222222;
                font: 10pt "DM Sans", sans-serif;
                margin-left: auto;
                margin-right: auto
              }
              td, th {
                border: 1px solid #CCCCCC;
                padding: 0px 2px;
                text-align: left;
                vertical-align: top
              }
              td.outer, th.outer {
                border: 1px solid #CCCCCC;
                padding: 2px 20px;
                text-align: left
              }
              table.identities td {
                font: 9pt "DM Mono", monospace;
                padding: 4px 8px
              }
              .pager {
                font: 10pt "DM Sans", sans-serif;
                color: #222222;
                margin: 8px
              }
              .copyright {
                font: 8pt "DM Sans", sans-serif;
                color: #000000;
                text-align: center
              }
            </STYLE>
            """;

    private static final String METRIKA = """
            <!-- Yandex.Metrika counter -->
            <script type="text/javascript">
                (function(m,e,t,r,i,k,a){
                    m[i]=m[i]||function(){(m[i].a=m[i].a||[]).push(arguments)};
                    m[i].l=1*new Date();
                    for (var j = 0; j < document.scripts.length; j++) {if (document.scripts[j].src === r) { return; }}
                    k=e.createElement(t),a=e.getElementsByTagName(t)[0],k.async=1,k.src=r,a.parentNode.insertBefore(k,a)
                })(window, document,'script','https://mc.yandex.ru/metrika/tag.js?id=112064937', 'ym');

                ym(112064937, 'init', {ssr:true, webvisor:true, clickmap:true, ecommerce:"dataLayer", referrer: document.referrer, url: location.href, accurateTrackBounce:true, trackLinks:true});
            </script>
            <noscript><div><img src="https://mc.yandex.ru/watch/112064937" style="position:absolute; left:-9999px;" alt="" /></div></noscript>
            <!-- /Yandex.Metrika counter -->
            """;

    /** Страница результатов поиска — точный макет crt.sh (?q=...): шапка Identity Search,
     *  Criteria-таблица, таблица Certificates с сортируемыми колонками и пейджер
     *  (результаты разбиты на страницы по 100, page от 1, totalPages всего). */
    static String resultsPage(String q, boolean excludeExpired, List<SearchRow> rows,
                              int sort, boolean descending, int page, int totalPages) {
        String encQ = URLEncoder.encode(q, StandardCharsets.UTF_8);
        String base = "/?q=" + encQ + (excludeExpired ? "&amp;exclude=expired" : "");
        StringBuilder trs = new StringBuilder();
        for (SearchRow r : rows) {
            trs.append("""
                    <TR>
                    <TD style="text-align:center"><A href="/?id=%d" target="_blank" rel="noopener">%d</A></TD>
                    <TD style="text-align:center;white-space:nowrap">%s</TD>
                    <TD style="text-align:center;white-space:nowrap">%s</TD>
                    <TD style="text-align:center;white-space:nowrap">%s</TD>
                    <TD style="white-space:nowrap">%s</TD>
                    <TD style="white-space:nowrap">%s</TD>
                    <TD style="white-space:normal">%s</TD>
                    </TR>
                    """.formatted(r.id(), r.id(),
                    crtshTimestamp(r.entry_timestamp(), true),
                    crtshTimestamp(r.not_before(), true),
                    crtshTimestamp(r.not_after(), true),
                    esc(r.common_name()),
                    escMulti(r.name_value()),
                    esc(r.issuer_name())));
        }
        if (trs.isEmpty()) {
            trs.append("<TR><TD colspan=\"7\">No certificates found.</TD></TR>\n");
        }
        String toggleHref = excludeExpired ? "/?q=" + encQ : base;
        String toggleText = excludeExpired ? "Include Expired" : "Exclude Expired";
        String sortDir = "&amp;sort=" + sort + (descending ? "" : "&amp;dir=asc");
        String jsonHref = base + sortDir + (page > 1 ? "&amp;p=" + page : "");
        String pager = pager(base, sort, descending, page, totalPages);
        return """
                <!DOCTYPE HTML>
                <HTML>
                <HEAD>
                <META http-equiv="Content-Type" content="text/html; charset=UTF-8">
                <TITLE>crtlogs.ru | %s</TITLE>
                %s
                </HEAD>
                <BODY>
                  <DIV class="header">
                    <A style="text-decoration:none" href="/"><SPAN class="crtsh">crtlogs.ru</SPAN></A> <SPAN class="title">Identity Search</SPAN>
                    <DIV class="header-extras">
                      <DIV class="header-icons">
                        <A href="%s&amp;output=json" target="_blank" rel="noopener" title="JSON output">%s</A>
                      </DIV>
                      <A class="header-link" href="%s">%s</A>
                    </DIV>
                  </DIV>
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
                  <TR>
                    <TH>
                      %s
                    </TH>
                    <TH style="white-space:nowrap">
                      %s
                    </TH>
                    <TH style="white-space:nowrap">%s
                    </TH>
                    <TH style="white-space:nowrap">%s
                    </TH>
                    <TH>Common Name</TH>
                    <TH>Matching Identities</TH>
                    <TH>
                      %s
                    </TH>
                  </TR>
                %s
                </TABLE>
                    </TD>
                  </TR>
                </TABLE>
                %s

                  <BR><BR><BR>

                  <DIV id="logs"></DIV>

                  <P class="copyright">&copy; crtlogs.ru &mdash; поиск по российским CT-логам (Yandex, VK, Минцифры), формат API совместим с crt.sh.</P>
                <SCRIPT src="/logs-status.js"></SCRIPT>
                %s
                </BODY>
                </HTML>
                """.formatted(
                esc(q),
                SEARCH_STYLE,
                jsonHref, jsonIcon(),
                toggleHref, toggleText,
                esc(q),
                excludeExpired ? "&nbsp;&nbsp;&nbsp;&nbsp;Exclude: expired" : "",
                sortHeader("crtlogs.ru ID", 0, sort, descending, base),
                sortHeader("Logged At", 1, sort, descending, base),
                sortHeader("Not Before", 2, sort, descending, base),
                sortHeader("Not After", 4, sort, descending, base),
                sortHeader("Issuer Name", 3, sort, descending, base),
                trs.toString(),
                pager,
                METRIKA);
    }

    /** Пейджер «‹ Prev | Page N of M | Next ›» под таблицей Certificates. */
    private static String pager(String base, int sort, boolean descending, int page, int totalPages) {
        if (totalPages <= 1) {
            return "";
        }
        String sortDir = "&amp;sort=" + sort + (descending ? "" : "&amp;dir=asc");
        StringBuilder sb = new StringBuilder("<DIV class=\"pager\">");
        if (page > 1) {
            sb.append("<A href=\"").append(base).append(sortDir)
                    .append("&amp;p=").append(page - 1).append("\">&#8249; Prev</A>&nbsp;&nbsp;");
        }
        sb.append("Page ").append(page).append(" of ").append(totalPages);
        if (page < totalPages) {
            sb.append("&nbsp;&nbsp;<A href=\"").append(base).append(sortDir)
                    .append("&amp;p=").append(page + 1).append("\">Next &#8250;</A>");
        }
        sb.append("</DIV>");
        return sb.toString();
    }

    /** Заголовок сортируемой колонки: ссылка flip-направления + стрелка у активной, как crt.sh. */
    private static String sortHeader(String label, int idx, int active, boolean descending, String base) {
        boolean isActive = idx == active;
        // неактивная колонка — предлагаем сортировку по убыванию; активная — переворачиваем
        String dir = isActive && descending ? "&amp;dir=asc" : "";
        String arrow = isActive ? (descending ? " &#8679;" : " &#8681;") : "";
        return "<A href=\"" + base + "&amp;sort=" + idx + dir + "\">" + label + "</A>" + arrow;
    }

    /** Иконка JSON (фигурные скобки) в стиле иконок шапки crt.sh. */
    private static String jsonIcon() {
        return """
                <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="#015258" \
                stroke-width="2" stroke-linecap="round" stroke-linejoin="round">\
                <path d="M8 3H7a2 2 0 0 0-2 2v4a2 2 0 0 1-2 2 2 2 0 0 1 2 2v4a2 2 0 0 0 2 2h1"/>\
                <path d="M16 3h1a2 2 0 0 1 2 2v4a2 2 0 0 0 2 2 2 2 0 0 0-2 2v4a2 2 0 0 1-2 2h-1"/></svg>""";
    }

    /** CSS и шрифты страницы сертификата — по образцу crt.sh. */
    private static final String CERT_STYLE = """
            <LINK href="//fonts.googleapis.com/css2?family=DM+Sans:wght@400;500;600;700&family=Urbanist:wght@700&family=Roboto+Mono&display=swap" rel="stylesheet">
            <STYLE type="text/css">
              a { color: #015258; text-decoration: underline; white-space: nowrap; }
              body { font: 12pt "DM Sans", sans-serif; padding-top: 10px; text-align: center; }
              span.crtsh { background-color: #015258; color: #FFFFFF; font: bold 30px "Urbanist", sans-serif;
                     padding: 2px 20px; border-radius: 6px; }
              span.title { color: #333132; font: bold 30px "Urbanist", sans-serif; padding: 10px 12px; }
              .header { position: relative; display: flex; align-items: center; justify-content: center;
                     gap: 32px; flex-wrap: wrap; }
              table { border-collapse: collapse; color: #222222; font: 10pt "DM Sans", sans-serif;
                     margin-left: auto; margin-right: auto; }
              table.options { border: none; margin-left: 0px; }
              td, th { border: 1px solid #CCCCCC; padding: 0px 2px; text-align: left; vertical-align: top; }
              td.outer, th.outer { border: 1px solid #CCCCCC; padding: 2px 20px; text-align: left; }
              th.options, td.options { border: none; vertical-align: middle; }
              td.text { font: 10pt "Roboto Mono", monospace; padding: 2px 20px; text-align: left;
                     white-space: pre; }
              table.identities td { font: 9pt "DM Mono", monospace, monospace; padding: 4px 8px; }
              td.mono, table.options td.mono { font-family: "Roboto Mono", monospace; font-size: 9pt; }
              .small { font: 8pt "DM Sans", sans-serif; color: #888888; }
              .copyright { font: 8pt "DM Sans", sans-serif; color: #000000; text-align: center; }
              .copyright a { color: #015258; }
            </STYLE>
            """;

    /** Страница сертификата в дизайне crt.sh: шапка, Criteria, CT-таблица, отпечатки, дамп. */
    static String certPage(CertDetails d) {
        String summary = d.is_precert() ? "Precertificate" : "Leaf certificate";
        StringBuilder ct = new StringBuilder();
        for (CertDetails.LogOccurrence o : d.occurrences()) {
            String operator = o.operator() != null ? o.operator() : "";
            String name = o.description() != null ? o.description() : o.log_url();
            ct.append("""
                    <TR>
                    <TD>%s&nbsp; <FONT class="small">%s UTC</FONT></TD>
                    <TD>%d</TD>
                    <TD>%s</TD>
                    <TD><A href="%s">%s</A><BR><FONT class="small">%s</FONT></TD>
                    </TR>
                    """.formatted(
                    crtshTimestamp(o.entry_timestamp(), true),
                    crtshTimestamp(o.entry_timestamp(), false),
                    o.log_index(),
                    esc(operator),
                    esc(o.log_url()),
                    esc(name),
                    esc(o.log_url())));
        }
        if (ct.isEmpty()) {
            ct.append("<TR><TD>").append(esc(d.entry_timestamp()))
                    .append("</TD><TD>").append(d.log_index())
                    .append("</TD><TD></TD><TD>").append(esc(d.log_url())).append("</TD></TR>");
        }
        String dump = d.text() != null ? d.text() : d.pem();
        return """
                <!DOCTYPE HTML>
                <HTML>
                <HEAD>
                <META http-equiv="Content-Type" content="text/html; charset=UTF-8">
                <TITLE>crtlogs.ru | %d</TITLE>
                %s
                </HEAD>
                <BODY>
                  <DIV class="header">
                    <A style="text-decoration:none" href="/"><SPAN class="crtsh">crtlogs.ru</SPAN></A> <SPAN class="title">Certificate Search</SPAN>
                  </DIV>
                  <BR>
                <TABLE>
                  <TR>
                    <TH class="outer">Criteria</TH>
                    <TD class="outer">ID = '%d'</TD>
                  </TR>
                </TABLE>
                <BR>
                <TABLE>
                  <TR>
                    <TH class="outer">crtlogs.ru ID</TH>
                    <TD class="outer"><A href="?id=%d">%d</A></TD>
                  </TR>
                  <TR>
                    <TH class="outer">Summary</TH>
                    <TD class="outer">%s &mdash; <SPAN class="small">%s</SPAN></TD>
                  </TR>
                  <TR>
                    <TH class="outer">Matching Identifiers</TH>
                    <TD class="outer" style="font-family:'Roboto Mono',monospace;font-size:9pt">%s</TD>
                  </TR>
                  <TR>
                    <TH class="outer">Validity</TH>
                    <TD class="outer">%s &mdash; %s</TD>
                  </TR>
                  <TR>
                    <TH class="outer">Issuer Name</TH>
                    <TD class="outer">%s</TD>
                  </TR>
                  <TR>
                    <TH class="outer">Certificate Transparency</TH>
                    <TD class="outer">
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
                      </DIV>
                    </TD>
                  </TR>
                  <TR>
                    <TH class="outer">Serial Number</TH>
                    <TD class="outer" style="font-family:'Roboto Mono',monospace">%s</TD>
                  </TR>
                  <TR>
                    <TH class="outer">Certificate Fingerprints</TH>
                    <TD class="outer">
                      <TABLE class="options" style="margin-left:0px">
                        <TR>
                          <TH>SHA-256</TH>
                          <TD class="mono">%s</TD>
                          <TD style="width:20px;border:none">&nbsp;</TD>
                          <TH>SHA-1</TH>
                          <TD class="mono">%s</TD>
                        </TR>
                      </TABLE>
                    </TD>
                  </TR>
                  <TR>
                    <TH class="outer" style="white-space:nowrap">
                      | Certificate |
                      <SPAN class="small">
                      <BR><BR><BR>Download Certificate: <A href="?d=%d">PEM</A>
                      </SPAN>
                    </TH>
                    <TD class="text">%s</TD>
                  </TR>
                </TABLE>

                  <BR><BR><BR>

                  <P class="copyright">&copy; crtlogs.ru &mdash; поиск по российским CT-логам (Yandex, VK, Минцифры), формат API совместим с crt.sh.</P>
                %s
                </BODY>
                </HTML>
                """.formatted(
                d.id(),
                CERT_STYLE,
                d.id(),
                d.id(), d.id(),
                esc(summary), d.is_precert() ? "precert_entry" : "x509_entry",
                escMulti(d.name_value()),
                esc(d.not_before()), esc(d.not_after()),
                esc(d.issuer_name() != null ? d.issuer_name() : d.issuer_dn()),
                ct.toString(),
                esc(d.serial_number()),
                esc(d.sha256().toUpperCase()),
                d.sha1() == null ? "n/a" : esc(d.sha1()),
                d.id(),
                esc(dump),
                METRIKA);
    }

    /** ISO-метка времени из БД -> дата или время, как на crt.sh ("2026-08-03 09:04:23 UTC"). */
    private static String crtshTimestamp(String iso, boolean datePart) {
        if (iso == null) {
            return "";
        }
        String plain = iso.replace('T', ' ').replaceAll("\\.\\d+$", "");
        if (datePart) {
            return esc(plain.substring(0, 10));
        }
        return esc(plain.length() > 11 ? plain.substring(11) : plain);
    }

    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }

    /** Экранирование + переводы строк идентификаторов (name_value через '\n'). */
    private static String escMulti(String s) {
        return esc(s).replace("\n", "<br>");
    }
}
