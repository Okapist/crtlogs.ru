package ru.crt.web;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import ru.crt.AbstractIntegrationTest;
import ru.crt.db.CertificateRepository;

/**
 * HTTP-контракт целиком: маршруты crt.sh ({@code /?q=}, {@code /?d=}, {@code /?id=}),
 * наши расширения ({@code /api/*}) и то, доходят ли до запроса параметры сортировки
 * и пейджинга. Лимит запросов в тестовой конфигурации выключен, токен — test-token
 * (src/test/resources/application.yml).
 *
 * <p>Здесь проверяется поведение эндпоинтов: состав полей JSON закреплён отдельно в
 * {@link JsonFieldNamesTest}, разметка страниц — в {@link HtmlViewTest}, содержимое
 * выдачи — в {@link SearchServiceTest}, а какая колонка стоит за каким индексом
 * сортировки — в CertificateRepositoryTest.
 */
class ApiControllerTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mvc;

    /** Поиск по домену фикстуры в JSON — база, к которой тесты дописывают свой параметр. */
    private static MockHttpServletRequestBuilder searchJson() {
        return get("/").param("q", FIXTURE_DOMAIN).param("output", "json");
    }

    @Test
    void rootWithoutSearchParamsServesTheUi() throws Exception {
        mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("text/html;charset=UTF-8"))
                .andExpect(content().string(containsString("Certificate Search")))
                .andExpect(content().string(containsString("<FORM id=\"searchForm\"")));
    }

    @Test
    void searchReturnsJsonOnlyWithOutputJson() throws Exception {
        insertFixture();

        mvc.perform(searchJson())
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].common_name").value(FIXTURE_DOMAIN))
                .andExpect(jsonPath("$[0].name_value").value(FIXTURE_DOMAIN + "\nwww." + FIXTURE_DOMAIN))
                .andExpect(jsonPath("$[0].result_count").value(1));

        mvc.perform(get("/").param("q", FIXTURE_DOMAIN))
                .andExpect(status().isOk())
                .andExpect(content().contentType("text/html;charset=UTF-8"))
                .andExpect(content().string(containsString("Identity Search")))
                .andExpect(content().string(containsString(FIXTURE_DOMAIN)));

        // output с любым другим значением — по-прежнему страница, а не 404 на несуществующий маршрут
        mvc.perform(get("/").param("q", FIXTURE_DOMAIN).param("output", "html"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("text/html;charset=UTF-8"));
    }

    /**
     * Пустой и слишком общий запросы отбиваются кодом 400. Какие именно шаблоны
     * считаются слишком общими, перечисляет CertificateRepositoryTest.
     */
    @Test
    void emptyAndTooBroadQueriesAreRejected() throws Exception {
        mvc.perform(get("/").param("q", "   ")).andExpect(status().isBadRequest());
        mvc.perform(get("/").param("q", "%")).andExpect(status().isBadRequest());
        mvc.perform(get("/").param("q", "%.ru").param("output", "json")).andExpect(status().isOk());
        mvc.perform(get("/").param("q", "%vk%").param("output", "json")).andExpect(status().isOk());
    }

    /**
     * Отказ объясняется посетителю на той же главной, а не отдаётся кодом 400 в пустой
     * whitelabel-странице: причину Spring по умолчанию наружу не выносит, и без своего
     * обработчика единственное, что видит человек, — «400».
     */
    @Test
    void rejectedSearchExplainsItselfOnThePageAndInJson() throws Exception {
        mvc.perform(get("/").param("q", "%a%"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType("text/html;charset=UTF-8"))
                .andExpect(content().string(containsString("Слишком общий шаблон")))
                .andExpect(content().string(containsString("%vk%")))
                .andExpect(content().string(containsString("value=\"%a%\"")))
                .andExpect(content().string(containsString("<FORM id=\"searchForm\"")));

        mvc.perform(get("/").param("q", "%a%").param("output", "json"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error", containsString("Слишком общий шаблон")));
    }

    /** Остальные отказы — тем же способом: текстом, а не кодом ответа. */
    @Test
    void everyOtherFailureAlsoCarriesAReadableMessage() throws Exception {
        // несуществующий сертификат — на всех трёх входах
        mvc.perform(get("/").param("id", "999999"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(containsString("сертификат 999999 не найден")));
        mvc.perform(get("/").param("d", "999999"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(containsString("сертификат 999999 не найден")));
        mvc.perform(get("/api/certs/999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error", containsString("не найден")));

        // нечисловой id: раньше Spring отвечал 400 вообще без тела
        mvc.perform(get("/").param("id", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("должен быть целым числом")));

        // несуществующий путь
        mvc.perform(get("/no-such-page"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(containsString("Такой страницы нет")));

        // отказ самого MVC: код у него свой (405), и подменять его пятисоткой нельзя —
        // иначе чужой кривой запрос выглядел бы как наш сбой
        mvc.perform(post("/"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(content().string(containsString("Запрос отклонён")));
    }

    @Test
    void downloadReturnsPemAsAttachmentNamedBySha256() throws Exception {
        CertificateRepository.NewCert cert = insertFixture();

        mvc.perform(get("/").param("d", String.valueOf(idsOf(FIXTURE_DOMAIN).getFirst())))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/x-pem-file"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + cert.sha256() + ".crt\""))
                .andExpect(content().string(startsWith("-----BEGIN CERTIFICATE-----")));
    }

    @Test
    void certificatePageAndJsonDetailsAnswerForTheSameId() throws Exception {
        insertFixture();
        long id = idsOf(FIXTURE_DOMAIN).getFirst();

        mvc.perform(get("/").param("id", String.valueOf(id)))
                .andExpect(status().isOk())
                .andExpect(content().contentType("text/html;charset=UTF-8"))
                .andExpect(content().string(containsString("Certificate Search")))
                .andExpect(content().string(containsString(FIXTURE_DOMAIN)));

        mvc.perform(get("/api/certs/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value((int) id))
                .andExpect(jsonPath("$.common_name").value(FIXTURE_DOMAIN))
                .andExpect(jsonPath("$.domains", containsInAnyOrder(FIXTURE_DOMAIN, "www." + FIXTURE_DOMAIN)))
                .andExpect(jsonPath("$.pem").exists())
                .andExpect(jsonPath("$.occurrences[0].log_url").value(LOG_URL));
    }

    /**
     * sort и dir доходят до запроса: какая колонка стоит за каким индексом, проверяет
     * тест хранилища, здесь важно, что параметры не теряются по дороге и dir работает.
     */
    @Test
    void sortAndDirectionParametersReachTheQuery() throws Exception {
        repository.insertCertificates(List.of(twinOf(insertFixture(), 500, "dd")));
        List<Long> ids = idsOf(FIXTURE_DOMAIN);

        mvc.perform(searchJson().param("sort", "0").param("dir", "asc"))
                .andExpect(jsonPath("$[0].id").value(ids.get(0).intValue()))
                .andExpect(jsonPath("$[1].id").value(ids.get(1).intValue()));

        mvc.perform(searchJson().param("sort", "0").param("dir", "desc"))
                .andExpect(jsonPath("$[0].id").value(ids.get(1).intValue()))
                .andExpect(jsonPath("$[1].id").value(ids.get(0).intValue()));
    }

    /** Смещение обрезается по общему числу совпадений, иначе OFFSET уезжал бы за конец выборки. */
    @Test
    void pageBeyondTheLastOneIsEmptyAndNonPositivePagesMeanTheFirst() throws Exception {
        insertFixture();

        mvc.perform(searchJson().param("p", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
        for (String nonPositive : new String[]{"0", "-5"}) {
            mvc.perform(searchJson().param("p", nonPositive))
                    .andExpect(jsonPath("$", hasSize(1)));
        }
    }

    @Test
    void expiredCertificatesAreFilteredOutByExcludeParameter() throws Exception {
        CertificateRepository.NewCert base = insertFixture();
        repository.insertCertificates(List.of(withValidity(base, 777, "ee",
                LocalDateTime.of(2019, 1, 1, 0, 0), LocalDateTime.of(2020, 1, 1, 0, 0))));

        mvc.perform(searchJson()).andExpect(jsonPath("$", hasSize(2)));
        mvc.perform(searchJson().param("exclude", "expired"))
                .andExpect(jsonPath("$", hasSize(1)));
    }

    /** /api/logs закрыт токеном, /api/status открыт, но текста ошибки не отдаёт — только флаг ok. */
    @Test
    void logsEndpointIsClosedByTokenWhileStatusIsPublicAndHidesErrorText() throws Exception {
        repository.upsertLog(LOG_URL, "Test log", "Tester");
        repository.advance(LOG_URL, 100L, 50L);
        mvc.perform(get("/api/status")).andExpect(jsonPath("$[0].ok").value(true));

        repository.markFailed(LOG_URL, "boom");

        mvc.perform(get("/api/logs")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/logs").header(HttpHeaders.AUTHORIZATION, "Bearer test-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].url").value(LOG_URL))
                .andExpect(jsonPath("$[0].last_error").value("boom"));

        mvc.perform(get("/api/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].url").value(LOG_URL))
                .andExpect(jsonPath("$[0].tree_size").value(100))
                .andExpect(jsonPath("$[0].last_synced_index").value(50))
                .andExpect(jsonPath("$[0].ok").value(false))
                .andExpect(jsonPath("$[0].last_error").doesNotExist());
    }

    /**
     * Виджет статуса опрашивает эндпоинт с каждой открытой страницы, поэтому ответ
     * кешируемый и не тратит бюджет лимита частоты (см. RateLimitFilter).
     */
    @Test
    void publicStatusIsCacheable() throws Exception {
        mvc.perform(get("/api/status"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "max-age=15, public"));
    }
}
