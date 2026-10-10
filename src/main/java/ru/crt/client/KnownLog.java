package ru.crt.client;

/** Лог из ctlog.json вместе с именем оператора. */
public record KnownLog(String url, String description, String operator) {
}
