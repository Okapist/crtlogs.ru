package ru.crt.db;

import java.time.LocalDateTime;

/** Строка ct_log: описание лога и состояние его синхронизации. */
public record LogRow(
        String url,
        String description,
        String operator,
        long lastSyncedIndex,
        long treeSize,
        String lastError,
        LocalDateTime updatedAt) {
}
