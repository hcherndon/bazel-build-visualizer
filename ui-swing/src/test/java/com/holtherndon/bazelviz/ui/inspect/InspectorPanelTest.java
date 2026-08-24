package com.holtherndon.bazelviz.ui.inspect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicLong;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What the shared inspector puts on screen.
 *
 * <p>Headless: none of these components realizes a peer. The assertion at the
 * top makes that a stated precondition rather than a coincidence.
 */
class InspectorPanelTest {

    @BeforeAll
    static void requireHeadless() {
        assertThat(GraphicsEnvironment.isHeadless())
                .as("these tests must not depend on a display")
                .isTrue();
    }

    @Test
    @DisplayName("an unknown value is rendered as a word, with its reason")
    void unknownValuesAreVisiblyUnknown() throws Exception {
        Inspection inspection = new Inspection.Builder("//pkg:target")
                .section("Timing")
                .field(Inspection.Field.of("Start", "12:00:00"))
                .field(Inspection.Field.unknown(
                        "Duration", "this Bazel version does not report action timestamps"))
                .build();

        List<String> labels = labelsOf(inspection);

        // The exit criterion in one assertion: an absence reads as an absence
        // and says why, rather than as a blank a user would take for a value.
        assertThat(labels).contains("12:00:00");
        assertThat(labels).anyMatch(text -> text.startsWith(InspectorPanel.UNKNOWN)
                && text.contains("does not report action timestamps"));
    }

    @Test
    @DisplayName("an empty value is not the same as an unknown one")
    void emptyIsNotUnknown() throws Exception {
        Inspection inspection = new Inspection.Builder("//pkg:target")
                .section("Result")
                .field(Inspection.Field.of("Message", ""))
                .build();

        assertThat(labelsOf(inspection)).contains("");
        assertThat(labelsOf(inspection)).noneMatch(text -> text.startsWith(InspectorPanel.UNKNOWN));
    }

    @Test
    @DisplayName("the source-event button appears only when there is one, and reports it")
    void sourceEventButtonReportsTheEvent() throws Exception {
        AtomicLong requested = new AtomicLong(-1);
        InspectorPanel panel = onEdt(InspectorPanel::new);
        onEdt(() -> {
            panel.onShowSourceEvent(requested::set);
            panel.show(new Inspection.Builder("//pkg:target")
                    .sourceEvent(OptionalLong.of(4_812L))
                    .section("Target")
                    .field("Label", "//pkg:target")
                    .build());
            return null;
        });

        assertThat(panel.displayed().sourceEventId()).hasValue(4_812L);

        onEdt(() -> {
            buttonsOf(panel).forEach(button -> button.doClick());
            return null;
        });
        assertThat(requested.get()).isEqualTo(4_812L);
    }

    @Test
    @DisplayName("with the shared actions installed, the strip takes over and nothing appears twice")
    void sharedActionsReplaceTheLegacyButton() throws Exception {
        List<com.holtherndon.bazelviz.ui.nav.EntityActions.Command> dispatched =
                new ArrayList<>();
        List<com.holtherndon.bazelviz.ui.nav.EntityRef> receivedRefs = new ArrayList<>();
        com.holtherndon.bazelviz.ui.nav.EntityActions actions =
                new com.holtherndon.bazelviz.ui.nav.EntityActions(
                        java.util.EnumSet.of(
                                com.holtherndon.bazelviz.ui.nav.EntityActions.Command
                                        .SHOW_SOURCE_EVENT,
                                com.holtherndon.bazelviz.ui.nav.EntityActions.Command
                                        .OPEN_TARGET),
                        (command, ref) -> {
                            dispatched.add(command);
                            receivedRefs.add(ref);
                        });

        InspectorPanel panel = onEdt(InspectorPanel::new);
        onEdt(() -> {
            panel.installEntityActions(actions, java.util.Set.of());
            panel.show(new Inspection.Builder("//pkg:target")
                    .sourceEvent(OptionalLong.of(4_812L))
                    .ref(new com.holtherndon.bazelviz.ui.nav.EntityRef.EventId(4_812L))
                    .ref(new com.holtherndon.bazelviz.ui.nav.EntityRef.TargetLabel("//pkg:target"))
                    .section("Target")
                    .field("Label", "//pkg:target")
                    .build());
            return null;
        });

        // Exactly one "Show source event" on screen: the strip's. The legacy
        // button yields rather than doubling it. (Scrollbar arrow buttons
        // have no text and are not part of the offering.)
        assertThat(onEdt(() -> titledButtonsOf(panel)))
                .containsExactlyInAnyOrder("Open target", "Show source event");

        onEdt(() -> {
            buttonsOf(panel).forEach(button -> button.doClick());
            return null;
        });
        assertThat(dispatched).containsExactlyInAnyOrder(
                com.holtherndon.bazelviz.ui.nav.EntityActions.Command.OPEN_TARGET,
                com.holtherndon.bazelviz.ui.nav.EntityActions.Command.SHOW_SOURCE_EVENT);
        assertThat(receivedRefs).contains(
                new com.holtherndon.bazelviz.ui.nav.EntityRef.EventId(4_812L),
                new com.holtherndon.bazelviz.ui.nav.EntityRef.TargetLabel("//pkg:target"));

        // An inspection with no refs — a view that has not adopted the
        // facility — falls back to the legacy button, exactly as before.
        onEdt(() -> {
            panel.show(new Inspection.Builder("//pkg:other")
                    .sourceEvent(OptionalLong.of(7L))
                    .section("Target")
                    .field("Label", "//pkg:other")
                    .build());
            return null;
        });
        assertThat(onEdt(() -> titledButtonsOf(panel)))
                .containsExactly("Show source event");
    }

    /** The visible, titled buttons — the offering, without scrollbar arrows. */
    private static List<String> titledButtonsOf(Container container) {
        List<String> texts = new ArrayList<>();
        buttonsOf(container).forEach(button -> {
            if (button.getText() != null && !button.getText().isEmpty()) {
                texts.add(button.getText());
            }
        });
        return texts;
    }

    @Test
    @DisplayName("a field cannot claim both a value and a reason for having none")
    void aFieldIsOneThingOrTheOther() {
        assertThatThrownBy(() -> new Inspection.Field(
                        "Duration", Optional.of("1.5 ms"), Optional.of("not reported")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duration");
    }

    private static List<String> labelsOf(Inspection inspection) throws Exception {
        InspectorPanel panel = onEdt(InspectorPanel::new);
        onEdt(() -> {
            panel.show(inspection);
            return null;
        });
        List<String> texts = new ArrayList<>();
        collectLabels(panel, texts);
        return texts;
    }

    private static void collectLabels(Container container, List<String> into) {
        for (Component child : container.getComponents()) {
            if (child instanceof JLabel label) {
                into.add(label.getText());
            }
            if (child instanceof Container nested) {
                collectLabels(nested, into);
            }
        }
    }

    private static List<javax.swing.JButton> buttonsOf(Container container) {
        List<javax.swing.JButton> buttons = new ArrayList<>();
        collectButtons(container, buttons);
        return buttons;
    }

    private static void collectButtons(Container container, List<javax.swing.JButton> into) {
        for (Component child : container.getComponents()) {
            if (child instanceof javax.swing.JButton button && button.isVisible()) {
                into.add(button);
            }
            if (child instanceof Container nested) {
                collectButtons(nested, into);
            }
        }
    }

    private static <T> T onEdt(java.util.concurrent.Callable<T> work) throws Exception {
        List<T> result = new ArrayList<>(1);
        List<Exception> failure = new ArrayList<>(1);
        SwingUtilities.invokeAndWait(() -> {
            try {
                result.add(work.call());
            } catch (Exception e) {
                failure.add(e);
            }
        });
        if (!failure.isEmpty()) {
            throw failure.getFirst();
        }
        return result.isEmpty() ? null : result.getFirst();
    }
}
