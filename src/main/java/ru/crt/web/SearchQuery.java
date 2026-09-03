package ru.crt.web;

import java.util.List;
import ru.crt.db.SortColumn;

/**
 * Разобранные параметры поиска: во что контроллер превращает строки запроса, прежде
 * чем отдать их {@link SearchService}. Умолчания живут здесь, а не в обработчике,
 * чтобы второй вход в поиск не заводил свою копию.
 */
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

    /**
     * Имена параметров, из которых собирается поиск. Всё прочее в адресе — не наше
     * (utm-метки, yclid и подобная разметка ссылок), и знать об этом надо не только
     * здесь: {@code readable-url.js} по этому же списку решает, что переносить в
     * переписанный адрес, а что заменить.
     */
    public static final List<String> PARAMETERS = List.of("q", "exclude", "sort", "dir", "p");

    /**
     * Включает ли это значение {@code exclude} фильтр действующих. Отдельным методом,
     * потому что спрашивают об этом двое: сам разбор запроса и страница отказа, которой
     * нужно вернуть чекбокс в исходное положение.
     */
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

    /**
     * Совпадает ли направление с умолчанием, то есть можно ли не называть его в адресе.
     * Спрашиваем здесь, а не сравниваем с {@code true} у вызывающего: умолчание задаётся
     * строкой выше, и переверни его кто-нибудь — ссылки молча стали бы обещать обратный
     * порядок, ничего при этом не сломав при компиляции.
     */
    public boolean hasDefaultDirection() {
        return descending == DEFAULT_DESCENDING;
    }
}
