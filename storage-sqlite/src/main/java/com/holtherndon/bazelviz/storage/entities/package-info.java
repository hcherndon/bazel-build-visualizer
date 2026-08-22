/**
 * The normalized entity layer: the writer that fills schema v2 and the queries
 * the Phase 3 views read it back through.
 *
 * <p>Two rules run through everything here.
 *
 * <p><b>Keyset, never OFFSET.</b> Every paged query takes an anchor row and
 * asks for rows on one side of it, including under a user-chosen sort — see
 * {@link com.holtherndon.bazelviz.storage.entities.Keyset} for how a sort
 * column that can be NULL is handled, and what silently disappears without it.
 *
 * <p><b>Unknown reaches the view as unknown.</b> The row records use
 * {@code Optional} and {@code OptionalLong} rather than sentinel numbers, and
 * where an absence has a cause worth showing — an action Bazel never timed —
 * the cause travels with it.
 */
package com.holtherndon.bazelviz.storage.entities;
