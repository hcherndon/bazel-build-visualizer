package com.holtherndon.bazelviz.ui.nav;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import javax.swing.AbstractButton;
import javax.swing.JComponent;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The shared navigation component's one contract: an item is offered exactly
 * when its command is wired and one of the refs is of the kind it acts on,
 * and every activation reaches the single handler with the right pair.
 *
 * <p>All headless: menus and buttons are constructed and clicked
 * programmatically, never shown.
 */
final class EntityActionsTest {

    /** Records every dispatched (command, ref) pair. */
    private static final class Recorder implements EntityActions.Handler {
        final List<EntityActions.Command> commands = new ArrayList<>();
        final List<EntityRef> refs = new ArrayList<>();

        @Override
        public void navigate(EntityActions.Command command, EntityRef ref) {
            commands.add(command);
            refs.add(ref);
        }
    }

    private static final Set<EntityActions.Command> ALL =
            EnumSet.allOf(EntityActions.Command.class);

    @Test
    @DisplayName("a label ref offers exactly the label commands, in vocabulary order")
    void labelRefOffersLabelCommands() {
        EntityActions actions = new EntityActions(ALL, new Recorder());
        List<EntityActions.Offer> offers = actions.offersFor(
                List.of(new EntityRef.TargetLabel("//a:b")), Set.of());

        assertThat(offers).extracting(EntityActions.Offer::command).containsExactly(
                EntityActions.Command.OPEN_TARGET,
                EntityActions.Command.SHOW_ACTIONS_FOR_LABEL,
                EntityActions.Command.SHOW_EVENTS_FOR_LABEL);
    }

    @Test
    @DisplayName("an action ref offers the action commands and an event ref its one")
    void actionAndEventRefsOfferTheirCommands() {
        EntityActions actions = new EntityActions(ALL, new Recorder());

        assertThat(actions.offersFor(List.of(new EntityRef.ActionId(7)), Set.of()))
                .extracting(EntityActions.Offer::command)
                .containsExactly(
                        EntityActions.Command.OPEN_IN_TREE,
                        EntityActions.Command.OPEN_IN_GRAPH,
                        EntityActions.Command.REVEAL_ACTION,
                        EntityActions.Command.SHOW_ON_TIMELINE);

        assertThat(actions.offersFor(List.of(new EntityRef.EventId(9)), Set.of()))
                .extracting(EntityActions.Offer::command)
                .containsExactly(EntityActions.Command.SHOW_SOURCE_EVENT);
    }

    @Test
    @DisplayName("an unwired command is never offered — honestly absent, not disabled")
    void unwiredCommandsAreAbsent() {
        EntityActions actions = new EntityActions(
                EnumSet.of(EntityActions.Command.OPEN_TARGET), new Recorder());
        List<EntityActions.Offer> offers = actions.offersFor(
                List.of(new EntityRef.TargetLabel("//a:b"), new EntityRef.ActionId(1)),
                Set.of());

        assertThat(offers).extracting(EntityActions.Offer::command)
                .containsExactly(EntityActions.Command.OPEN_TARGET);
    }

    @Test
    @DisplayName("a host can omit the command that would navigate to itself")
    void omittedCommandsAreAbsent() {
        EntityActions actions = new EntityActions(ALL, new Recorder());
        List<EntityActions.Offer> offers = actions.offersFor(
                List.of(new EntityRef.ActionId(4)),
                Set.of(EntityActions.Command.REVEAL_ACTION));

        assertThat(offers).extracting(EntityActions.Offer::command)
                .doesNotContain(EntityActions.Command.REVEAL_ACTION)
                .isNotEmpty();
    }

    @Test
    @DisplayName("no refs, no offers, and the popup is empty rather than a blank shell")
    void nothingIsOfferedForNothing() {
        EntityActions actions = new EntityActions(ALL, new Recorder());

        assertThat(actions.offersFor(List.of(), Set.of())).isEmpty();
        assertThat(actions.popupFor(List.of(), Set.of()).getComponentCount()).isZero();
        assertThat(actions.buttonStripFor(List.of(), Set.of()).getComponentCount()).isZero();
    }

    @Test
    @DisplayName("clicking a menu item dispatches its command and ref to the one handler")
    void menuItemsDispatch() {
        Recorder recorder = new Recorder();
        EntityActions actions = new EntityActions(ALL, recorder);
        EntityRef.TargetLabel label = new EntityRef.TargetLabel("//pkg:name");
        JPopupMenu menu = actions.popupFor(List.of(label), Set.of());

        assertThat(menu.getComponentCount()).isEqualTo(3);
        ((JMenuItem) menu.getComponent(0)).doClick();

        assertThat(recorder.commands).containsExactly(EntityActions.Command.OPEN_TARGET);
        assertThat(recorder.refs).containsExactly(label);
    }

    @Test
    @DisplayName("the button strip carries the same offering and dispatches the same way")
    void buttonStripDispatches() {
        Recorder recorder = new Recorder();
        EntityActions actions = new EntityActions(ALL, recorder);
        EntityRef.ActionId action = new EntityRef.ActionId(41);
        JComponent strip = actions.buttonStripFor(
                List.of(action), Set.of(EntityActions.Command.REVEAL_ACTION));

        List<String> titles = new ArrayList<>();
        for (int i = 0; i < strip.getComponentCount(); i++) {
            titles.add(((AbstractButton) strip.getComponent(i)).getText());
        }
        assertThat(titles).containsExactly(
                EntityActions.Command.OPEN_IN_TREE.title(),
                EntityActions.Command.OPEN_IN_GRAPH.title(),
                EntityActions.Command.SHOW_ON_TIMELINE.title());

        ((AbstractButton) strip.getComponent(1)).doClick();
        assertThat(recorder.commands)
                .containsExactly(EntityActions.Command.OPEN_IN_GRAPH);
        assertThat(recorder.refs).containsExactly(action);
    }

    @Test
    @DisplayName("direct dispatch works for wired commands and fails loudly for unwired ones")
    void directNavigateGuardsWiring() {
        Recorder recorder = new Recorder();
        EntityActions actions = new EntityActions(
                EnumSet.of(EntityActions.Command.SHOW_ON_TIMELINE), recorder);

        actions.navigate(EntityActions.Command.SHOW_ON_TIMELINE, new EntityRef.ActionId(3));
        assertThat(recorder.commands)
                .containsExactly(EntityActions.Command.SHOW_ON_TIMELINE);

        assertThatThrownBy(() -> actions.navigate(
                        EntityActions.Command.OPEN_TARGET, new EntityRef.TargetLabel("//a:b")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a label ref refuses to exist without a label")
    void labelRefRejectsBlank() {
        assertThatThrownBy(() -> new EntityRef.TargetLabel(" "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
