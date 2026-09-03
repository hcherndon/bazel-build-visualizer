package com.holtherndon.bazelviz.ui.nav;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** Headless test: touches only the enum, never a Swing component. */
class NavEntryTest {

  @Test
  void sidebarEntriesMatchThePlanInDisplayOrder() {
    // Eighteen, where plan 17.1 lists eleven. Console and Capture were
    // merged into one Console card, Failures was renamed Errors, Query is
    // in no phase of the plan at all, and the old Graph card split into
    // Graph (the canvas) and Tree (the dependency trees); NavEntry's own
    // javadoc carries the reasons.
    assertThat(Arrays.stream(NavEntry.values()).map(NavEntry::title))
        .containsExactly(
            "Console",
            "Terminal",
            "Browse Repository",
            "Overview",
            "Timeline",
            "Critical Path",
            "Starlark Profile",
            "Actions",
            "Top Level Targets",
            "All Targets",
            "Configurations",
            "Graph",
            "Tree",
            "Tests",
            "Errors",
            "Events",
            "Findings",
            "Query");
  }

  @Test
  void executionToolsFollowConsole() {
    assertThat(NavEntry.values()[0]).isEqualTo(NavEntry.BUILD);
    assertThat(NavEntry.values()[1]).isEqualTo(NavEntry.TERMINAL);
    assertThat(NavEntry.values()[2]).isEqualTo(NavEntry.REPOSITORY);
    assertThat(NavEntry.TERMINAL.title()).isEqualTo("Terminal");
    assertThat(NavEntry.REPOSITORY.title()).isEqualTo("Browse Repository");
  }

  @Test
  void targetExplorersAreSeparateAndAdjacent() {
    assertThat(NavEntry.TARGETS.title()).isEqualTo("Top Level Targets");
    assertThat(NavEntry.ALL_TARGETS.title()).isEqualTo("All Targets");
    assertThat(NavEntry.ALL_TARGETS.cardName()).isEqualTo("alltargets");
    assertThat(NavEntry.ALL_TARGETS.ordinal()).isEqualTo(NavEntry.TARGETS.ordinal() + 1);
    assertThat(NavEntry.CONFIGURATIONS.ordinal()).isEqualTo(NavEntry.ALL_TARGETS.ordinal() + 1);
  }

  @Test
  void theGraphTreeSplitIsTwoRealEntries() {
    // The canvas and the trees were one card behind an embedded sub-tab,
    // which made "open in graph" ambiguous between two different answers.
    // GRAPH keeps its name and now means the drawing; TREE is the
    // renamed home of the Phase 5 trees.
    assertThat(NavEntry.GRAPH.title()).isEqualTo("Graph");
    assertThat(NavEntry.GRAPH.cardName()).isEqualTo("graph");
    assertThat(NavEntry.TREE.title()).isEqualTo("Tree");
    assertThat(NavEntry.TREE.cardName()).isEqualTo("tree");
  }

  @Test
  void cardNamesAreUniqueAndStable() {
    assertThat(Arrays.stream(NavEntry.values()).map(NavEntry::cardName))
        .doesNotHaveDuplicates()
        .allSatisfy(name -> assertThat(name).matches("[a-z]+"));
  }

  @Test
  void theConsoleEntryCarriesTheLauncherAndBothHalvesOfACapture() {
    // One entry, not two: BUILD keeps the stable card id while its visible
    // title is Console. It comes first because this is where a build starts.
    assertThat(Arrays.stream(NavEntry.values()).map(Enum::name))
        .doesNotContain("CONSOLE", "CAPTURE", "FAILURES")
        .contains("BUILD", "ERRORS");
    assertThat(NavEntry.BUILD.arrivalPhase()).isEqualTo(2);
    assertThat(NavEntry.BUILD.title()).isEqualTo("Console");
    assertThat(NavEntry.BUILD.cardName()).isEqualTo("build");
    assertThat(NavEntry.values()[0]).isEqualTo(NavEntry.BUILD);
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
    assertThat(NavEntry.CRITICAL_PATH.arrivalPhase()).isEqualTo(8);
    assertThat(NavEntry.STARLARK_PROFILE.arrivalPhase()).isEqualTo(10);
    assertThat(NavEntry.STARLARK_PROFILE.ordinal()).isEqualTo(NavEntry.CRITICAL_PATH.ordinal() + 1);
    // One phase each, and they are different phases: plan 24 gives
    // Phase 5 the dependency and reverse-dependency trees, the
    // selected-action neighbourhood, the path-between-nodes and the
    // graph-source selector -- the Tree card. Phase 7 is the rendered
    // canvas with layouts and semantic zoom -- the Graph card. While they
    // shared a card the earlier phase named its arrival; split, each
    // carries its own.
    assertThat(NavEntry.TREE.arrivalPhase()).isEqualTo(5);
    assertThat(NavEntry.GRAPH.arrivalPhase()).isEqualTo(7);
  }

  @Test
  void everyEntryArrivesAfterThePhaseZeroShell() {
    assertThat(Arrays.stream(NavEntry.values()).map(NavEntry::arrivalPhase))
        .allSatisfy(phase -> assertThat(phase).isPositive());
  }
}
