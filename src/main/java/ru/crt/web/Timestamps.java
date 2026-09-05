package ru.crt.web;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;

/** Формат дат во всей выдаче: ISO без зоны, секунды всегда, миллисекунды при наличии — как у crt.sh. Здесь же datePart/timePart: они режут строку по смещениям этого формата. */
final class Timestamps {

    private static final DateTimeFormatter ISO = new DateTimeFormatterBuilder()
            .appendPattern("yyyy-MM-dd'T'HH:mm:ss")
            .appendFraction(ChronoField.NANO_OF_SECOND, 0, 3, true)
            .toFormatter();

    private Timestamps() {
    }

    static String format(LocalDateTime value) {
        return value == null ? null : value.format(ISO);
    }

    /** Дата метки: "2026-08-03". Пусто, если метки нет — даты в БД необязательны. */
    static String datePart(String iso) {
        return iso == null || iso.length() < 10 ? "" : iso.substring(0, 10);
    }

    /** Время той же метки: "09:04:23". Пусто, если метки нет. */
    static String timePart(String iso) {
        return iso == null || iso.length() < 19 ? "" : iso.substring(11, 19);
    }
}
