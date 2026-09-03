package ru.crt.web;

import ru.crt.db.LogRow;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/** Статус CT-лога и синхронизации: {@code GET /api/logs}. Имена полей JSON — snake_case. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record LogStatus(
        String url,
        String description,
        String operator,
        long lastSyncedIndex,
        long treeSize,
        String lastError,
        String updatedAt) {

    /** Проекция строки ct_log: даты получают внешний формат, остальное — как есть. */
    public static LogStatus of(LogRow row) {
        return new LogStatus(row.url(), row.description(), row.operator(), row.lastSyncedIndex(),
                row.treeSize(), row.lastError(), Timestamps.format(row.updatedAt()));
    }
}
