package ru.crt.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/** JSON-детали сертификата для UI: {@code GET /api/certs/{id}}; поля — как у {@link SearchRow}. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record CertDetails(
        long id,
        Long issuerCaId,
        String issuerName,
        String commonName,
        String nameValue,
        List<String> domains,
        String logUrl,
        long logIndex,
        String sha256,
        String sha1,
        String serialNumber,
        String subjectDn,
        String issuerDn,
        String notBefore,
        String notAfter,
        String entryTimestamp,
        @JsonProperty("is_precert") boolean precert,
        /** Все CT-логи, в которых обнаружен этот же сертификат (тот же DER/sha256). */
        List<LogOccurrence> occurrences,
        /** Дамп в духе openssl x509 -text — как на странице сертификата crt.sh. */
        String text,
        String pem) {

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record LogOccurrence(
            String logUrl,
            String description,
            String operator,
            long logIndex,
            String entryTimestamp) {
    }
}
