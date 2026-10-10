package ru.crt.db;

import java.time.LocalDateTime;

/** Вхождение сертификата в CT-лог: строка certificate, соединённая с ct_log. */
public record OccurrenceRow(
        String logUrl,
        String description,
        String operator,
        long logIndex,
        LocalDateTime entryTs) {
}
