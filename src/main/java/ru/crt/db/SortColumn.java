package ru.crt.db;

/**
 * Колонка сортировки выдачи в семантике crt.sh: индекс параметра {@code sort},
 * выражение ORDER BY над производной таблицей searchPage и куски SQL, которыми
 * колонка добирает себе данные из других таблиц. Все три вещи — одним местом, иначе
 * добавление колонки требовало бы правок в контроллере, репозитории и заголовках
 * таблицы, и ни одна забытая не ломала бы компиляцию.
 */
public enum SortColumn {

    ID(0, "t.id"),
    LOGGED_AT(1, "t.entry_ts"),
    NOT_BEFORE(2, "t.not_before"),
    ISSUER(3, "LOWER(t.issuer_name)",
            "ca.name AS issuer_name, ", "LEFT JOIN ca ON ca.id = c.issuer_ca_id"),
    NOT_AFTER(4, "t.not_after");

    /** Умолчание crt.sh; им же отвечаем на неизвестный индекс. */
    public static final SortColumn DEFAULT = LOGGED_AT;

    private final int index;
    private final String sql;
    private final String selectExtra;
    private final String joinSql;

    SortColumn(int index, String sql) {
        this(index, sql, "", "");
    }

    SortColumn(int index, String sql, String selectExtra, String joinSql) {
        this.index = index;
        this.sql = sql;
        this.selectExtra = selectExtra;
        this.joinSql = joinSql;
    }

    /** Значение параметра {@code sort} в ссылках crt.sh. */
    public int index() {
        return index;
    }

    /** Выражение ORDER BY над производной таблицей {@code t} из searchPage. */
    public String sql() {
        return sql;
    }

    /**
     * Что колонке нужно дополнительно выбрать во внутреннем SELECT (с запятой на конце)
     * и какое соединение для этого подключить. Пусто у всех, кроме издателя: ради него
     * подключается ca, а в остальных случаях это соединение отрабатывало бы на каждой
     * строке выборки ради колонки, которую внешний SELECT всё равно отбрасывает.
     *
     * <p>Оба куска SQL живут здесь, а не тернарниками у сборщика запроса: иначе колонка,
     * которой нужна ещё одна таблица, снова требовала бы правок в двух местах — ровно от
     * этого перечисление и заводилось.
     */
    public String selectExtra() {
        return selectExtra;
    }

    /** Соединение, дающее колонку из {@link #selectExtra()}; пусто, если оно не нужно. */
    public String joinSql() {
        return joinSql;
    }

    /** Колонка по индексу из запроса; null и неизвестные индексы дают {@link #DEFAULT}. */
    public static SortColumn of(Integer index) {
        if (index != null) {
            for (SortColumn column : values()) {
                if (column.index == index) {
                    return column;
                }
            }
        }
        return DEFAULT;
    }
}
