package ru.crt.web;

import java.util.List;

/** Страница выдачи: строки и общее число совпадений; число страниц выводится из total. */
public record SearchPage(List<SearchRow> rows, int total) {

    /** Сколько всего страниц по {@link SearchService#PAGE_SIZE}; 0, если совпадений нет. */
    public int totalPages() {
        return (total + SearchService.PAGE_SIZE - 1) / SearchService.PAGE_SIZE;
    }
}
