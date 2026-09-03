package ru.crt.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.Month;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import ru.crt.AbstractIntegrationTest;
import ru.crt.client.KnownLog;

/**
 * Сквозной тест хранилища на in-memory H2: вставка реальных записей лога, поиск в
 * семантике crt.sh, идемпотентность вставки, exclude=expired, сортировка и пейджинг,
 * состояние логов. Внешний вид выдачи (имена полей, формат дат, CN) сюда не входит —
 * это дело веб-слоя, см. SearchServiceTest и CertDetailsServiceTest.
 */
class CertificateRepositoryTest extends AbstractIntegrationTest {

    /** Домен, на котором висят синтетические строки сортировки, — не пересекается с фикстурой. */
    private static final String SORT_DOMAIN = "sort.example";

    /** Строки выдачи по домену: связка searchPage + certRows, как её делает поиск. */
    private List<CertRow> rows(String q, boolean excludeExpired) {
        return repository.certRows(
                repository.searchPage(q, excludeExpired, SortColumn.ID, false, 1, 100).ids());
    }

    /** Общее число совпадений по запросу. */
    private int total(String q, boolean excludeExpired) {
        return repository.searchPage(q, excludeExpired, SortColumn.ID, false, 1, 100).total();
    }

    /**
     * Строка выдачи собирается из certificate, ca и cert_domain — все три должны доехать.
     * Значения полей в контракте crt.sh закрепляет SearchServiceTest.
     */
    @Test
    void searchFindsInsertedCertificateWithFilledRow() throws Exception {
        insertFixture();

        List<CertRow> found = rows(FIXTURE_DOMAIN, false);
        assertEquals(1, found.size());
        CertRow row = found.getFirst();
        assertNotNull(row.issuerCaId(), "id издателя — из ca");
        assertEquals("CN=cateam.ru", row.subjectDn());
        assertEquals(List.of("cateam.ru", "www.cateam.ru"), row.domains(),
                "идентификаторы — из cert_domain, по алфавиту");
        assertNull(row.der(), "строкам поиска DER не нужен — это десятки мегабайт на широком шаблоне");
    }

    /** Поиск в семантике crt.sh: поддомены ищутся шаблоном, символы LIKE — пользователя. */
    @Test
    void wildcardSearchFindsSubdomains() throws Exception {
        insertFixture();
        assertEquals(1, rows("%." + FIXTURE_DOMAIN, false).size(),
                "%.cateam.ru должен находить www.cateam.ru");
        assertEquals(1, rows("%te%", false).size(),
                "% с обеих сторон — поиск по вхождению: 'te' сидит внутри cateam.ru");
        assertTrue(rows("ateam.ru", false).isEmpty(),
                "без шаблона совпадение точное, а не по подстроке");
    }

    /** Пачки перечитываются после ошибок, поэтому повторная вставка обязана быть пустой операцией. */
    @Test
    void repeatedInsertOfTheSameEntriesAddsNothing() throws Exception {
        List<CertificateRepository.NewCert> rows = fixtureCerts();
        repository.insertCertificates(rows);
        int afterFirst = total(FIXTURE_DOMAIN, false);

        assertEquals(0, repository.insertCertificates(rows), "вторая вставка не добавила строк");

        cacheManager.getCache("search").clear(); // иначе счётчик вернётся из кеша, а не из БД
        assertEquals(afterFirst, total(FIXTURE_DOMAIN, false),
                "уникальность (log_url, log_index) удержала число строк");
    }

    @Test
    void certificateIsLoadedByIdWithDerAndMissingIdGivesNull() throws Exception {
        CertificateRepository.NewCert inserted = insertFixture();

        CertRow row = repository.certRow(idsOf(FIXTURE_DOMAIN).getFirst());
        assertNotNull(row);
        assertEquals(inserted.sha256(), row.sha256());
        assertEquals(LOG_URL, row.logUrl());
        assertEquals(0, row.logIndex());
        assertFalse(row.precert());
        assertNotNull(row.der(), "деталям сертификата DER нужен — из него строится дамп и PEM");

        assertNull(repository.certRow(-1));
    }

    @Test
    void emptyBatchTouchesNothing() {
        assertEquals(0, repository.insertCertificates(List.of()));
    }

    @Test
    void excludeExpiredFiltersOutOldCerts() throws Exception {
        CertificateRepository.NewCert original = fixtureCerts().getFirst();
        // второй, истёкший сертификат на том же домене: срок задан вручную, sha256 делаем
        // другим (в реальности один DER не может иметь разные сроки — иначе дедупликация
        // по sha256 схлопнула бы строки)
        CertificateRepository.NewCert expired = withValidity(original, 999, "ee",
                LocalDateTime.of(2019, Month.JANUARY, 1, 0, 0),
                LocalDateTime.of(2020, Month.JANUARY, 1, 0, 0)); // истёк
        repository.insertCertificates(List.of(original));
        repository.insertCertificates(List.of(expired));

        assertEquals(2, rows(FIXTURE_DOMAIN, false).size());
        assertEquals(1, rows(FIXTURE_DOMAIN, true).size());
        assertEquals(1, total(FIXTURE_DOMAIN, true), "счётчик учитывает тот же фильтр");
    }

    /**
     * Срок действия хранится как UTC-локальное время, поэтому и граница «истёк» должна
     * быть UTC. Со сравнением по часам машины (CURRENT_TIMESTAMP) выдача теряла бы всё,
     * что истекает в пределах смещения зоны, — тесты идут в Asia/Kolkata (+05:30),
     * так что сертификат «ещё два часа действителен» такую ошибку ловит.
     */
    @Test
    void expiredFilterComparesWithUtcNotWithTheServerClock() throws Exception {
        CertificateRepository.NewCert base = fixtureCerts().getFirst();
        LocalDateTime nowUtc = LocalDateTime.now(java.time.ZoneOffset.UTC);
        CertificateRepository.NewCert endsSoon = copyOf(base, LOG_URL, 301, otherSha256(base, "c1"),
                nowUtc.minusYears(1), nowUtc.plusHours(2), base.entryTs(),
                base.issuerDn(), Set.of("soon.example"));
        CertificateRepository.NewCert endedRecently = copyOf(base, LOG_URL, 302, otherSha256(base, "c2"),
                nowUtc.minusYears(1), nowUtc.minusHours(2), base.entryTs(),
                base.issuerDn(), Set.of("past.example"));
        repository.insertCertificates(List.of(endsSoon, endedRecently));

        assertEquals(1, total("soon.example", true), "истекает через два часа — ещё действует");
        assertEquals(0, total("past.example", true), "истёк два часа назад");
        assertEquals(1, total("past.example", false), "без фильтра виден и истёкший");
    }

    /** Счётчик и страница лежат одной записью — почему, см. javadoc searchPage. */
    @Test
    void countAndPageShareOneCacheEntry() throws Exception {
        insertFixture();

        CertificateRepository.SearchResult found =
                repository.searchPage(FIXTURE_DOMAIN, false, SortColumn.LOGGED_AT, true, 1, 100);
        assertTrue(found.total() > 0);
        assertFalse(found.ids().isEmpty());

        Object cached = cacheManager.getCache("search").get(FIXTURE_DOMAIN + "::LOGGED_AT:true:1:100").get();
        assertEquals(found, cached, "в кеше лежит та же пара, а не два независимых значения");
    }

    @Test
    void certRowsReturnOnlyRequestedIdsInTheGivenOrder() throws Exception {
        repository.insertCertificates(List.of(twinOf(insertFixture(), 500, "dd")));
        List<Long> ids = idsOf(FIXTURE_DOMAIN);
        assertEquals(2, ids.size());

        assertEquals(ids.reversed(),
                repository.certRows(ids.reversed()).stream().map(CertRow::id).toList(),
                "порядок id сохраняется — его задаёт searchPage");
        assertEquals(List.of(ids.getFirst()),
                repository.certRows(List.of(ids.getFirst())).stream().map(CertRow::id).toList());
        assertTrue(repository.certRows(List.of()).isEmpty());
    }

    /**
     * IN-список режется на куски по MAX_IDS_PER_QUERY: H2 ограничивает число параметров,
     * и страница из тысяч id иначе падала бы на самом запросе.
     */
    @Test
    void queryByIdsSplitsHugeIdListIntoChunks() throws Exception {
        insertFixture();
        long real = idsOf(FIXTURE_DOMAIN).getFirst();

        List<Long> ids = new ArrayList<>();
        for (long i = 1; i <= 2500; i++) {
            ids.add(-i); // несуществующие id: нужен только размер списка
        }
        ids.add(real);

        List<CertRow> rows = repository.certRows(ids);
        assertEquals(1, rows.size(), "несуществующие id пропускаются, запрос не падает");
        assertEquals(real, rows.getFirst().id());
    }

    @Test
    void sameCertificateFoundInMultipleLogs() throws Exception {
        CertificateRepository.NewCert original = fixtureCerts().getFirst();
        // тот же DER/sha256, но другой лог и записан на час раньше — как повторная подача
        // в другой CT-лог; домены в реальности берутся из DER, поэтому у копий совпадают
        CertificateRepository.NewCert inOtherLog = copyOf(original, "https://other.example/log/", 42,
                original.sha256(), original.notBefore(), original.notAfter(),
                original.entryTs().minusHours(1), original.issuerDn(), original.domains());
        // не inLog(): здесь важна и другая метка времени попадания в лог
        repository.insertCertificates(List.of(original, inOtherLog));

        List<CertRow> found = rows(FIXTURE_DOMAIN, false);
        assertEquals(1, found.size(), "дубликат в нескольких логах — одна строка поиска, как у crt.sh");
        assertEquals("https://other.example/log/", found.getFirst().logUrl(),
                "показывается самое раннее попадание в лог");

        List<OccurrenceRow> occurrences = repository.occurrences(original.sha256());
        assertEquals(2, occurrences.size(), "вхождения перечисляются из обоих логов");
        assertTrue(occurrences.stream().anyMatch(o -> "https://other.example/log/".equals(o.logUrl())));
    }

    /**
     * Одна запись, которую отвергает БД, не должна ронять пачку: иначе транзакция
     * откатывается целиком, last_synced_index не двигается и лог перестаёт синкаться
     * навсегда — следующий проход скачивает и роняет тот же диапазон.
     */
    @Test
    void badRecordIsSkippedAndRestOfBatchIsInserted() throws Exception {
        CertificateRepository.NewCert good = fixtureCerts().getFirst();
        // домен длиннее cert_domain.domain VARCHAR(255) — такую запись БД не примет
        CertificateRepository.NewCert bad =
                withDomains(good, 12345, "bb", Set.of("a".repeat(300) + ".example"));

        assertEquals(1, repository.insertCertificates(List.of(bad, good)),
                "битая запись пропускается поштучно, а не роняет всю пачку");

        assertFalse(rows(FIXTURE_DOMAIN, false).isEmpty(),
                "соседняя запись из той же пачки должна быть вставлена");
        // ca издателя откатился вместе с пачкой: если бы его id остался в кеше,
        // все следующие вставки этого издателя падали бы на внешнем ключе
        assertEquals(0, repository.insertCertificates(List.of(good)),
                "повторная вставка того же издателя после отката должна проходить");
    }

    @Test
    void caIdsAreSharedPerIssuerDn() throws Exception {
        repository.insertCertificates(fixtureCerts());
        Set<Long> issuerIds = new TreeSet<>();
        for (CertRow r : rows(FIXTURE_DOMAIN, false)) {
            issuerIds.add(r.issuerCaId());
        }
        assertEquals(1, issuerIds.size(), "все сертификаты одного издателя — один issuer_ca_id");
    }

    // --- сортировка и пейджинг ---

    /**
     * Три строки на одном домене, у которых каждый порядок сортировки свой: если
     * индекс sort перепутает колонку, результат совпадёт с другим ожидаемым порядком
     * и тест это увидит. Возвращает id в порядке вставки (то есть в порядке sort=0 asc).
     */
    private List<Long> insertSortableRows() throws Exception {
        CertificateRepository.NewCert base = fixtureCerts().getFirst();
        List<CertificateRepository.NewCert> rows = List.of(
                copyOf(base, LOG_URL, 101, otherSha256(base, "a1"),
                        LocalDateTime.of(2024, Month.FEBRUARY, 1, 0, 0),
                        LocalDateTime.of(2030, Month.JANUARY, 1, 0, 0),
                        LocalDateTime.of(2026, Month.MARCH, 1, 0, 0), "CN=Bravo", Set.of(SORT_DOMAIN)),
                copyOf(base, LOG_URL, 102, otherSha256(base, "a2"),
                        LocalDateTime.of(2024, Month.JANUARY, 1, 0, 0),
                        LocalDateTime.of(2030, Month.MARCH, 1, 0, 0),
                        LocalDateTime.of(2026, Month.FEBRUARY, 1, 0, 0), "CN=Charlie", Set.of(SORT_DOMAIN)),
                copyOf(base, LOG_URL, 103, otherSha256(base, "a3"),
                        LocalDateTime.of(2024, Month.MARCH, 1, 0, 0),
                        LocalDateTime.of(2030, Month.FEBRUARY, 1, 0, 0),
                        LocalDateTime.of(2026, Month.JANUARY, 1, 0, 0), "CN=Alpha", Set.of(SORT_DOMAIN)));
        assertEquals(0, repository.insertCertificates(rows));
        return page(SortColumn.ID, false);
    }

    @Test
    void sortIndexSelectsTheColumnOfCrtSh() throws Exception {
        List<Long> byId = insertSortableRows();
        assertEquals(3, byId.size());
        Long a = byId.get(0);
        Long b = byId.get(1);
        Long c = byId.get(2);

        assertEquals(List.of(a, b, c), page(SortColumn.of(0), false), "0 = ID");
        assertEquals(List.of(c, b, a), page(SortColumn.of(1), false), "1 = Logged At (entry_ts)");
        assertEquals(List.of(b, a, c), page(SortColumn.of(2), false), "2 = Not Before");
        assertEquals(List.of(c, a, b), page(SortColumn.of(3), false), "3 = Issuer Name");
        assertEquals(List.of(a, c, b), page(SortColumn.of(4), false), "4 = Not After");
        // неизвестный индекс сортировки ведёт себя как Logged At — как и умолчание crt.sh
        assertEquals(SortColumn.LOGGED_AT, SortColumn.of(42), "неизвестный индекс — сортировка по умолчанию");
        assertEquals(SortColumn.LOGGED_AT, SortColumn.of(null), "как и отсутствующий параметр");
    }

    @Test
    void descendingReversesEveryOrder() throws Exception {
        insertSortableRows();
        for (SortColumn sort : SortColumn.values()) {
            assertEquals(page(sort, false).reversed(), page(sort, true),
                    "сортировка " + sort + " по убыванию — тот же порядок наоборот");
        }
    }

    @Test
    void pagingCutsTheWindowInSql() throws Exception {
        List<Long> byId = insertSortableRows();
        assertEquals(List.of(byId.get(0)), window(1, 1));
        assertEquals(List.of(byId.get(1)), window(2, 1));
        assertEquals(List.of(byId.get(2)), window(3, 1));
        assertTrue(window(99, 1).isEmpty(), "за последней страницей пусто");
        assertTrue(window(1, 0).isEmpty(), "нулевой размер страницы не должен доходить до БД");
        assertEquals(3, window(1, 10).size(), "страница шире выборки — вся выборка");
        assertEquals(3, total(SORT_DOMAIN, false));
    }

    private List<Long> page(SortColumn sort, boolean descending) {
        return repository.searchPage(SORT_DOMAIN, false, sort, descending, 1, 10).ids();
    }

    private List<Long> window(int page, int pageSize) {
        return repository.searchPage(SORT_DOMAIN, false, SortColumn.ID, false, page, pageSize).ids();
    }

    // --- шаблоны поиска ---

    @Test
    void wildcardPatternsAreRecognised() {
        assertTrue(CertificateRepository.isWildcard("%.example.ru"));
        assertTrue(CertificateRepository.isWildcard("exampl_.ru"), "_ — тоже шаблон LIKE");
        assertFalse(CertificateRepository.isWildcard("example.ru"));
    }

    // --- состояние логов ---

    @Test
    void logStateIsUpsertedAndReported() {
        repository.upsertLog(LOG_URL, "Test log", "Tester");
        repository.upsertLog("https://second.example/log/", "Second", "Another");
        repository.upsertLog(LOG_URL, "Test log v2", "Tester"); // MERGE обновляет, а не дублирует

        List<KnownLog> known = repository.getKnownLogs();
        assertEquals(2, known.size(), "повторный upsert не создаёт вторую строку");
        assertEquals("Test log v2", known.stream()
                .filter(l -> l.url().equals(LOG_URL)).findFirst().orElseThrow().description());

        repository.advance(LOG_URL, 100L, 50L);
        assertEquals(50L, repository.loadSyncProgress().get(LOG_URL));

        LogRow status = repository.logRows().stream()
                .filter(s -> s.url().equals(LOG_URL)).findFirst().orElseThrow();
        assertEquals(100L, status.treeSize());
        assertEquals(50L, status.lastSyncedIndex());
        assertNull(status.lastError());
        assertNotNull(status.updatedAt(), "updated_at проставляется при каждом обновлении состояния");
        assertEquals("Tester", status.operator());
        assertTrue(Duration.between(status.updatedAt(), LocalDateTime.now(java.time.ZoneOffset.UTC))
                        .abs().toMinutes() < 1,
                "updated_at — UTC, как и все остальные даты выдачи; с часами машины он в"
                        + " одном JSON с ними разъезжался бы на смещение зоны");
    }

    /**
     * Прогресс не должен откатываться от ошибки: markFailed не трогает ни индекс, ни
     * tree_size — иначе лог перекачивал бы уже загруженное.
     */
    @Test
    void errorKeepsProgressAndIsTruncatedToColumnWidth() {
        repository.upsertLog(LOG_URL, "Test log", "Tester");
        repository.advance(LOG_URL, 100L, 50L);

        repository.markFailed(LOG_URL, "x".repeat(5000));
        LogRow failed = repository.logRows().getFirst();
        assertEquals(1024, failed.lastError().length(), "ct_log.last_error — VARCHAR(1024)");
        assertEquals(50L, failed.lastSyncedIndex(), "прогресс сохраняется");
        assertEquals(100L, failed.treeSize());

        repository.markCaughtUp(LOG_URL, 200L);
        LogRow recovered = repository.logRows().getFirst();
        assertNull(recovered.lastError(), "успешный проход снимает ошибку");
        assertEquals(50L, recovered.lastSyncedIndex(), "догнанный лог не двигает индекс");
        assertEquals(200L, recovered.treeSize());
    }

    @Test
    void logStatusesAreOrderedByOperatorThenUrl() {
        repository.upsertLog("https://b.example/", "B", "Zeta");
        repository.upsertLog("https://a.example/", "A", "Zeta");
        repository.upsertLog("https://c.example/", "C", "Alpha");
        assertEquals(List.of("https://c.example/", "https://a.example/", "https://b.example/"),
                repository.logRows().stream().map(LogRow::url).toList());
    }
}
