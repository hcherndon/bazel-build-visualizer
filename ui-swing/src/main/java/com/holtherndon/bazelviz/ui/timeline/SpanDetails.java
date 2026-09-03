package com.holtherndon.bazelviz.ui.timeline;

import com.holtherndon.bazelviz.ui.nav.EntityRef;
import java.util.List;
import java.util.Objects;

/**
 * What the timeline's side inspector shows for one clicked segment.
 *
 * <p>A value, on purpose: the controller builds one on a worker thread from
 * whatever the database says and hands it to the EDT, so the view — which
 * {@code TimelinePaintIsolationTest} forbids from holding anything that can
 * reach a database — displays plain strings and dispatches plain
 * {@link EntityRef}s. Absent facts are absent from {@link #lines} rather than
 * rendered as zeroes, per the project rule.
 *
 * @param title what was clicked, e.g. the action's primary output or the
 *     target's label
 * @param lines one fact per line, already worded; only facts something
 *     actually reported
 * @param refs the entities this segment is, for the embedded
 *     {@code EntityActions} strip; empty offers no actions, honestly
 */
public record SpanDetails(String title, List<String> lines, List<EntityRef> refs) {

    public SpanDetails {
        Objects.requireNonNull(title, "title");
        lines = List.copyOf(lines);
        refs = List.copyOf(refs);
    }
}
