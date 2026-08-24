package com.holtherndon.bazelviz.ui.actions;

import com.holtherndon.bazelviz.storage.entities.ActionRow;
import com.holtherndon.bazelviz.storage.entities.ActionSort;
import com.holtherndon.bazelviz.ui.inspect.EntityFormat;
import com.holtherndon.bazelviz.ui.table.ColumnSpec;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The actions table's columns.
 *
 * <p>Ordered by how a user reads a row: what it built, what kind of work it
 * was, how it ended, how long it took, when. The output path is last and
 * widest because it is the identity but rarely the thing being scanned for.
 *
 * <p>Every extractor is a field read or a format call. They run on the EDT
 * during paint, so nothing here may query, parse or allocate at scale.
 */
public final class ActionTableColumns {

    private ActionTableColumns() {}

    public static List<ColumnSpec<ActionRow>> columns() {
        return List.of(
                // An action with no label -- the workspace-status action on
                // three of the four supported versions -- shows an em dash
                // rather than a blank, so it reads as "no target" and not as a
                // rendering gap.
                new ColumnSpec<>("Target", row -> EntityFormat.text(row.label())),
                new ColumnSpec<>("Mnemonic", row -> EntityFormat.text(row.mnemonic())),
                new ColumnSpec<>("Outcome", row -> row.outcome().name()),
                // Formatted from the Measured value, so a duration Bazel could
                // not report renders as an em dash here and explains itself in
                // the inspector.
                new ColumnSpec<>("Duration", row -> EntityFormat.duration(row.duration())),
                // The process's code or nothing. Bazel's own exit_code field is
                // 1 for every failure whatever the command returned, and a
                // failure with no spawn -- a genrule that produced no output --
                // has no process code at all.
                new ColumnSpec<>("Exit", row -> EntityFormat.exitCode(row.processExitCode())),
                // Where it ran and whether it was a cache hit. Both come from
                // the execution log, so both are empty until one is imported --
                // and stay empty for the two thirds of actions that never spawn
                // a subprocess (K1). An em dash there is the truth, not a gap.
                new ColumnSpec<>("Runner", ActionTableColumns::runner),
                new ColumnSpec<>("Cached", ActionTableColumns::cached),
                new ColumnSpec<>("Output", ActionRow::primaryOutput));
    }

    /**
     * Where the action ran.
     *
     * <p>An action with several spawns says how many rather than naming one of
     * them: two spawns that ran under different runners give the action no
     * single runner, and showing the first would present one spawn's answer as
     * the action's.
     */
    static String runner(ActionRow row) {
        ActionRow.Execution execution = row.execution();
        if (execution.isAmbiguous()) {
            return execution.attempts() + " spawns";
        }
        return EntityFormat.text(execution.runner());
    }

    static String cached(ActionRow row) {
        ActionRow.Execution execution = row.execution();
        if (execution.isAbsent() || execution.isAmbiguous()) {
            return EntityFormat.text(java.util.Optional.empty());
        }
        return execution.cacheHit().map(hit -> hit ? "yes" : "no")
                .orElseGet(() -> EntityFormat.text(java.util.Optional.empty()));
    }

    /** Preferred pixel widths, in column order. */
    public static int[] widths() {
        return new int[] {320, 120, 100, 100, 60, 130, 70, 520};
    }

    /**
     * The columns with a backend ordering behind them: header name → the
     * {@link ActionSort} the storage layer pages under. Exactly these four,
     * because these are the orderings {@code SchemaV2} indexes for keyset
     * paging — a header that sorted anything else would have to either scan
     * the table or sort only the rows already fetched, and both would lie.
     */
    private static final Map<String, ActionSort> SORTABLE = Map.of(
            "Target", ActionSort.LABEL,
            "Mnemonic", ActionSort.MNEMONIC,
            "Outcome", ActionSort.OUTCOME,
            "Duration", ActionSort.DURATION);

    /** The backend ordering behind a column header, when it has one. */
    public static Optional<ActionSort> sortFor(String columnId) {
        return Optional.ofNullable(SORTABLE.get(columnId));
    }

    /**
     * The header id that stands for {@code sort} — the column name when a
     * column carries that ordering, else the enum's own name, so a toolbar
     * choice with no column of its own (start time, arrival descending)
     * still round-trips through the persisted column state.
     */
    public static String sortKeyFor(ActionSort sort) {
        for (Map.Entry<String, ActionSort> entry : SORTABLE.entrySet()) {
            if (entry.getValue() == sort) {
                return entry.getKey();
            }
        }
        return sort.name();
    }

    /**
     * Why a column without a backend ordering does not sort — the header
     * tooltip's text. Honest about the reason: the table is keyset-paged
     * over up to five million actions, so any ordering must come from a
     * database index, and no index orders these.
     */
    public static String sortUnavailableExplanation(String columnId) {
        return "This column does not sort: the table is paged straight from the"
                + " database at up-to-five-million-action scale, and no index orders"
                + " actions by " + columnId + ". Sorting only the rows already"
                + " fetched would misrepresent the rest.";
    }
}
