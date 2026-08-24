package com.holtherndon.bazelviz.ui.timeline;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.ui.nav.EntityActions;
import com.holtherndon.bazelviz.ui.nav.EntityRef;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.swing.JButton;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Timeline's inline inspector, and the shared vocabulary it dispatches.
 *
 * <p>Before this existed, clicking a span selected a row in the Actions tab
 * without switching to it — visibly, nothing happened. Now a click shows the
 * segment's details in place, and the details' actions go through the same
 * {@link EntityActions} facility and the same single handler as every other
 * view's, which is what these tests pin down.
 */
final class TimelineInspectorTest {

    private static final int WIDTH = 1_000;
    private static final int HEIGHT = 400;

    @BeforeAll
    static void requireHeadless() {
        assertThat(GraphicsEnvironment.isHeadless()).isTrue();
    }

    /** Records every navigation the facility dispatches. */
    private static final class Recorder implements EntityActions.Handler {
        final List<EntityActions.Command> commands = new ArrayList<>();
        final List<EntityRef> refs = new ArrayList<>();

        @Override
        public void navigate(EntityActions.Command command, EntityRef ref) {
            commands.add(command);
            refs.add(ref);
        }
    }

    private static TimelineModel aModel() {
        TimelineLodIndex index = TimelineLodIndex.build(new SpanSource() {
            @Override
            public long spanCount() {
                return 1;
            }

            @Override
            public void forEachSpan(SpanConsumer consumer) {
                consumer.accept(0, 1_000_000, 0, 0, SpanSource.BYTES_UNKNOWN);
            }
        }, 0, 10_000_000);
        return new TimelineModel(
                index,
                List.of(new TimelineModel.Lane("All actions", "", 1, 1_000_000, 0, 1_000_000)),
                List.of(), Map.of(), 0, 0, 0);
    }

    private static List<JButton> buttonsIn(Container container) {
        List<JButton> found = new ArrayList<>();
        for (Component child : container.getComponents()) {
            if (child instanceof JButton button) {
                found.add(button);
            }
            if (child instanceof Container nested) {
                found.addAll(buttonsIn(nested));
            }
        }
        return found;
    }

    @Test
    @DisplayName("clicking a span shows the inspector and reports the selection")
    void clickShowsTheInspector() {
        TimelineView view = new TimelineView();
        view.canvasForTest().setSize(WIDTH, HEIGHT);
        view.setModel(aModel());
        SpanWindow.Builder window = SpanWindow.builder(0, 10_000_000);
        window.add(0, 5_000_000, 0, 42, "");
        view.setWindow(window.build());

        List<Long> selected = new ArrayList<>();
        List<Long> picked = new ArrayList<>();
        view.onSelection(selected::add);
        view.onActionPicked(picked::add);

        assertThat(view.inspectorVisibleForTest()).isFalse();
        // x = 100 is well inside the span (0..5s of a 10s wall over 1000 px);
        // y = 1 is the top sub-row of lane 0, and there is no live band.
        view.clickAt(100, 1);

        assertThat(view.inspectorVisibleForTest()).isTrue();
        assertThat(view.inspectorTitleForTest()).isEqualTo("Action 42");
        assertThat(selected).containsExactly(42L);
        assertThat(picked).containsExactly(42L);
        assertThat(view.viewport().orElseThrow().selectedNode()).hasValue(42);
    }

    @Test
    @DisplayName("the inspector's actions dispatch through the shared facility")
    void inspectorActionsUseTheFacility() {
        TimelineView view = new TimelineView();
        Recorder recorder = new Recorder();
        view.installEntityActions(new EntityActions(
                Set.of(EntityActions.Command.REVEAL_ACTION,
                        EntityActions.Command.OPEN_TARGET,
                        EntityActions.Command.SHOW_ON_TIMELINE),
                recorder));

        view.showInspector(new SpanDetails(
                "//pkg:thing",
                List.of("Action 7 (Javac) — SUCCESS"),
                List.of(new EntityRef.ActionId(7),
                        new EntityRef.TargetLabel("//pkg:thing"))));

        List<JButton> buttons = buttonsIn(view.inspectorActionsForTest());
        assertThat(buttons)
                .extracting(JButton::getText)
                .containsExactlyInAnyOrder("Open target", "Reveal action")
                // Not "Show on timeline": the segment it would show is the one
                // already under the pointer.
                .doesNotContain("Show on timeline");

        buttons.stream()
                .filter(button -> button.getText().equals("Reveal action"))
                .findFirst().orElseThrow().doClick();

        assertThat(recorder.commands).containsExactly(EntityActions.Command.REVEAL_ACTION);
        assertThat(recorder.refs).containsExactly(new EntityRef.ActionId(7));
    }

    @Test
    @DisplayName("without the facility installed, details show and no dead buttons appear")
    void noFacilityMeansNoButtons() {
        TimelineView view = new TimelineView();
        view.showInspector(new SpanDetails(
                "Action 9", List.of("a line"), List.of(new EntityRef.ActionId(9))));

        assertThat(view.inspectorVisibleForTest()).isTrue();
        assertThat(buttonsIn(view.inspectorActionsForTest())).isEmpty();
    }

    @Test
    @DisplayName("the controller words an action's details from what was reported, only that")
    void detailsAreHonest() {
        com.holtherndon.bazelviz.storage.entities.ActionRow row =
                new com.holtherndon.bazelviz.storage.entities.ActionRow(
                        7,
                        "bazel-out/k8-fastbuild/bin/pkg/thing.jar",
                        java.util.Optional.of("//pkg:thing"),
                        java.util.Optional.of("Javac"),
                        com.holtherndon.bazelviz.core.domain.ActionOutcome.SUCCEEDED,
                        java.util.OptionalLong.empty(),
                        java.util.OptionalLong.empty(),
                        java.util.Optional.of("this Bazel version reports no action times"),
                        java.util.OptionalInt.empty(),
                        java.util.OptionalInt.empty(),
                        java.util.Optional.empty(),
                        java.util.Optional.empty(),
                        java.util.Optional.empty(),
                        java.util.Optional.empty(),
                        java.util.OptionalLong.of(31),
                        com.holtherndon.bazelviz.storage.entities.ActionRow.Execution.none());

        SpanDetails details = TimelineController.detailsOf(row);

        assertThat(details.title()).isEqualTo("//pkg:thing");
        // The unknown duration is a worded reason, never "0.000 s".
        assertThat(String.join("\n", details.lines()))
                .contains("Duration unknown: this Bazel version reports no action times")
                .doesNotContain("Duration: 0");
        assertThat(details.refs()).containsExactly(
                new EntityRef.ActionId(7),
                new EntityRef.TargetLabel("//pkg:thing"),
                new EntityRef.EventId(31));
    }
}
