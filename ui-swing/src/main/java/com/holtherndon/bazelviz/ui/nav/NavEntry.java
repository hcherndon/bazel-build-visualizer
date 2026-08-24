package com.holtherndon.bazelviz.ui.nav;

import java.util.Locale;

/**
 * The navigation sidebar entries in display order (plan section 17.1), each
 * mapped to the CardLayout card it selects and the phase whose UI deliverable
 * replaces its placeholder. Arrival phases come from the per-phase "UI
 * deliverable" lists in docs/product-plan.md section 24 — change them there
 * first, then here.
 *
 * <h2>Eleven entries, and not the plan's eleven</h2>
 *
 * <p>Three departures from plan 17.1's list, all from use rather than from
 * design:
 *
 * <ul>
 *   <li><b>Console and Capture are one {@code BUILD} entry.</b> They were
 *       always read together — the capture's phase and counters answer "is it
 *       still going" and the console answers "what is it saying" — and
 *       splitting them made the user switch cards mid-build to follow one
 *       build. The card now carries the capture status as a header strip above
 *       the console.
 *   <li><b>{@code FAILURES} is {@code ERRORS}.</b> The view lists Bazel's
 *       console diagnostics alongside failed actions and targets, and a
 *       compiler warning printed on stderr is not a failure. "Errors" covers
 *       what is actually on the card; "Failures" promised something narrower
 *       than what it showed.
 *   <li><b>{@code QUERY} is not in plan 17.1 at all.</b> Perfetto's query page
 *       is the one thing it does that nothing here replaced: SQL over the
 *       captured data, for the question nobody built a view for. Its arrival
 *       phase is recorded as 10 because that is the last plan phase and the
 *       field means "the phase by which this card is real"; it in fact shipped
 *       after Phase 10 closed, and is the first entry here that no phase of the
 *       plan asked for.
 * </ul>
 */
public enum NavEntry {
    OVERVIEW("Overview", 3),
    TIMELINE("Timeline", 6),
    ACTIONS("Actions", 3),
    TARGETS("Targets", 3),
    GRAPH("Graph", 5),
    TESTS("Tests", 3),
    ERRORS("Errors", 3),
    EVENTS("Events", 1),
    BUILD("Build", 2),
    FINDINGS("Findings", 8),
    QUERY("Query", 10);

    private final String title;
    private final int arrivalPhase;

    NavEntry(String title, int arrivalPhase) {
        this.title = title;
        this.arrivalPhase = arrivalPhase;
    }

    /** Human-readable sidebar label. */
    public String title() {
        return title;
    }

    /** Plan phase in which the real view replaces the placeholder card. */
    public int arrivalPhase() {
        return arrivalPhase;
    }

    /** Stable CardLayout identifier for this entry's center card. */
    public String cardName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
