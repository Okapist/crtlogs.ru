package ru.crt.web;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/** Строка результата поиска с именами полей crt.sh; snake_case даёт {@link JsonNaming} на record'е. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record SearchRow(
        long id,
        Long issuerCaId,
        String issuerName,
        String commonName,
        String nameValue,
        String entryTimestamp,
        String notBefore,
        String notAfter,
        String serialNumber,
        int resultCount) {
}
