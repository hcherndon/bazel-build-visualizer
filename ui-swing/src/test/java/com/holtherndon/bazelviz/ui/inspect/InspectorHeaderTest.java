package com.holtherndon.bazelviz.ui.inspect;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.ui.nav.EntityActions;
import com.holtherndon.bazelviz.ui.nav.EntityRef;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The one header all three inspectors wear.
 *
 * <p>The thing being pinned down is a layout promise: at any width the pane
 * can be dragged to, the actions stay reachable and the text gives way. Before
 * this widget the opposite happened — the button strip kept its full width and
 * walked off the panel, so the actions were "entirely hidden unless the pane is
 * pulled all the way to the side". A layout promise needs measuring rather than
 * describing, so these tests lay the header out at a narrow width and read the
 * resulting bounds.
 *
 * <p>Headless: nothing here realizes a peer, so the layout is driven by
 * {@code doLayout} directly — {@code validate} is a no-op without one.
 */
class InspectorHeaderTest {

    /** Long enough that no sane pane width fits it. */
    private static final String LONG_TITLE =
            "//very/deeply/nested/package/path/with/many/segments:a-target-with-a-long-name";

    /** Narrower than the title, wide enough for the edge. */
    private static final int NARROW = 160;

    private static final int TALL_ENOUGH = 48;

    @BeforeAll
    static void requireHeadless() {
        assertThat(GraphicsEnvironment.isHeadless())
                .as("these tests must not depend on a display")
                .isTrue();
    }

    /** Records dispatched pairs. */
    private static final class Recorder implements EntityActions.Handler {
        final List<EntityActions.Command> commands = new ArrayList<>();
        final List<EntityRef> refs = new ArrayList<>();

        @Override
        public void navigate(EntityActions.Command command, EntityRef ref) {
            commands.add(command);
            refs.add(ref);
        }
    }

    @Test
    @DisplayName("at a narrow width the text gives way and the overflow button does not")
    void narrowWidthShrinksTheTextNotTheButton() throws Exception {
        InspectorHeader header = onEdt(InspectorHeader::new);
        onEdt(() -> {
            header.installEntityActions(
                    new EntityActions(EnumSet.of(EntityActions.Command.OPEN_TARGET),
                            new Recorder()),
                    Set.of());
            header.show(LONG_TITLE, Optional.of("Javac · SUCCEEDED"),
                    List.of(new EntityRef.TargetLabel(LONG_TITLE)));
            layOut(header, NARROW, TALL_ENOUGH);
            return null;
        });

        int buttonWidth = onEdt(() -> header.overflowForTest().getWidth());
        int buttonWanted = onEdt(() -> header.overflowForTest().getPreferredSize().width);
        assertThat(buttonWidth)
                .as("the button keeps its full width whatever the pane does")
                .isEqualTo(buttonWanted);

        Point buttonAt = onEdt(() -> SwingUtilities.convertPoint(
                header.overflowForTest(), 0, 0, header));
        assertThat(buttonAt.x)
                .as("and stays inside the header rather than walking off its left edge")
                .isGreaterThanOrEqualTo(0);
        assertThat(buttonAt.x + buttonWidth)
                .as("and off its right edge")
                .isLessThanOrEqualTo(NARROW);

        int titleWidth = onEdt(() -> header.titleLabelForTest().getWidth());
        int titleWanted = onEdt(() -> header.titleLabelForTest().getPreferredSize().width);
        assertThat(titleWidth)
                .as("the title is the thing that shrank")
                .isLessThan(titleWanted)
                .isGreaterThan(0);
    }

    @Test
    @DisplayName("an elided line keeps the whole string in its tooltip")
    void elidedTextIsStillReadable() throws Exception {
        InspectorHeader header = onEdt(InspectorHeader::new);
        onEdt(() -> {
            header.show(LONG_TITLE, Optional.of("Javac · SUCCEEDED"), List.of());
            return null;
        });

        assertThat(onEdt(header::titleForTest)).isEqualTo(LONG_TITLE);
        assertThat(onEdt(header::titleTooltipForTest)).isEqualTo(LONG_TITLE);
        assertThat(onEdt(header::subtitleTooltipForTest)).isEqualTo("Javac · SUCCEEDED");
    }

    @Test
    @DisplayName("its minimum width is the edge, not the title, so a pane can actually narrow")
    void theMinimumWidthDoesNotFollowTheTitle() throws Exception {
        InspectorHeader header = onEdt(InspectorHeader::new);
        int blank = onEdt(() -> header.getMinimumSize().width);
        onEdt(() -> {
            header.installEntityActions(
                    new EntityActions(EnumSet.of(EntityActions.Command.OPEN_TARGET),
                            new Recorder()),
                    Set.of());
            header.show(LONG_TITLE, Optional.of(LONG_TITLE),
                    List.of(new EntityRef.TargetLabel(LONG_TITLE)));
            return null;
        });

        int loaded = onEdt(() -> header.getMinimumSize().width);
        int titleWanted = onEdt(() -> header.titleLabelForTest().getPreferredSize().width);
        assertThat(titleWanted)
                .as("the title really is wider than any pane would be")
                .isGreaterThan(300);
        assertThat(loaded)
                .as("but the header's floor is the edge plus room for an ellipsis")
                .isLessThan(200)
                .isGreaterThanOrEqualTo(blank);
    }

    @Test
    @DisplayName("no offers, no button — never an empty menu behind an ellipsis")
    void theButtonAppearsOnlyWithOffers() throws Exception {
        EntityActions actions = new EntityActions(
                EnumSet.of(EntityActions.Command.OPEN_TARGET), new Recorder());
        InspectorHeader header = onEdt(InspectorHeader::new);

        // No facility installed at all: a host that has not adopted it.
        onEdt(() -> {
            header.show("//pkg:a", Optional.empty(),
                    List.of(new EntityRef.TargetLabel("//pkg:a")));
            return null;
        });
        assertThat(onEdt(() -> header.overflowForTest().isVisible()))
                .as("no facility means no button")
                .isFalse();

        // Installed, but the entity carries no refs to act on.
        onEdt(() -> {
            header.installEntityActions(actions, Set.of());
            header.show("Select an event to inspect its raw bytes.",
                    Optional.empty(), List.of());
            return null;
        });
        assertThat(onEdt(() -> header.overflowForTest().isVisible()))
                .as("no refs means no button")
                .isFalse();

        // Installed, refs present, but nothing wired applies to their kind.
        onEdt(() -> {
            header.show("event 12", Optional.empty(),
                    List.of(new EntityRef.EventId(12)));
            return null;
        });
        assertThat(onEdt(() -> header.overflowForTest().isVisible()))
                .as("a ref no wired command acts on means no button")
                .isFalse();
        assertThat(onEdt(() -> header.overflowMenuForTest().getComponentCount()))
                .isZero();

        // Something real behind it at last.
        onEdt(() -> {
            header.show("//pkg:a", Optional.empty(),
                    List.of(new EntityRef.TargetLabel("//pkg:a")));
            return null;
        });
        assertThat(onEdt(() -> header.overflowForTest().isVisible())).isTrue();

        // And clearing takes it away again.
        onEdt(() -> {
            header.clear();
            return null;
        });
        assertThat(onEdt(() -> header.overflowForTest().isVisible())).isFalse();
        assertThat(onEdt(header::titleForTest)).isEqualTo(" ");
        assertThat(onEdt(header::titleTooltipForTest)).isNull();
    }

    @Test
    @DisplayName("the menu is exactly what popupFor offers, omissions included, and it dispatches")
    void theMenuIsThePopupForOffering() throws Exception {
        Recorder recorder = new Recorder();
        EntityActions actions = new EntityActions(
                EnumSet.of(EntityActions.Command.OPEN_TARGET,
                        EntityActions.Command.REVEAL_ACTION,
                        EntityActions.Command.SHOW_ON_TIMELINE,
                        EntityActions.Command.SHOW_SOURCE_EVENT),
                recorder);
        List<EntityRef> refs = List.of(
                new EntityRef.ActionId(7),
                new EntityRef.TargetLabel("//pkg:thing"),
                new EntityRef.EventId(31));
        Set<EntityActions.Command> omit = Set.of(EntityActions.Command.SHOW_ON_TIMELINE);

        InspectorHeader header = onEdt(InspectorHeader::new);
        onEdt(() -> {
            header.installEntityActions(actions, omit);
            header.show("//pkg:thing", Optional.of("Javac · SUCCEEDED"), refs);
            return null;
        });

        assertThat(onEdt(() -> titlesOf(header.overflowMenuForTest())))
                .as("the same list popupFor would build, in the same order")
                .isEqualTo(onEdt(() -> titlesOf(actions.popupFor(refs, omit))))
                .containsExactly("Open target", "Reveal action", "Show source event")
                .doesNotContain("Show on timeline");
        assertThat(onEdt(() -> header.offers(EntityActions.Command.SHOW_SOURCE_EVENT))).isTrue();
        assertThat(onEdt(() -> header.offers(EntityActions.Command.SHOW_ON_TIMELINE))).isFalse();

        onEdt(() -> {
            JPopupMenu menu = header.overflowMenuForTest();
            ((JMenuItem) menu.getComponent(1)).doClick();
            return null;
        });
        assertThat(recorder.commands).containsExactly(EntityActions.Command.REVEAL_ACTION);
        assertThat(recorder.refs).containsExactly(new EntityRef.ActionId(7));
    }

    @Test
    @DisplayName("a host's own control shares the fixed edge and keeps its width too")
    void trailingControlsRideTheEdge() throws Exception {
        InspectorHeader header = onEdt(InspectorHeader::new);
        javax.swing.JButton close = onEdt(() -> new javax.swing.JButton("Close"));
        onEdt(() -> {
            header.addTrailing(close);
            header.show(LONG_TITLE, Optional.empty(), List.of());
            layOut(header, NARROW, TALL_ENOUGH);
            return null;
        });

        int width = onEdt(close::getWidth);
        assertThat(width).isEqualTo(onEdt(() -> close.getPreferredSize().width));
        Point at = onEdt(() -> SwingUtilities.convertPoint(close, 0, 0, header));
        assertThat(at.x).isGreaterThanOrEqualTo(0);
        assertThat(at.x + width).isLessThanOrEqualTo(NARROW);
    }

    private static List<String> titlesOf(JPopupMenu menu) {
        List<String> titles = new ArrayList<>();
        for (int i = 0; i < menu.getComponentCount(); i++) {
            titles.add(((JMenuItem) menu.getComponent(i)).getText());
        }
        return titles;
    }

    /**
     * Lays a container out at a size, top down. {@code validate()} is a no-op
     * on a component with no peer, which every component in a headless test
     * is, so the layout managers are run by hand instead.
     */
    private static void layOut(Container container, int width, int height) {
        container.setSize(width, height);
        layoutDeep(container);
    }

    private static void layoutDeep(Container container) {
        container.doLayout();
        for (Component child : container.getComponents()) {
            if (child instanceof Container nested) {
                layoutDeep(nested);
            }
        }
    }

    private static <T> T onEdt(Callable<T> work) throws Exception {
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
