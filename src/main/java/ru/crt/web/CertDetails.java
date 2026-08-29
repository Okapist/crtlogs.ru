package ru.crt.web;

import java.util.List;

/** JSON-детали сертификата для UI: {@code GET /api/certs/{id}}. */
public record CertDetails(
        long id,
        Long issuer_ca_id,
        String issuer_name,
        String common_name,
        String name_value,
        List<String> domains,
        String log_url,
        long log_index,
        String sha256,
        String sha1,
        String serial_number,
        String subject_dn,
        String issuer_dn,
        String not_before,
        String not_after,
        String entry_timestamp,
        boolean is_precert,
        /** Все CT-логи, в которых обнаружен этот же сертификат (тот же DER/sha256). */
        List<LogOccurrence> occurrences,
        /** Дамп в духе openssl x509 -text — как на странице сертификата crt.sh. */
        String text,
        String pem) {

    /** Одно вхождение сертификата в CT-лог. */
    public record LogOccurrence(
            String log_url,
            String description,
            String operator,
            long log_index,
            String entry_timestamp) {
    }
}
