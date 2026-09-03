package ru.crt.web;

import java.util.List;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;
import ru.crt.db.CertRow;
import ru.crt.db.CertificateRepository;
import ru.crt.x509.X509;

/**
 * Детали сертификата для страницы и для {@code /api/certs/{id}}: строка БД плюс
 * вхождения в логи, отпечатки, openssl-дамп и PEM.
 *
 * <p>Сборка живёт здесь, а не в репозитории: контракт JSON, рендер дампа и кодирование
 * PEM — представление, а не хранение.
 */
@Service
public class CertDetailsService {

    private final CertificateRepository repository;
    /** Дамп по отпечатку — единственная дорогая часть деталей; политика в {@code CacheConfig}. */
    private final Cache certText;

    public CertDetailsService(CertificateRepository repository, CacheManager caches) {
        this.repository = repository;
        this.certText = caches.getCache("certText");
    }

    /** Детали сертификата; null, если такого id нет. Имена — в форме показа ({@link DisplayNames}). */
    public CertDetails details(long id) {
        CertRow c = repository.certRow(id);
        if (c == null) {
            return null;
        }
        List<String> domains = DisplayNames.domains(c.domains());
        return new CertDetails(
                c.id(),
                c.issuerCaId(),
                c.issuerName(),
                DisplayNames.commonName(c.subjectDn()),
                DisplayNames.nameValue(domains),
                domains,
                c.logUrl(),
                c.logIndex(),
                c.sha256(),
                X509.sha1Hex(c.der()),
                c.serial(),
                c.subjectDn(),
                c.issuerDn(),
                Timestamps.format(c.notBefore()),
                Timestamps.format(c.notAfter()),
                Timestamps.format(c.entryTs()),
                c.precert(),
                occurrences(c.sha256()),
                dump(c),
                X509.toPem(c.der()));
    }

    /** Файл для скачивания ({@code /?d=}): без дампа и вхождений, которые ему не нужны. */
    public record PemFile(String sha256, String pem) {
    }

    /** PEM сертификата; null, если такого id нет. */
    public PemFile pem(long id) {
        CertRow c = repository.certRow(id);
        return c == null ? null : new PemFile(c.sha256(), X509.toPem(c.der()));
    }

    /**
     * Дамп сертификата или null, если DER не разобрался. Null тоже кешируется: на
     * битой записи разбор всё равно не начнёт получаться, а страница показывает PEM.
     */
    private String dump(CertRow c) {
        return certText.get(c.sha256(), () -> CertTextDump.render(c.der()));
    }

    /** Вхождения этого же сертификата (тот же sha256) во все синхронизированные логи. */
    private List<CertDetails.LogOccurrence> occurrences(String sha256) {
        return repository.occurrences(sha256).stream()
                .map(o -> new CertDetails.LogOccurrence(o.logUrl(), o.description(), o.operator(),
                        o.logIndex(), Timestamps.format(o.entryTs())))
                .toList();
    }
}
