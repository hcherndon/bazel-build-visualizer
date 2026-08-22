package com.holtherndon.bazelviz.storage.entities;

/**
 * The orderings the actions table offers.
 *
 * <p>Each names a column the schema indexes, because a sort over an unindexed
 * column at five million rows is a full scan per page — which would turn
 * clicking a column header into a stall the user has no way to interpret.
 */
public enum ActionSort {

    /**
     * Row id, which is arrival order. The default: it is the order the events
     * came in, it needs no index beyond the primary key, and it is the only
     * ordering with no unknowns in it.
     */
    ARRIVAL("a.id", "Arrival"),

    /** When the action started, where that is known. */
    START_TIME("a.start_micros", "Start"),

    /**
     * How long the action took.
     *
     * <p>Computed rather than stored, and deliberately NULL when the timing is
     * not trustworthy — including the Bazel 8.4.x case where both timestamps
     * are present and equal. Sorting by a stored zero there would put every
     * action in the build at the top of the "fastest" list.
     */
    DURATION(
            "(CASE WHEN a.duration_unknown_reason IS NULL"
                    + " THEN a.end_micros - a.start_micros END)",
            "Duration"),

    /** The action type, via the interned mnemonic. */
    MNEMONIC("m.value", "Mnemonic"),

    /** The owning target, via the interned label. */
    LABEL("l.value", "Target"),

    /** Succeeded before failed, or the reverse. */
    OUTCOME("a.outcome", "Outcome");

    private final String column;
    private final String title;

    ActionSort(String column, String title) {
        this.column = column;
        this.title = title;
    }

    /** The SQL expression to order by, qualified with the query's aliases. */
    String column() {
        return column;
    }

    public String title() {
        return title;
    }
}
