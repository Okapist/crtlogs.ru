package ru.crt.web;

/**
 * Строка результата поиска с точными именами полей crt.sh
 * ({@code GET /?q=...&output=json}). Даты — ISO_LOCAL_DATE_TIME без зоны,
 * name_value — идентификаторы через '\n'.
 */
public record SearchRow(
        long id,
        Long issuer_ca_id,
        String issuer_name,
        String common_name,
        String name_value,
        String entry_timestamp,
        String not_before,
        String not_after,
        String serial_number,
        int result_count) {
}
