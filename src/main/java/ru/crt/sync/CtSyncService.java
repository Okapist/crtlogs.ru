package ru.crt.sync;

import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
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

/**
 * Инкрементальная синхронизация всех логов из ctlog.json в локальную БД.
 * Ошибка одного лога (например, недоступный Минцифры) не останавливает остальные;
 * прогресс каждого лога сохраняется в ct_log.last_synced_index.
 */
@Service
public class CtSyncService {

    private static final Logger log = LoggerFactory.getLogger(CtSyncService.class);

    private final CtLogClient client;
    private final CertificateRepository repository;
    private final AppProperties props;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public CtSyncService(CtLogClient client, CertificateRepository repository, AppProperties props) {
        this.client = client;
        this.repository = repository;
        this.props = props;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        CompletableFuture.runAsync(this::syncAll); // первый синк не блокирует старт HTTP
    }

    @Scheduled(fixedDelayString = "${ct.sync-interval:PT10M}")
    public void scheduledSync() {
        syncAll();
    }

    public boolean isRunning() {
        return running.get();
    }

    public void syncAll() {
        if (!running.compareAndSet(false, true)) {
            log.info("Предыдущий проход синхронизации ещё работает — пропуск");
            return;
        }
        try {
            Map<String, Long> progress = repository.loadSyncProgress();
            for (KnownLog ctLog : knownLogs()) {
                syncOneLog(ctLog, progress.getOrDefault(ctLog.url(), 0L));
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

    private void syncOneLog(KnownLog ctLog, long startIndex) {
        try {
            long treeSize = client.getSth(ctLog.url()).tree_size();
            long processed = startIndex;
            while (processed < treeSize) {
                long end = Math.min(processed + props.getBatchSize() - 1, treeSize - 1);
                List<CtLogClient.EntryDoc> entries = client.getEntries(ctLog.url(), processed, end);
                if (entries.isEmpty()) {
                    break; // сервер отдал меньше запрошенного до конца — продолжим в следующий проход
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
                repository.insertCertificates(rows);
                processed += entries.size(); // продвигаемся на фактическое количество
                repository.updateLogState(ctLog.url(), treeSize, processed, null);
                log.info("Синк {}: {}/{} записей", ctLog.description(), processed, treeSize);
            }
            repository.updateLogState(ctLog.url(), treeSize, null, null); // обновить tree_size / снять ошибку
        } catch (Exception e) {
            String message = truncate(e.toString(), 1000);
            log.warn("Лог {} недоступен: {}", ctLog.url(), message);
            repository.updateLogState(ctLog.url(), null, null, message);
        }
    }

    private CertificateRepository.NewCert toNewCert(String logUrl, long index, CtLogClient.EntryDoc entry)
            throws Exception {
        CtEntryParser.ParsedEntry parsed = CtEntryParser.parse(entry.leaf_input(), entry.extra_data());
        X509Certificate cert = parsed.certificate();
        byte[] der = cert.getEncoded();
        return new CertificateRepository.NewCert(
                logUrl,
                index,
                der,
                CertificateRepository.sha256Hex(der),
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

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
