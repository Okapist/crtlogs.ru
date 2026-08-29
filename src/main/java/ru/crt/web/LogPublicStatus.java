package ru.crt.web;

/**
 * Публичный (без токена) статус синхронизации лога для блока под кнопкой поиска.
 * Тексты ошибок не включаются — только факт их наличия; подробности — в /api/logs.
 */
public record LogPublicStatus(
        String url,
        String description,
        String operator,
        long last_synced_index,
        long tree_size,
        boolean ok,
        String updated_at) {
}
