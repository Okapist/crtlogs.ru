package ru.crt.web;

import ru.crt.db.LogRow;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * Публичный (без токена) статус синхронизации лога для блока под кнопкой поиска.
 * Тексты ошибок не включаются — только факт их наличия; подробности — в /api/logs.
 *
 * <p>Имена полей JSON остаются snake_case: их читает static/logs-status.js.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record LogPublicStatus(
        String url,
        String description,
        String operator,
        long lastSyncedIndex,
        long treeSize,
        boolean ok,
        String updatedAt) {

    /** Та же строка ct_log, что и в {@link LogStatus}, но текст ошибки сведён к флагу. */
    public static LogPublicStatus of(LogRow row) {
        return new LogPublicStatus(row.url(), row.description(), row.operator(),
                row.lastSyncedIndex(), row.treeSize(), row.lastError() == null,
                Timestamps.format(row.updatedAt()));
    }
}
