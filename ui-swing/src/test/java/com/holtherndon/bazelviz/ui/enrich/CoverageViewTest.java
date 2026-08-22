package com.holtherndon.bazelviz.ui.enrich;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.core.enrich.AttemptCorrelation;
import com.holtherndon.bazelviz.core.enrich.EnrichmentTask;
import com.holtherndon.bazelviz.core.enrich.ProfileAnchor;
import com.holtherndon.bazelviz.storage.enrich.EnrichmentQueries;
import java.awt.GraphicsEnvironment;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What the coverage panel says.
 *
 * <p>The tests are about sentences rather than layout, because the failure mode
 * here is a true number that reads as a defect: "4 of 13 actions have attempt
 * data" is correct and alarming, and the sentence explaining why is the whole
 * value of the panel.
 */
final class CoverageViewTest {

    @BeforeAll
    static void requireHeadless() {
        assertThat(GraphicsEnvironment.isHeadless()).isTrue();
    }

    @Test
    @DisplayName("partial action coverage is explained, not just counted")
    void partialCoverageIsExplained() throws Exception {
        List<String> text = render(new EnrichmentQueries.Coverage(
                13, 4, 4, 0, 0, Map.of(AttemptCorrelation.MATCHED_BY_OUTPUT, 4L)));

        assertThat(text).contains("13", "4");
        assertThat(joined(text))
                .contains("never start a subprocess")
                .contains("not missing data");
    }

    @Test
    @DisplayName("no execution log says what is unknown rather than showing zeroes")
    void noExecutionLogIsStated() throws Exception {
        List<String> text = render(new EnrichmentQueries.Coverage(13, 0, 0, 0, 0, Map.of()));

        assertThat(joined(text))
                .contains("No execution log has been imported")
                .contains("where actions ran");
    }

    @Test
    @DisplayName("unmatched spawns are surfaced with what is still true about them")
    void unmatchedSpawnsAreSurfaced() throws Exception {
        List<String> text = render(new EnrichmentQueries.Coverage(
                13, 2, 5, 0, 0,
                Map.of(AttemptCorrelation.MATCHED_BY_OUTPUT, 2L,
                        AttemptCorrelation.UNMATCHED, 2L,
                        AttemptCorrelation.AMBIGUOUS, 1L)));

        // Plan 24: ambiguous correlations remain visible.
        assertThat(joined(text)).contains("3 spawns could not be matched");
        // And their own measurements are not called into question by it.
        assertThat(joined(text)).contains("own measurements are still correct");
    }

    @Test
    @DisplayName("a profile whose spans cannot be attributed says which flag was missing")
    void unattributableSpansNameTheFlag() throws Exception {
        List<String> text = render(new EnrichmentQueries.Coverage(13, 4, 4, 900, 0, Map.of()));

        assertThat(joined(text)).contains("--experimental_profile_include_primary_output");
    }

    @Test
    @DisplayName("a floored profile anchor warns that times cannot be lined up")
    void flooredAnchorIsSurfaced() throws Exception {
        CoverageView view = onEdt(CoverageView::new);
        onEdt(() -> {
            view.render(new CoverageView.Snapshot(
                    new EnrichmentQueries.Coverage(13, 4, 4, 900, 900, Map.of()),
                    List.of(),
                    List.of(new EnrichmentQueries.Phase(
                            0, "Launch Blaze", -19000, OptionalLong.of(0), true)),
                    Optional.of(ProfileAnchor.flooredStart(
                            1787434075000000L, "profile_finish_ts")),
                    List.of(),
                    List.of()));
            return null;
        });

        // Without this a reader lines profile spans up against execution-log
        // starts to a precision Bazel 6.5.0 and 7.6.1 do not support (P1).
        assertThat(joined(view.renderedText())).contains("one second");
    }

    @Test
    @DisplayName("a failed enrichment task shows all six things plan 21.4 asks for")
    void failedTasksAreExplained() throws Exception {
        CoverageView view = onEdt(CoverageView::new);
        onEdt(() -> {
            view.render(new CoverageView.Snapshot(
                    new EnrichmentQueries.Coverage(13, 0, 0, 0, 0, Map.of()),
                    List.of(), List.of(), Optional.empty(), List.of(),
                    List.of(new EnrichmentTask(
                            EnrichmentTask.Kind.PROFILE,
                            Optional.of("/tmp/profile.json"),
                            EnrichmentTask.State.FAILED,
                            Optional.of("failed"),
                            Optional.of("Expected BEGIN_OBJECT but was STRING"),
                            true,
                            List.of("Bazel's own critical path"),
                            java.util.OptionalLong.empty(),
                            java.util.OptionalLong.empty()))));
            return null;
        });

        String text = joined(view.renderedText());
        assertThat(text).contains("Trace profile");                 // task
        assertThat(text).contains("/tmp/profile.json");             // source
        assertThat(text).contains("failed");                        // exit status
        assertThat(text).contains("Expected BEGIN_OBJECT");         // error excerpt
        assertThat(text).contains("Can be retried");                // retriability
        assertThat(text).contains("Bazel's own critical path");     // metrics lost
    }

    @Test
    @DisplayName("Bazel's critical path says it is Bazel's and why it is not linked")
    void criticalPathIsAttributed() throws Exception {
        CoverageView view = onEdt(CoverageView::new);
        onEdt(() -> {
            view.render(new CoverageView.Snapshot(
                    new EnrichmentQueries.Coverage(13, 4, 4, 0, 0, Map.of()),
                    List.of(), List.of(), Optional.empty(),
                    List.of(new EnrichmentQueries.CriticalPathComponent(
                            0, "action 'Executing genrule //pkg:gen_a'",
                            OptionalLong.of(36_440))),
                    List.of()));
            return null;
        });

        assertThat(joined(view.renderedText()))
                .contains("Bazel's own critical path")
                .contains("not linked to the actions table");
    }

    // ---------------------------------------------------------------- helpers

    private static List<String> render(EnrichmentQueries.Coverage coverage) throws Exception {
        CoverageView view = onEdt(CoverageView::new);
        onEdt(() -> {
            view.render(new CoverageView.Snapshot(
                    coverage, List.of(), List.of(), Optional.empty(), List.of(), List.of()));
            return null;
        });
        return view.renderedText();
    }

    private static String joined(List<String> text) {
        return String.join(" ‖ ", text);
    }

    private static <T> T onEdt(Callable<T> work) throws Exception {
        AtomicReference<T> value = new AtomicReference<>();
        AtomicReference<Exception> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                value.set(work.call());
            } catch (Exception e) {
                failure.set(e);
            }
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return value.get();
    }
}
