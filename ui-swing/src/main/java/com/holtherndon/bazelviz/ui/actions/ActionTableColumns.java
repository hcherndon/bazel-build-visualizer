package com.holtherndon.bazelviz.ui.actions;

import com.holtherndon.bazelviz.storage.entities.ActionRow;
import com.holtherndon.bazelviz.ui.inspect.EntityFormat;
import com.holtherndon.bazelviz.ui.table.ColumnSpec;
import java.util.List;

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
                new ColumnSpec<>("Exit", row -> EntityFormat.exitCode(row.effectiveExitCode())),
                new ColumnSpec<>("Output", ActionRow::primaryOutput));
    }

    /** Preferred pixel widths, in column order. */
    public static int[] widths() {
        return new int[] {320, 120, 100, 100, 60, 520};
    }
}
