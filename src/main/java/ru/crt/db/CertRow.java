package ru.crt.db;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Строка certificate вместе с доменами из cert_domain и именем издателя из ca —
 * то, что хранилище знает о сертификате. Даты отдаются как лежат в БД (UTC-локальное
 * представление, см. {@code CtSyncService.utc}); формат выдачи задаёт веб-слой, а не
 * репозиторий.
 *
 * @param der DER сертификата; null у строк поиска — там он не нужен, а на широком
 *            шаблоне это были бы десятки мегабайт BLOB'ов
 */
public record CertRow(
        long id,
        Long issuerCaId,
        String issuerName,
        String logUrl,
        long logIndex,
        String sha256,
        String serial,
        String subjectDn,
        String issuerDn,
        LocalDateTime notBefore,
        LocalDateTime notAfter,
        LocalDateTime entryTs,
        boolean precert,
        List<String> domains,
        byte[] der) {
}
