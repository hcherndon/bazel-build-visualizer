package com.holtherndon.bazelviz.storage.entities;

/**
 * The orderings the actions table offers.
 *
 * <h2>All six page at the same cost, and that took work</h2>
 *
 * <p>Measured at 1,000,000 actions (docs/performance.md, {@code EntityScaleSpike}),
 * one page of 200 rows costs 0.30–0.67 ms under every sort, at the head of the
 * table and at the tail alike. See {@link Keyset} for what makes that true and
 * for the two natural-looking alternatives that cost 18.8 ms and 26 ms instead.
 *
 * <p>What does differ is the one-off anchor scan a view pays when the user
 * picks a sort: 52–61 ms for the four that order by a column of
 * {@code actions}, and 242–363 ms for {@link #MNEMONIC} and {@link #LABEL},
 * which order by text in a dictionary table and so need a sort no index over
 * {@code actions} can supply. That scan runs off the EDT with the previous rows
 * still on screen.
 */
public enum ActionSort {

    /**
     * Row id, which is arrival order. The default: it is the order the events
     * came in, it needs no index beyond the primary key, and it is the only
     * ordering with no unknowns in it.
     */
    ARRIVAL("a.id", "Arrival", false),

    /** When the action started, where that is known. */
    START_TIME("a.start_micros", "Start", true),

    /**
     * How long the action took.
     *
     * <p>Computed rather than stored, and deliberately NULL when the timing is
     * not trustworthy — including the Bazel 8.4.x case where both timestamps
     * are present and equal. Sorting by a stored zero there would put every
     * action in the build at the top of the "fastest" list.
     *
     * <p>{@code SchemaV2} indexes this exact expression, which is what lets a
     * page seek rather than sort.
     */
    DURATION(
            "(CASE WHEN a.duration_unknown_reason IS NULL"
                    + " THEN a.end_micros - a.start_micros END)",
            "Duration",
            true),

    /** The action type, via the interned mnemonic. */
    MNEMONIC("m.value", "Mnemonic", true),

    /** The owning target, via the interned label. */
    LABEL("l.value", "Target", true),

    /** Succeeded before failed, or the reverse. */
    OUTCOME("a.outcome", "Outcome", false);

    private final String column;
    private final String title;
    private final boolean nullable;

    ActionSort(String column, String title, boolean nullable) {
        this.column = column;
        this.title = title;
        this.nullable = nullable;
    }

    /** The SQL expression to order by, qualified with the query's aliases. */
    String column() {
        return column;
    }

    /**
     * Whether the column can be NULL.
     *
     * <p>Lets the pager skip the null-side query for a column that has no null
     * side, rather than issuing one that can only ever return nothing.
     */
    boolean nullable() {
        return nullable;
    }

    public String title() {
        return title;
    }
}
