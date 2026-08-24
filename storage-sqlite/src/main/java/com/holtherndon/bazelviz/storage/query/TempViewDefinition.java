package com.holtherndon.bazelviz.storage.query;

import java.util.Objects;

/**
 * One saved temporary view: a name and the tabular SELECT it stands for.
 *
 * <p>This is the persisted half of the Query card's saved views. A temp view
 * lives in one connection's temp schema and dies with the connection, so a
 * "saved" view is really a definition that is <em>replayed</em> — by
 * {@code AdHocQueries#applyTempViews} — against every new query connection
 * (each query tab has its own). The body is stored as the SELECT alone rather
 * than the whole {@code CREATE} statement so that renaming a view is a rename
 * in the store, not a rewrite of SQL.
 *
 * @param name the view's name, unquoted; quoting is the applier's job
 * @param select the tabular body — validated against {@link ReadOnlySql} at
 *     apply time, not here, so a definition edited on disk is refused with a
 *     reason instead of exploding a constructor during listing
 */
public record TempViewDefinition(String name, String select) {

    public TempViewDefinition {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(select, "select");
        if (name.isBlank()) {
            throw new IllegalArgumentException("a temp view needs a name");
        }
        if (select.isBlank()) {
            throw new IllegalArgumentException("a temp view needs a body");
        }
    }
}
