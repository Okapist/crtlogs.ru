package ru.crt.web;

/** Статус CT-лога и синхронизации: {@code GET /api/logs}. */
public record LogStatus(
        String url,
        String description,
        String operator,
        long last_synced_index,
        long tree_size,
        String last_error,
        String updated_at) {
}
