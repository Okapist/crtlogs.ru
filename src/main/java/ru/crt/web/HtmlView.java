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

    private static final String STYLE = """
            <style>
              body { font-family: Georgia, "Times New Roman", serif; background: #fff; color: #000;
                     margin: 16px 24px; font-size: 15px; }
              a { color: #1a0dab; }
              .masthead { margin-bottom: 16px; overflow: hidden; }
              .masthead .logo { font-size: 22px; font-weight: bold; }
              .masthead .sub { color: #666; font-size: 13px; margin-left: 10px; }
              .masthead form { float: right; margin-top: 2px; }
              .masthead form input[type=text] { width: 300px; padding: 4px 6px; font-size: 13px;
                     font-family: inherit; border: 1px solid #767676; }
              .masthead form input[type=submit] { padding: 4px 12px; font-size: 13px; font-family: inherit; }
              .info { color: #444; font-size: 13px; margin: 6px 0; }
              table.results { border-collapse: collapse; margin-top: 6px; }
              table.results th, table.results td { border: 1px solid #999; padding: 3px 7px;
                     text-align: left; vertical-align: top; font-size: 13px; }
              table.results th { background: #eee; font-weight: bold; white-space: nowrap; }
              table.results td.ident { font-size: 12px; }
              table.fields { border-collapse: collapse; margin: 10px 0; }
              table.fields th, table.fields td { border: 1px solid #999; padding: 4px 8px;
                     text-align: left; vertical-align: top; font-size: 14px; }
              table.fields th { background: #eee; font-weight: bold; white-space: nowrap; width: 200px; }
              pre.pem { border: 1px solid #999; padding: 8px 10px; font-size: 12px;
                     display: inline-block; margin: 6px 0; }
              .footer { color: #777; font-size: 12px; margin-top: 28px; border-top: 1px solid #ddd;
                     padding-top: 8px; }
            </style>
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

    private static String page(String title, String q, String body) {
        String valueAttr = q == null || q.isEmpty() ? "" : " value=\"" + esc(q) + "\"";
        return """
                <!DOCTYPE html>
                <html lang="ru">
                <head>
                <meta charset="utf-8">
                <title>%s | crtlogs.ru</title>
                %s
                </head>
                <body>
                <div class="masthead">
                <form action="/" method="get">
                <input type="text" name="q" placeholder="домен, например example.ru"%s>
                <input type="submit" value="Search">
                </form>
                <a class="logo" href="/">crtlogs.ru</a>\
                <span class="sub">Certificate Search &middot; российские CT-логи (Yandex, VK, Минцифры)</span>
                </div>
                %s
                <div class="footer">crtlogs.ru &mdash; поиск по российским CT-логам, формат API совместим с crt.sh.</div>
                %s
                </body>
                </html>
                """.formatted(esc(title), STYLE, valueAttr, body, METRIKA);
    }

    /** Страница результатов — таблица как у crt.sh. */
    static String resultsPage(String q, boolean excludeExpired, List<SearchRow> rows) {
        StringBuilder trs = new StringBuilder();
        for (SearchRow r : rows) {
            trs.append("""
                    <tr>
                    <td><a href="/?id=%d" target="_blank" rel="noopener">%d</a></td>
                    <td>%s</td>
                    <td>%s</td>
                    <td>%s</td>
                    <td>%s</td>
                    <td class="ident">%s</td>
                    <td>%s</td>
                    </tr>
                    """.formatted(r.id(), r.id(),
                    esc(r.entry_timestamp()),
                    esc(r.not_before()),
                    esc(r.not_after()),
                    esc(r.common_name()),
                    escMulti(r.name_value()),
                    esc(r.issuer_name())));
        }
        String encQ = URLEncoder.encode(q, StandardCharsets.UTF_8);
        String excludeLine = excludeExpired
                ? "<p class=\"info\">Показаны только действующие сертификаты &middot; "
                        + "<a href=\"/?q=" + encQ + "\">показать все</a></p>"
                : "<p class=\"info\"><a href=\"/?q=" + encQ
                        + "&amp;exclude=expired\">исключить истёкшие</a> (exclude=expired)</p>";
        String body = """
                <h2>Результаты поиска</h2>
                <p class="info">Запрос: <b>%s</b> &middot; найдено: <b>%d</b></p>
                %s
                <table class="results">
                <tr><th>crtlogs.ru ID</th><th>Logged At</th><th>Not Before</th><th>Not After</th>
                <th>Common Name</th><th>Matching Identifiers</th><th>Issuer Name</th></tr>
                %s
                </table>
                """.formatted(esc(q), rows.size(), excludeLine, trs);
        return page(q, q, body);
    }

    /** Страница сертификата — таблица полей + PEM, как /?id= у crt.sh. */
    static String certPage(CertDetails d) {
        String type = d.is_precert()
                ? "Precertificate (precert_entry)"
                : "Final certificate (x509_entry)";
        String body = """
                <h2>Сертификат crtlogs.ru ID %d</h2>
                <table class="fields">
                <tr><th>crtlogs.ru ID</th><td>%d</td></tr>
                <tr><th>Logged At</th><td>%s<br><span style="color:#666">%s #%d</span></td></tr>
                <tr><th>Тип записи</th><td>%s</td></tr>
                <tr><th>Not Before</th><td>%s</td></tr>
                <tr><th>Not After</th><td>%s</td></tr>
                <tr><th>Common Name</th><td>%s</td></tr>
                <tr><th>Matching Identifiers</th><td>%s</td></tr>
                <tr><th>Serial Number</th><td style="font-family:monospace">%s</td></tr>
                <tr><th>SHA-256 Fingerprint</th><td style="font-family:monospace">%s</td></tr>
                <tr><th>Subject</th><td>%s</td></tr>
                <tr><th>Issuer Name</th><td>%s<br><span style="color:#666">%s</span></td></tr>
                </table>
                <h3>Certificate</h3>
                <p class="info"><a href="/?d=%d">Скачать PEM</a> (?d=%d)</p>
                <pre class="pem">%s</pre>
                """.formatted(
                d.id(), d.id(),
                esc(d.entry_timestamp()), esc(d.log_url()), d.log_index(),
                type,
                esc(d.not_before()),
                esc(d.not_after()),
                esc(d.common_name()),
                escMulti(d.name_value()),
                esc(d.serial_number()),
                esc(d.sha256()),
                esc(d.subject_dn()),
                esc(d.issuer_name()),
                esc(d.issuer_dn()),
                d.id(), d.id(),
                esc(d.pem()));
        return page("Certificate " + d.id(), null, body);
    }

    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }

    /** Экранирование + переводы строк идентификаторов (name_value через '\n'). */
    private static String escMulti(String s) {
        return esc(s).replace("\n", "<br>");
    }
}
