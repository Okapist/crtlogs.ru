package ru.crt.web;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * Строка результата поиска с точными именами полей crt.sh
 * ({@code GET /?q=...&output=json}). Даты — ISO_LOCAL_DATE_TIME без зоны,
 * name_value — идентификаторы через '\n'.
 *
 * <p>Поля названы по-джавовски, а snake_case контракта даёт {@link JsonNaming} на самом
 * record'е — чтобы контракт не зависел от настроек общего ObjectMapper'а.
 */
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
