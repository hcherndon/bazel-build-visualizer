package com.holtherndon.bazelviz.ui.nav;

import java.util.Locale;

/**
 * The navigation sidebar entries in display order (plan section 17.1), each
 * mapped to the CardLayout card it selects and the phase whose UI deliverable
 * replaces its placeholder. Arrival phases come from the per-phase "UI
 * deliverable" lists in docs/product-plan.md section 24 — change them there
 * first, then here.
 */
public enum NavEntry {
    OVERVIEW("Overview", 3),
    TIMELINE("Timeline", 6),
    ACTIONS("Actions", 3),
    TARGETS("Targets", 3),
    GRAPH("Graph", 5),
    TESTS("Tests", 3),
    FAILURES("Failures", 3),
    EVENTS("Events", 1),
    CONSOLE("Console", 2),
    CAPTURE("Capture", 2),
    FINDINGS("Findings", 8);

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
