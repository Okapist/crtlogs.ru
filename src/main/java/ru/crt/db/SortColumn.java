package ru.crt.db;

/** Колонка сортировки в семантике crt.sh: индекс параметра {@code sort}, выражение ORDER BY и куски SQL для добора данных из других таблиц — всё в одном месте. */
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

    /** Что колонке нужно дополнительно выбрать и какое соединение подключить; пусто у всех, кроме издателя. */
    public String selectExtra() {
        return selectExtra;
    }

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
