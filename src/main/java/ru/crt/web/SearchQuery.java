package ru.crt.web;

import java.util.List;
import ru.crt.db.SortColumn;

/** Разобранные параметры поиска; умолчания живут здесь, а не в обработчике. */
public record SearchQuery(
        String q,
        boolean excludeExpired,
        SortColumn sort,
        boolean descending,
        int page) {

    /** Значение {@code exclude}, включающее фильтр действующих сертификатов. */
    public static final String EXCLUDE_EXPIRED = "expired";

    /** Умолчание crt.sh — по убыванию. */
    public static final boolean DEFAULT_DESCENDING = true;

    /** Параметры поиска; по этому же списку readable-url.js решает, что переносить в адрес. */
    public static final List<String> PARAMETERS = List.of("q", "exclude", "sort", "dir", "p");

    public static boolean excludesExpired(String exclude) {
        return EXCLUDE_EXPIRED.equals(exclude);
    }

    /** Параметры запроса crt.sh: {@code exclude}, {@code sort}, {@code dir}, {@code p}. */
    public static SearchQuery of(String q, String exclude, Integer sort, String dir, Integer page) {
        return new SearchQuery(
                q,
                excludesExpired(exclude),
                SortColumn.of(sort),
                dir == null ? DEFAULT_DESCENDING : !"asc".equalsIgnoreCase(dir),
                page != null && page >= 1 ? page : 1);
    }

    /** Совпадает ли направление с умолчанием — можно ли не называть его в адресе. */
    public boolean hasDefaultDirection() {
        return descending == DEFAULT_DESCENDING;
    }
}
