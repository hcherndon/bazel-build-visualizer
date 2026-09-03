package com.holtherndon.bazelviz.ui.enrich;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.core.enrich.AttemptCorrelation;
import com.holtherndon.bazelviz.core.enrich.EnrichmentTask;
import com.holtherndon.bazelviz.core.enrich.ProfileAnchor;
import com.holtherndon.bazelviz.storage.enrich.EnrichmentQueries;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import javax.swing.border.TitledBorder;
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

    @Test
    @DisplayName("the empty coverage state fills the pane instead of becoming a top card")
    void emptyStateUsesTheWholePane() throws Exception {
        CoverageView view = onEdt(CoverageView::new);
        onEdt(() -> {
            layoutTree(view, 1_000, 600);
            assertThat(view.emptyStateForTest().isVisible()).isTrue();
            assertThat(view.emptyStateForTest().getSize()).isEqualTo(view.getSize());
            assertThat(view.emptyStateForTest().getComponent(0))
                    .isInstanceOfSatisfying(javax.swing.JLabel.class,
                            message -> assertThat(message.isEnabled()).isFalse());

            view.render(new CoverageView.Snapshot(
                    new EnrichmentQueries.Coverage(0, 0, 0, 0, 0, Map.of()),
                    List.of(), List.of(), Optional.empty(), List.of(), List.of()));
            layoutTree(view, 1_000, 600);
            assertThat(view.emptyStateForTest().isVisible()).isFalse();
            assertThat(view.scrollForTest().isVisible()).isTrue();
            return null;
        });
    }

    @Test
    @DisplayName("coverage gives long sections full width and pairs only short sections")
    void cardsUseReadableResponsiveRows() throws Exception {
        List<String> criticalDescriptions = new java.util.ArrayList<>();
        List<EnrichmentQueries.CriticalPathComponent> criticalComponents =
                new java.util.ArrayList<>();
        for (int index = 0; index < 6; index++) {
            String description = "action 'Compiling a deliberately long generated source for "
                    + "//pkg/subpackage:target_" + index + " from bazel-out/darwin-fastbuild/bin/"
                    + "pkg/subpackage/generated/descriptor_" + index
                    + ".java under the selected Java toolchain [for tool]'";
            criticalDescriptions.add(description);
            criticalComponents.add(new EnrichmentQueries.CriticalPathComponent(
                    index, description, OptionalLong.of(8_000)));
        }
        CoverageView view = onEdt(CoverageView::new);
        onEdt(() -> {
            view.render(new CoverageView.Snapshot(
                    new EnrichmentQueries.Coverage(13, 4, 4, 900, 900, Map.of()),
                    List.of(new EnrichmentQueries.RunnerCount("remote", 4)),
                    List.of(new EnrichmentQueries.Phase(
                            0, "Launch Blaze", 0, OptionalLong.of(10_000), false)),
                    Optional.empty(),
                    criticalComponents,
                    List.of()));

            // Match the lower half of a large Overview split instead of
            // handing the inner grid an artificial 6,000px canvas.
            layoutTree(view, 1_090, 285);
            JPanel cards = view.cardsForTest();
            JPanel data = section(cards, "Data coverage");
            JPanel critical = section(cards, "Bazel's critical path");
            JPanel tasks = section(cards, "Enrichment tasks");
            Component[] paired = view.pairedCardsForTest().getComponents();

            assertThat(view.dashboardForTest().getWidth())
                    .isEqualTo(view.contentForTest().getWidth());
            assertThat(view.contentForTest().getWidth())
                    .isEqualTo(view.scrollForTest().getViewport().getExtentSize().width);
            assertThat(view.dashboardForTest().getX()).isZero();
            assertThat(paired).hasSize(2);
            assertThat(paired[0].getX()).isLessThan(paired[1].getX());
            assertThat(data.getWidth()).isEqualTo(critical.getWidth());
            assertThat(tasks.getWidth()).isEqualTo(critical.getWidth());
            assertThat(critical.getWidth())
                    .isGreaterThan(paired[0].getWidth() + paired[1].getWidth() - 24);

            for (String text : criticalDescriptions) {
                javax.swing.JTextArea description = findTextArea(cards, text);
                assertThat(description).isNotNull();
                assertThat(description.getWidth())
                        .isGreaterThan((int) (critical.getWidth() * 0.65));
                assertThat(description.getHeight())
                        .isGreaterThanOrEqualTo(
                                2 * description.getFontMetrics(description.getFont()).getHeight());
                assertThat(description.getY() + description.getHeight())
                        .isLessThanOrEqualTo(critical.getHeight());
            }
            javax.swing.JTextArea duration = findTextArea(cards, "8.0 ms");
            assertThat(duration).isNotNull();
            assertThat(duration.getWidth()).isLessThan((int) (critical.getWidth() * 0.34));
            assertThat(critical.getHeight()).isGreaterThanOrEqualTo(critical.getPreferredSize().height);
            assertThat(view.contentForTest().getHeight())
                    .isGreaterThan(view.scrollForTest().getViewport().getExtentSize().height);
            assertThat(view.scrollForTest().getVerticalScrollBar().isVisible()).isTrue();

            layoutTree(view, 770, 210);
            assertThat(paired[1].getX()).isEqualTo(paired[0].getX());
            assertThat(paired[1].getY()).isGreaterThan(paired[0].getY());
            assertThat(critical.getX() + critical.getWidth()).isLessThanOrEqualTo(cards.getWidth());
            return null;
        });
    }

    @Test
    @DisplayName("coverage follows a real viewport when the window expands")
    void dashboardUsesNewViewportWidthAfterResize() throws Exception {
        CoverageView view = onEdt(CoverageView::new);
        onEdt(() -> {
            view.render(new CoverageView.Snapshot(
                    new EnrichmentQueries.Coverage(13, 4, 4, 0, 0, Map.of()),
                    List.of(), List.of(), Optional.empty(), List.of(), List.of()));

            layoutTree(view, 1_090, 370);
            int initial = view.dashboardForTest().getWidth();
            int initialExtent = view.scrollForTest().getViewport().getExtentSize().width;
            assertThat(view.contentForTest().getWidth()).isEqualTo(initialExtent);
            assertThat(view.cardsForTest().getWidth()).isEqualTo(initial);
            layoutTree(view, 1_320, 370);

            int expandedExtent = view.scrollForTest().getViewport().getExtentSize().width;
            assertThat(expandedExtent).isGreaterThan(initialExtent + 200);
            assertThat(view.dashboardForTest().getWidth()).isGreaterThan(initial + 200);
            assertThat(view.dashboardForTest().getWidth())
                    .isEqualTo(view.contentForTest().getWidth());
            assertThat(view.contentForTest().getWidth()).isEqualTo(expandedExtent);
            assertThat(view.cardsForTest().getWidth()).isEqualTo(view.dashboardForTest().getWidth());
            assertThat(view.dashboardForTest().getX()).isZero();
            return null;
        });
    }

    @Test
    @DisplayName("long build-provided row values wrap inside a narrow coverage card")
    void longValuesWrapWithoutHorizontalOverflow() throws Exception {
        String error = "Expected a profile object but received a deliberately long malformed"
                + " value whose complete diagnostic must remain readable in a narrow window";
        CoverageView view = onEdt(CoverageView::new);
        onEdt(() -> {
            view.render(new CoverageView.Snapshot(
                    new EnrichmentQueries.Coverage(13, 0, 0, 0, 0, Map.of()),
                    List.of(), List.of(), Optional.empty(), List.of(),
                    List.of(new EnrichmentTask(
                            EnrichmentTask.Kind.PROFILE,
                            Optional.of("/tmp/a/very/long/path/to/the/imported/profile.json"),
                            EnrichmentTask.State.FAILED,
                            Optional.of("failed"),
                            Optional.of(error),
                            true,
                            List.of("Bazel's own critical path"),
                            OptionalLong.empty(),
                            OptionalLong.empty()))));
            layoutTree(view, 360, 600);

            javax.swing.JTextArea errorArea = findTextArea(view.cardsForTest(), error);
            assertThat(errorArea).isNotNull();
            assertThat(errorArea.getWidth()).isPositive();
            assertThat(errorArea.getHeight())
                    .isGreaterThan(errorArea.getFontMetrics(errorArea.getFont()).getHeight());

            assertThat(view.contentForTest().getScrollableTracksViewportWidth()).isTrue();
            assertThat(view.scrollForTest().getHorizontalScrollBarPolicy())
                    .isEqualTo(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
            return null;
        });
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

    private static void layoutTree(Container root, int width, int height) {
        root.setSize(width, height);
        for (int pass = 0; pass < 4; pass++) {
            invalidateTree(root);
            layoutChildren(root);
        }
    }

    private static void invalidateTree(Container root) {
        root.invalidate();
        for (Component child : root.getComponents()) {
            if (child instanceof Container nested) {
                invalidateTree(nested);
            }
        }
    }

    private static void layoutChildren(Container root) {
        root.doLayout();
        for (Component child : root.getComponents()) {
            if (child instanceof Container nested) {
                layoutChildren(nested);
            }
        }
    }

    private static javax.swing.JTextArea findTextArea(Container root, String text) {
        for (Component child : root.getComponents()) {
            if (child instanceof javax.swing.JTextArea area && text.equals(area.getText())) {
                return area;
            }
            if (child instanceof Container nested) {
                javax.swing.JTextArea found = findTextArea(nested, text);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static JPanel section(Container root, String title) {
        for (Component child : root.getComponents()) {
            if (child instanceof JPanel panel
                    && panel.getBorder() instanceof TitledBorder border
                    && title.equals(border.getTitle())) {
                return panel;
            }
            if (child instanceof Container nested) {
                JPanel found = sectionOrNull(nested, title);
                if (found != null) {
                    return found;
                }
            }
        }
        throw new AssertionError("section not found: " + title);
    }

    private static JPanel sectionOrNull(Container root, String title) {
        for (Component child : root.getComponents()) {
            if (child instanceof JPanel panel
                    && panel.getBorder() instanceof TitledBorder border
                    && title.equals(border.getTitle())) {
                return panel;
            }
            if (child instanceof Container nested) {
                JPanel found = sectionOrNull(nested, title);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
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
