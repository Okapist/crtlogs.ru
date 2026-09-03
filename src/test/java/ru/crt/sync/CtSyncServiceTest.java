package ru.crt.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.Month;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import ru.crt.TestFixtures;
import ru.crt.client.CtLogClient;
import ru.crt.client.KnownLog;
import ru.crt.config.AppProperties;
import ru.crt.db.CertificateRepository;

/**
 * Проход синхронизации на моках клиента и хранилища: догон лога, круговой обход
 * логов срезами, изоляция ошибок и продвижение прогресса. В сеть и в БД тест не
 * ходит — проверяется именно логика обхода.
 */
class CtSyncServiceTest {

    private static final String LOG_A = "https://a.example/log/";
    private static final String LOG_B = "https://b.example/log/";

    /** Запись record'а неизменяема, поэтому один экземпляр обслуживает все стабы. */
    private static final CtLogClient.EntryDoc REAL_ENTRY = TestFixtures.entries().getFirst();

    private CtLogClient client;
    private CertificateRepository repository;
    private AppProperties props;
    private CtSyncService service;

    @BeforeEach
    void setUp() {
        client = mock(CtLogClient.class);
        repository = mock(CertificateRepository.class);
        props = new AppProperties();
        service = new CtSyncService(client, repository, props);
        when(repository.loadSyncProgress()).thenReturn(new HashMap<>());
    }

    private void logList(String... urls) throws Exception {
        when(client.fetchLogList(anyString())).thenReturn(
                Arrays.stream(urls).map(url -> new KnownLog(url, "log " + url, "Operator")).toList());
    }

    @Test
    void logListIsRefreshedAndStoredBeforeSync() throws Exception {
        logList(LOG_A);
        when(client.getSth(LOG_A)).thenReturn(new CtLogClient.Sth(0, 1));

        service.syncAll();

        verify(repository).upsertLog(LOG_A, "log " + LOG_A, "Operator");
        // лог уже догнан: только отметка tree_size и снятие ошибки
        verify(repository).markCaughtUp(LOG_A, 0L);
        verify(client, never()).getEntries(anyString(), anyLong(), anyLong());
    }

    @Test
    void catchesUpLogBatchByBatchAndSavesProgress() throws Exception {
        logList(LOG_A);
        props.setBatchSize(2);
        when(client.getSth(LOG_A)).thenReturn(new CtLogClient.Sth(3, 1));
        when(client.getEntries(LOG_A, 0, 1)).thenReturn(List.of(REAL_ENTRY, REAL_ENTRY));
        when(client.getEntries(LOG_A, 2, 2)).thenReturn(List.of(REAL_ENTRY));

        service.syncAll();

        verify(repository, times(2)).insertCertificates(any());
        verify(repository).advance(LOG_A, 3L, 2L);
        verify(repository).advance(LOG_A, 3L, 3L);
        // после догона — ещё одна отметка без сдвига индекса
        verify(repository).markCaughtUp(LOG_A, 3L);
    }

    /**
     * Ответ короче запрошенного диапазона — нормальная ситуация: сервер отдаёт, сколько
     * готов. Прогресс двигается на фактическое число записей, иначе следующая пачка
     * пропустила бы хвост.
     */
    @Test
    void progressAdvancesByActualEntryCount() throws Exception {
        logList(LOG_A);
        props.setBatchSize(100);
        when(client.getSth(LOG_A)).thenReturn(new CtLogClient.Sth(100, 1));
        when(client.getEntries(LOG_A, 0, 99)).thenReturn(List.of(REAL_ENTRY, REAL_ENTRY));
        when(client.getEntries(LOG_A, 2, 99)).thenReturn(List.of());

        service.syncAll();

        verify(repository).advance(LOG_A, 100L, 2L);
        // пустой ответ завершает срез, а не крутит цикл вхолостую
        verify(repository).markCaughtUp(LOG_A, 100L);
        verify(client, times(2)).getEntries(anyString(), anyLong(), anyLong());
    }

    /**
     * Круговой обход: большой лог не занимает проход целиком, а уступает следующему
     * через BATCHES_PER_SLICE пачек — иначе догон миллионов записей одного лога
     * оставил бы остальные несинхронизированными вообще.
     */
    @Test
    void bigLogYieldsToTheNextOneAfterOneSlice() throws Exception {
        logList(LOG_A, LOG_B);
        props.setBatchSize(1);
        when(client.getSth(anyString())).thenReturn(new CtLogClient.Sth(25, 1));
        List<String> calls = new ArrayList<>();
        when(client.getEntries(anyString(), anyLong(), anyLong())).thenAnswer(invocation -> {
            calls.add(invocation.getArgument(0) + "#" + invocation.getArgument(1));
            return List.of(REAL_ENTRY);
        });

        service.syncAll();

        assertEquals(50, calls.size(), "25 пачек на лог");
        assertTrue(calls.subList(0, 20).stream().allMatch(c -> c.startsWith(LOG_A)),
                "первый срез — 20 пачек первого лога");
        assertTrue(calls.subList(20, 40).stream().allMatch(c -> c.startsWith(LOG_B)),
                "затем управление переходит ко второму логу");
        assertEquals(LOG_A + "#20", calls.get(40), "второй круг продолжает с того же места");
        assertEquals(LOG_B + "#24", calls.getLast());
    }

    @Test
    void failingLogIsRecordedAndDoesNotStopTheOthers() throws Exception {
        logList(LOG_A, LOG_B);
        when(client.getSth(LOG_A)).thenThrow(new IOException("лог недоступен"));
        when(client.getSth(LOG_B)).thenReturn(new CtLogClient.Sth(0, 1));

        service.syncAll();

        ArgumentCaptor<String> error = ArgumentCaptor.forClass(String.class);
        verify(repository).markFailed(eq(LOG_A), error.capture());
        assertTrue(error.getValue().contains("лог недоступен"), "текст ошибки виден в /api/logs");
        verify(repository).markCaughtUp(LOG_B, 0L);
    }

    /**
     * Битая запись не должна останавливать лог: она пропускается, а прогресс всё равно
     * двигается на размер пачки — иначе следующий проход скачивал бы тот же диапазон.
     */
    @Test
    void unparseableEntryIsSkippedButProgressStillMoves() throws Exception {
        logList(LOG_A);
        props.setBatchSize(2);
        when(client.getSth(LOG_A)).thenReturn(new CtLogClient.Sth(2, 1));
        when(client.getEntries(LOG_A, 0, 1)).thenReturn(
                List.of(new CtLogClient.EntryDoc("!!!не-base64!!!", null), REAL_ENTRY));

        service.syncAll();

        ArgumentCaptor<List<CertificateRepository.NewCert>> inserted = ArgumentCaptor.captor();
        verify(repository).insertCertificates(inserted.capture());
        assertEquals(1, inserted.getValue().size(), "разобралась только одна запись из двух");
        assertEquals(1L, inserted.getValue().getFirst().logIndex(), "индекс — позиция в логе, а не в пачке");
        verify(repository).advance(LOG_A, 2L, 2L);
    }

    @Test
    void unavailableLogListFallsBackToLogsFromDatabase() throws Exception {
        when(client.fetchLogList(anyString())).thenThrow(new IOException("ctlog.json недоступен"));
        when(repository.getKnownLogs()).thenReturn(List.of(new KnownLog(LOG_A, "из БД", "Operator")));
        when(client.getSth(LOG_A)).thenReturn(new CtLogClient.Sth(0, 1));

        service.syncAll();

        verify(repository).getKnownLogs();
        verify(client).getSth(LOG_A);
        verify(repository, never()).upsertLog(anyString(), anyString(), anyString());
    }

    /**
     * Проход ограничен по времени. Без границы догон холодной базы держал бы проход
     * часами: расписание всё это время только писало бы «предыдущий ещё работает»,
     * свежий ctlog.json не перечитывался бы, а отличить работающий проход от залипшего
     * стало бы нечем. Дедлайн передаётся явно — иначе тест ждал бы полчаса.
     */
    @Test
    void passStopsWhenItsTimeBudgetIsSpent() throws Exception {
        logList(LOG_A, LOG_B);
        when(client.getSth(anyString())).thenReturn(new CtLogClient.Sth(1_000_000, 1));

        service.syncAll(System.nanoTime() - 1); // бюджет исчерпан ещё до первого круга

        verify(client, never()).getEntries(anyString(), anyLong(), anyLong());
        verify(repository).upsertLog(eq(LOG_A), anyString(), anyString());
    }

    /** Проход, закончившийся по бюджету, обязан освободить флаг: иначе синка больше нет. */
    @Test
    void spentBudgetDoesNotBlockTheNextPass() throws Exception {
        logList(LOG_A);
        when(client.getSth(LOG_A)).thenReturn(new CtLogClient.Sth(0, 1));

        service.syncAll(System.nanoTime() - 1);
        service.syncAll();

        verify(client).getSth(LOG_A);
        verify(repository).markCaughtUp(LOG_A, 0L);
    }

    /** Второй проход поверх идущего только удвоил бы нагрузку на логи и БД. */
    @Test
    void concurrentPassIsSkippedWhileTheFirstOneRuns() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(client.fetchLogList(anyString())).thenAnswer(invocation -> {
            started.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return List.of();
        });

        Thread first = new Thread(service::syncAll, "sync-test");
        first.start();
        assertTrue(started.await(5, TimeUnit.SECONDS), "первый проход должен успеть начаться");

        service.scheduledSync(); // расписание не должно запускать второй проход

        release.countDown();
        first.join(5_000);
        assertFalse(first.isAlive());
        verify(client, times(1)).fetchLogList(anyString());
    }

    /** Первый синк не должен блокировать старт HTTP — он уходит в отдельный поток. */
    @Test
    void applicationReadyStartsSyncInBackground() throws Exception {
        logList(LOG_A);
        when(client.getSth(LOG_A)).thenReturn(new CtLogClient.Sth(0, 1));

        service.onApplicationReady();

        verify(client, timeout(5_000)).fetchLogList(anyString());
    }

    // --- маппинг записи лога в строку БД ---

    @Test
    void x509EntryIsMappedToDatabaseRow() throws Exception {
        CertificateRepository.NewCert cert =
                CtSyncService.toNewCert(LOG_A, 42, REAL_ENTRY);

        assertEquals(LOG_A, cert.logUrl());
        assertEquals(42, cert.logIndex());
        assertFalse(cert.precert());
        assertEquals("CN=cateam.ru", cert.subjectDn());
        assertEquals("CN=TCI ECDSA B1 16-280", cert.issuerDn());
        assertEquals("018a8ce5854e250482138e0d", cert.serial(), "serial — hex в нижнем регистре");
        // сами домены — контракт CtEntryParser (см. CtEntryParserTest), здесь важно, что
        // они доезжают до строки БД, а не теряются по дороге
        assertFalse(cert.domains().isEmpty());
        // даты приводятся к UTC независимо от таймзоны машины
        assertEquals(LocalDateTime.of(2026, Month.JANUARY, 19, 10, 26, 30), cert.notBefore());
        assertEquals(LocalDateTime.of(2027, Month.JANUARY, 19, 10, 26, 30), cert.notAfter());
        assertEquals(LocalDateTime.of(2026, Month.JANUARY, 19, 10, 45, 44, 153_000_000), cert.entryTs(),
                "entry_ts — timestamp из MerkleTreeLeaf, миллисекунды epoch");
        assertEquals(64, cert.sha256().length());
    }

    @Test
    void precertEntryIsMarkedAsPrecert() throws Exception {
        CertificateRepository.NewCert cert =
                CtSyncService.toNewCert(LOG_A, 1, TestFixtures.entries().get(1));

        assertTrue(cert.precert());
        assertEquals("CN=khaos.today", cert.subjectDn(), "у precert сертификат берётся из extra_data");
        assertEquals(LocalDateTime.of(2025, Month.JANUARY, 1, 9, 9, 41, 122_000_000), cert.entryTs());
    }
}
