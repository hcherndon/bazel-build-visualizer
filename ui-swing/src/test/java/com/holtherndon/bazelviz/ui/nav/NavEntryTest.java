package com.holtherndon.bazelviz.ui.nav;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** Headless test: touches only the enum, never a Swing component. */
class NavEntryTest {

    @Test
    void sidebarEntriesMatchThePlanInDisplayOrder() {
        assertThat(Arrays.stream(NavEntry.values()).map(NavEntry::title))
                .containsExactly(
                        "Overview",
                        "Timeline",
                        "Actions",
                        "Targets",
                        "Graph",
                        "Tests",
                        "Failures",
                        "Events",
                        "Console",
                        "Capture",
                        "Findings");
    }

    @Test
    void cardNamesAreUniqueAndStable() {
        assertThat(Arrays.stream(NavEntry.values()).map(NavEntry::cardName))
                .doesNotHaveDuplicates()
                .allSatisfy(name -> assertThat(name).matches("[a-z]+"));
    }

    @Test
    void planFixedArrivalPhasesAreCorrect() {
        assertThat(NavEntry.OVERVIEW.arrivalPhase()).isEqualTo(3);
        assertThat(NavEntry.TIMELINE.arrivalPhase()).isEqualTo(6);
        assertThat(NavEntry.GRAPH.arrivalPhase()).isEqualTo(7);
    }

    @Test
    void everyEntryArrivesAfterThePhaseZeroShell() {
        assertThat(Arrays.stream(NavEntry.values()).map(NavEntry::arrivalPhase))
                .allSatisfy(phase -> assertThat(phase).isPositive());
    }
}
