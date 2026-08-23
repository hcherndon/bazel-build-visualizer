package com.holtherndon.bazelviz.ui.graph;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.core.graph.ConfigurationMatch;
import com.holtherndon.bazelviz.storage.graph.GraphQueries;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What the selector says about a graph before anyone reads it.
 *
 * <p>A dependency tree built from a query that analysed a different
 * configuration looks exactly like a correct one. These sentences are the only
 * thing between the user and a confident wrong answer, which is why they are
 * tested rather than merely written.
 */
final class GraphSourceSummaryTest {

    private static GraphQueries.GraphSource source(
            String state, ConfigurationMatch match, String detail) {
        return new GraphQueries.GraphSource(
                "DECLARED_ACTIONS", Optional.of("bazel aquery //..."), state, match,
                Optional.ofNullable(detail), OptionalLong.of(16), OptionalLong.of(4),
                state.equals("FAILED") ? Optional.of("no such target") : Optional.empty());
    }

    @Test
    @DisplayName("an exact match is labelled plainly, with no warning")
    void exactNeedsNoWarning() {
        GraphQueries.GraphSource exact = source("SUCCEEDED", ConfigurationMatch.EXACT, null);

        assertThat(GraphSourceSummary.label(exact)).isEqualTo("Declared action graph (aquery)");
        assertThat(GraphSourceSummary.warning(exact)).isEmpty();
        assertThat(exact.isTrustworthy()).isTrue();
    }

    @Test
    @DisplayName("a mismatched graph is labelled, warned about, and still shown")
    void mismatchIsLoudAndNotHidden() {
        GraphQueries.GraphSource mismatched = source(
                "SUCCEEDED", ConfigurationMatch.MISMATCHED,
                "This graph includes 1 configuration the build never used, so it is not this"
                        + " build's action graph. The actions and edges in it are real.");

        assertThat(GraphSourceSummary.label(mismatched)).contains("different configuration");
        // The warning survives being scrolled past; the description does not.
        assertThat(GraphSourceSummary.warning(mismatched))
                .hasValueSatisfying(text -> assertThat(text).contains("not confirmed"));
        // And the detail keeps saying the data is real, so a reader does not
        // conclude the whole graph is garbage.
        assertThat(GraphSourceSummary.describe(mismatched)).contains("are real");
    }

    @Test
    @DisplayName("an unverified graph is not presented as matching")
    void unknownIsWarnedAbout() {
        GraphQueries.GraphSource unknown = source("SUCCEEDED", ConfigurationMatch.UNKNOWN, null);

        assertThat(GraphSourceSummary.label(unknown)).contains("unverified");
        assertThat(GraphSourceSummary.warning(unknown)).isPresent();
        assertThat(unknown.isTrustworthy()).isFalse();
    }

    @Test
    @DisplayName("a failed query says the rest of the session is unaffected")
    void failureIsContainedInWords() {
        GraphQueries.GraphSource failed = source("FAILED", ConfigurationMatch.UNKNOWN, null);

        assertThat(GraphSourceSummary.label(failed)).contains("unavailable");
        assertThat(GraphSourceSummary.describe(failed))
                .contains("no such target")
                .contains("Everything else in this session is unaffected");
    }

    @Test
    @DisplayName("the description says how much of the build the graph covers")
    void coverageIsStated() {
        String text = GraphSourceSummary.describe(
                source("SUCCEEDED", ConfigurationMatch.EXACT, null));

        // 16 declared, 4 executed. Neither number is the build's action count,
        // and saying both is what stops either being read as one.
        assertThat(text).contains("16 actions were declared").contains("4 also ran");
    }

    @Test
    @DisplayName("a trustworthy graph is preferred when there is one")
    void preferredPicksTheTrustworthyOne() {
        GraphQueries.GraphSource bad = source("SUCCEEDED", ConfigurationMatch.MISMATCHED, "x");
        GraphQueries.GraphSource good = source("SUCCEEDED", ConfigurationMatch.EXACT, null);

        assertThat(GraphSourceSummary.preferred(List.of(bad, good))).hasValue(good);
    }

    @Test
    @DisplayName("a mismatched graph is still offered when it is the only one")
    void preferredFallsBackRatherThanShowingNothing() {
        GraphQueries.GraphSource bad = source("SUCCEEDED", ConfigurationMatch.MISMATCHED, "x");

        // Choosing which one a user who makes no choice reads is not the same
        // as hiding the others.
        assertThat(GraphSourceSummary.preferred(List.of(bad))).hasValue(bad);
    }

    @Test
    @DisplayName("a failed query is never the preferred graph")
    void failedIsNeverPreferred() {
        assertThat(GraphSourceSummary.preferred(
                List.of(source("FAILED", ConfigurationMatch.UNKNOWN, null)))).isEmpty();
        assertThat(GraphSourceSummary.preferred(List.of())).isEmpty();
    }

    @Test
    @DisplayName("every match state produces a label and none renders as an enum name")
    void everyStateIsWorded() {
        for (ConfigurationMatch match : ConfigurationMatch.values()) {
            String label = GraphSourceSummary.label(source("SUCCEEDED", match, null));
            assertThat(label).as("%s", match).isNotBlank().doesNotContain("_");
        }
    }
}
