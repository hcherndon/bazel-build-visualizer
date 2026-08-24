package com.holtherndon.bazelviz.ui.nav;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** Headless test: touches only the enum, never a Swing component. */
class NavEntryTest {

    @Test
    void sidebarEntriesMatchThePlanInDisplayOrder() {
        // Eleven, where plan 17.1 lists a different eleven. Console and
        // Capture were merged into one Build card, Failures was renamed
        // Errors, and Query is in no phase of the plan at all; NavEntry's own
        // javadoc carries the reasons.
        assertThat(Arrays.stream(NavEntry.values()).map(NavEntry::title))
                .containsExactly(
                        "Overview",
                        "Timeline",
                        "Actions",
                        "Targets",
                        "Graph",
                        "Tests",
                        "Errors",
                        "Events",
                        "Build",
                        "Findings",
                        "Query");
    }

    @Test
    void cardNamesAreUniqueAndStable() {
        assertThat(Arrays.stream(NavEntry.values()).map(NavEntry::cardName))
                .doesNotHaveDuplicates()
                .allSatisfy(name -> assertThat(name).matches("[a-z]+"));
    }

    @Test
    void theBuildEntryCarriesBothHalvesOfACapture() {
        // One entry, not two: the capture status is the Build card's header and
        // the console is its body, so there is no card to switch to when the
        // build starts printing. Phase 2 is where both halves arrived.
        assertThat(Arrays.stream(NavEntry.values()).map(Enum::name))
                .doesNotContain("CONSOLE", "CAPTURE", "FAILURES")
                .contains("BUILD", "ERRORS");
        assertThat(NavEntry.BUILD.arrivalPhase()).isEqualTo(2);
        assertThat(NavEntry.BUILD.cardName()).isEqualTo("build");
        assertThat(NavEntry.ERRORS.cardName()).isEqualTo("errors");
    }

    @Test
    void theQueryEntryIsLastAndReal() {
        // Perfetto's query page, which nothing in plan 17.1 asked for. Last in
        // display order because it is the card you go to when none of the
        // others answers the question, and its card name has to satisfy the
        // same [a-z]+ rule as every other.
        assertThat(NavEntry.values()[NavEntry.values().length - 1]).isEqualTo(NavEntry.QUERY);
        assertThat(NavEntry.QUERY.cardName()).isEqualTo("query");
        assertThat(NavEntry.QUERY.arrivalPhase()).isEqualTo(10);
    }

    @Test
    void planFixedArrivalPhasesAreCorrect() {
        assertThat(NavEntry.OVERVIEW.arrivalPhase()).isEqualTo(3);
        assertThat(NavEntry.TIMELINE.arrivalPhase()).isEqualTo(6);
        // Phase 5, not 7. Plan 24 gives Phase 5 the dependency and
        // reverse-dependency trees, the selected-action neighbourhood, the
        // path-between-nodes and the graph-source selector -- all of which are
        // this card. Phase 7 is the rendered canvas with layouts and semantic
        // zoom, which replaces the trees' company rather than their arrival.
        assertThat(NavEntry.GRAPH.arrivalPhase()).isEqualTo(5);
    }

    @Test
    void everyEntryArrivesAfterThePhaseZeroShell() {
        assertThat(Arrays.stream(NavEntry.values()).map(NavEntry::arrivalPhase))
                .allSatisfy(phase -> assertThat(phase).isPositive());
    }
}
