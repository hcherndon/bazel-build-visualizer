package com.holtherndon.bazelviz.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.ui.nav.EntityActions;
import com.holtherndon.bazelviz.ui.nav.EntityRef;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Graph/Tree split's wiring contract, checked without a window.
 *
 * <p>The suite runs headless, so {@code MainWindow} itself — a JFrame —
 * cannot be constructed here. What can be checked is the set of commands its
 * {@code navigate} switch stands behind, which is the half of the contract
 * that is data rather than compilation: an unwired command is never offered
 * anywhere, so this set is exactly what users can reach.
 */
final class MainWindowNavWiringTest {

    @Test
    @DisplayName("the Graph/Tree split wired both of its commands")
    void bothSplitCommandsAreWired() {
        Set<EntityActions.Command> wired = MainWindow.wiredCommands();

        // OPEN_IN_TREE waited, deliberately unwired, until today's Graph card
        // became the Tree card; OPEN_IN_GRAPH now targets the canvas card.
        // Both must be offered, or the split shipped half its point.
        assertThat(wired)
                .contains(
                        EntityActions.Command.OPEN_IN_TREE,
                        EntityActions.Command.OPEN_IN_GRAPH);
        // Still honestly absent: no events-by-label read path exists yet.
        assertThat(wired).doesNotContain(EntityActions.Command.SHOW_EVENTS_FOR_LABEL);
    }

    @Test
    @DisplayName("an action row is offered both destinations, tree and graph")
    void actionRowsAreOfferedBoth() {
        EntityActions actions =
                new EntityActions(MainWindow.wiredCommands(), (command, ref) -> { });

        List<EntityActions.Offer> offers = actions.offersFor(
                List.of(new EntityRef.ActionId(7)), Set.of());

        assertThat(offers)
                .extracting(EntityActions.Offer::command)
                .contains(
                        EntityActions.Command.OPEN_IN_TREE,
                        EntityActions.Command.OPEN_IN_GRAPH);
    }
}
