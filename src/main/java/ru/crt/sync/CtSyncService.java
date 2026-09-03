package ru.crt.sync;

import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import ru.crt.client.CtLogClient;
import ru.crt.client.KnownLog;
import ru.crt.config.AppProperties;
import ru.crt.db.CertificateRepository;
import ru.crt.x509.X509;

/**
 * Инкрементальная синхронизация всех логов из ctlog.json в локальную БД.
 * Логи обходятся по кругу срезами по {@link #BATCHES_PER_SLICE} пачек, поэтому большой
 * лог не занимает проход целиком. Ошибка одного лога (например, недоступный Минцифры)
 * не останавливает остальные; прогресс каждого лога сохраняется в ct_log.last_synced_index.
 */
@Service
public class CtSyncService {

    private static final Logger log = LoggerFactory.getLogger(CtSyncService.class);

    /**
     * Сколько пачек подряд берём у одного лога, прежде чем перейти к следующему.
     * Без такого среза первый же большой лог занимал бы проход целиком (догон
     * миллионов записей идёт часами), и остальные логи не синкались бы вообще.
     */
    private static final int BATCHES_PER_SLICE = 20;

    /**
     * Предел на один проход по времени. Без него проход идёт столько, сколько занимает
     * догон всех логов (на холодной базе — часами), а каждый следующий запуск по
     * расписанию только пишет «предыдущий ещё работает»: ct.sync-interval при этом
     * ничего не значит, свежий ctlog.json не перечитывается, и снаружи невозможно
     * отличить работающий проход от залипшего. По исчерпании бюджета проход
     * заканчивается на границе среза — прогресс каждого лога уже сохранён, и
     * следующий проход продолжает с того же места.
     */
    private static final Duration MAX_PASS_DURATION = Duration.ofMinutes(30);

    private final CtLogClient client;
    private final CertificateRepository repository;
    private final AppProperties props;
    private final AtomicBoolean running = new AtomicBoolean(false);
    /** Начало текущего прохода — только чтобы показать в логе, сколько он уже идёт. */
    private volatile long passStartedNanos;

    public CtSyncService(CtLogClient client, CertificateRepository repository, AppProperties props) {
        this.client = client;
        this.repository = repository;
        this.props = props;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        // первый синк не блокирует старт HTTP; отдельный поток, а не ForkJoinPool.commonPool:
        // синк — это блокирующие HTTP и JDBC, а commonPool на 1-2 ядрах имеет один воркер
        Thread worker = new Thread(this::syncAll, "ct-sync-initial");
        worker.setDaemon(true);
        worker.start();
    }

    @Scheduled(fixedDelayString = "${ct.sync-interval:PT10M}")
    public void scheduledSync() {
        syncAll();
    }

    public void syncAll() {
        syncAll(System.nanoTime() + MAX_PASS_DURATION.toNanos());
    }

    /** Пакет-private для теста с искусственным дедлайном. */
    void syncAll(long deadlineNanos) {
        if (!running.compareAndSet(false, true)) {
            log.info("Предыдущий проход синхронизации работает уже {} мин — пропуск",
                    Duration.ofNanos(System.nanoTime() - passStartedNanos).toMinutes());
            return;
        }
        passStartedNanos = System.nanoTime();
        try {
            Map<String, Long> progress = repository.loadSyncProgress();
            // круговой обход: каждый лог получает срез из BATCHES_PER_SLICE пачек и
            // уступает следующему, пока не догонит свой tree_size или не отвалится
            List<KnownLog> pending = knownLogs();
            while (!pending.isEmpty()) {
                if (System.nanoTime() - deadlineNanos >= 0) { // разность, а не сравнение: nanoTime переполняется
                    log.info("Бюджет прохода исчерпан, {} логов догоним следующим проходом",
                            pending.size());
                    return;
                }
                List<KnownLog> next = new ArrayList<>(pending.size());
                for (KnownLog ctLog : pending) {
                    if (syncSlice(ctLog, progress)) {
                        next.add(ctLog);
                    }
                }
                pending = next;
            }
        } catch (Exception e) {
            log.warn("Ошибка прохода синхронизации: {}", e.toString());
        } finally {
            running.set(false);
        }
    }

    /** Свежий ctlog.json; если недоступен — логи, уже сохранённые в БД. */
    private List<KnownLog> knownLogs() {
        try {
            List<KnownLog> fresh = client.fetchLogList(props.getLogListUrl());
            for (KnownLog l : fresh) {
                repository.upsertLog(l.url(), l.description(), l.operator());
            }
            return fresh;
        } catch (Exception e) {
            log.warn("ctlog.json недоступен ({}), работаем по логам из БД", e.toString());
            return repository.getKnownLogs();
        }
    }

    /**
     * Догоняет лог не более чем на {@link #BATCHES_PER_SLICE} пачек, сохраняя прогресс
     * в {@code progress} и в БД после каждой пачки.
     *
     * @return true, если лог догнан не полностью и его надо взять на следующем круге
     */
    private boolean syncSlice(KnownLog ctLog, Map<String, Long> progress) {
        long processed = progress.getOrDefault(ctLog.url(), 0L);
        try {
            long treeSize = client.getSth(ctLog.url()).treeSize();
            for (int batch = 0; batch < BATCHES_PER_SLICE; batch++) {
                if (processed >= treeSize) {
                    repository.markCaughtUp(ctLog.url(), treeSize);
                    return false;
                }
                long end = Math.min(processed + props.getBatchSize() - 1, treeSize - 1);
                List<CtLogClient.EntryDoc> entries = client.getEntries(ctLog.url(), processed, end);
                if (entries.isEmpty()) {
                    // сервер отдал меньше запрошенного до конца — продолжим в следующий проход
                    repository.markCaughtUp(ctLog.url(), treeSize);
                    return false;
                }
                List<CertificateRepository.NewCert> rows = new ArrayList<>(entries.size());
                for (int i = 0; i < entries.size(); i++) {
                    long index = processed + i;
                    try {
                        rows.add(toNewCert(ctLog.url(), index, entries.get(i)));
                    } catch (Exception e) {
                        log.warn("Запись {}#{} не разобрана, пропускаем: {}", ctLog.url(), index, e.toString());
                    }
                }
                int skipped = repository.insertCertificates(rows);
                processed += entries.size(); // продвигаемся на фактическое количество
                repository.advance(ctLog.url(), treeSize, processed);
                log.info("Синк {}: {}/{} записей{}", ctLog.description(), processed, treeSize,
                        skipped > 0 ? " (не вставлено " + skipped + ")" : "");
            }
            return true; // срез исчерпан, лог ещё не догнан
        } catch (Exception e) {
            log.warn("Лог {} недоступен: {}", ctLog.url(), e.toString());
            repository.markFailed(ctLog.url(), e.toString());
            return false; // до следующего прохода
        } finally {
            progress.put(ctLog.url(), processed);
        }
    }

    /**
     * Запись лога -> строка БД. Public static, чтобы тесты проверяли этот маппинг,
     * а не свою копию: иначе смена формата serial или таймзоны их не уронила бы.
     */
    public static CertificateRepository.NewCert toNewCert(String logUrl, long index,
                                                          CtLogClient.EntryDoc entry) throws Exception {
        CtEntryParser.ParsedEntry parsed = CtEntryParser.parse(entry.leafInput(), entry.extraData());
        X509Certificate cert = parsed.certificate();
        byte[] der = cert.getEncoded();
        return new CertificateRepository.NewCert(
                logUrl,
                index,
                der,
                X509.sha256Hex(der),
                serialHex(cert),
                cert.getSubjectX500Principal().getName(),
                cert.getIssuerX500Principal().getName(),
                utc(cert.getNotBefore().toInstant()),
                utc(cert.getNotAfter().toInstant()),
                utc(Instant.ofEpochMilli(parsed.entryTimestampMs())),
                parsed.precert(),
                parsed.domains());
    }

    /** Даты храним в UTC-локальном представлении, независимо от таймзоны машины. */
    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static String serialHex(X509Certificate cert) {
        return HexFormat.of().formatHex(cert.getSerialNumber().toByteArray());
    }

}
