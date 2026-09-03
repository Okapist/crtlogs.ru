package ru.crt.web;

import java.util.List;

/**
 * Страница выдачи: строки и общее число совпадений. Номер текущей страницы лежит в
 * {@link SearchQuery}, а число страниц выводится из {@code total} — держать их ещё и
 * здесь значило бы иметь два источника правды в одном ответе.
 */
public record SearchPage(List<SearchRow> rows, int total) {

    /** Сколько всего страниц по {@link SearchService#PAGE_SIZE}; 0, если совпадений нет. */
    public int totalPages() {
        return (total + SearchService.PAGE_SIZE - 1) / SearchService.PAGE_SIZE;
    }
}
