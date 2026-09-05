package ru.crt.db;

import java.time.LocalDateTime;
import java.util.List;

/** Строка certificate вместе с доменами и именем издателя. Даты — как в БД (UTC-локально); der — null у строк поиска, где DER не нужен. */
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
