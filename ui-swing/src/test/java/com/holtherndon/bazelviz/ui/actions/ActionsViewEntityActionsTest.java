package com.holtherndon.bazelviz.ui.actions;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.capture.file.importer.BepImporter;
import com.holtherndon.bazelviz.capture.file.importer.ImportResult;
import com.holtherndon.bazelviz.format.session.SessionManager;
import com.holtherndon.bazelviz.storage.entities.ActionFilter;
import com.holtherndon.bazelviz.storage.entities.ActionRow;
import com.holtherndon.bazelviz.storage.entities.ActionSort;
import com.holtherndon.bazelviz.testsupport.bep.BepBinaryWriter;
import com.holtherndon.bazelviz.testsupport.bep.SyntheticBepStream;
import com.holtherndon.bazelviz.ui.nav.EntityActions;
import com.holtherndon.bazelviz.ui.nav.EntityRef;
import com.holtherndon.bazelviz.ui.session.EntityReader;
import com.holtherndon.bazelviz.ui.session.SqliteSessionSource;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import javax.swing.JComponent;
import javax.swing.RepaintManager;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Actions tab's adoption of the shared navigation actions: its rows and
 * inspection carry the three identities the facility acts on, and the
 * "show actions for this target" command lands here as a visible, clearable
 * label filter rather than an invisible narrowing.
 */
class ActionsViewEntityActionsTest {

    private static final int EVENT_COUNT = 300;

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

    /**
     * Records which components ask Swing to re-lay them out, by identity.
     *
     * <p>This is how the label-chip fix is actually verified: a real running
     * window relays out asynchronously once {@code revalidate()} is called,
     * but that pump requires a genuine {@code Window} ancestor, and headless
     * forbids creating one at all ({@code new JFrame()} throws {@code
     * HeadlessException} even unshown). {@code JComponent.setVisible(true)}
     * already revalidates the component <em>itself</em> as a side effect --
     * that is not the bug fixed here, and is not enough on its own: it is
     * the chip's <em>toolbar</em>, laid out while the chip was invisible,
     * that has to be told to redo its {@code FlowLayout} for the chip to get
     * real bounds. Recording identities (rather than actually laying
     * anything out) is observable without a display and distinguishes
     * exactly this: whether the toolbar itself was asked to revalidate.
     */
    private static final class RecordingRepaintManager extends RepaintManager {
        final Set<java.awt.Component> requested =
                Collections.newSetFromMap(new IdentityHashMap<>());

        @Override
        public void addInvalidComponent(JComponent component) {
            requested.add(component);
            super.addInvalidComponent(component);
        }
    }

    @Test
    @Timeout(180)
    @DisplayName("an action's inspection names its action, its label and its source event")
    void inspectionCarriesTheRefs(@TempDir Path temporary) throws Exception {
        SqliteSessionSource opened = openImportedSession(temporary);
        try (EntityReader reader = opened.openEntityReader()) {
            List<ActionRow> rows = reader.firstActionPage(
                    ActionFilter.NONE, ActionSort.ARRIVAL, false, 50);
            assertThat(rows).isNotEmpty();
            ActionRow labelled = rows.stream()
                    .filter(row -> row.label().isPresent())
                    .findFirst()
                    .orElseThrow();

            List<EntityRef> refs = ActionInspection.of(labelled).refs();
            assertThat(refs).contains(new EntityRef.ActionId(labelled.id()));
            assertThat(refs).contains(
                    new EntityRef.TargetLabel(labelled.label().orElseThrow()));
            assertThat(refs).contains(
                    new EntityRef.EventId(labelled.bepEventId().orElseThrow()));
        } finally {
            opened.close();
        }
    }

    @Test
    @Timeout(180)
    @DisplayName("filterToLabel narrows visibly and the chip clears it completely")
    void labelFilterNarrowsVisiblyAndClears(@TempDir Path temporary) throws Exception {
        SqliteSessionSource opened = openImportedSession(temporary);
        String label;
        try (EntityReader reader = opened.openEntityReader()) {
            label = reader.firstActionPage(ActionFilter.NONE, ActionSort.ARRIVAL, false, 50)
                    .stream()
                    .filter(row -> row.label().isPresent())
                    .findFirst()
                    .orElseThrow()
                    .label()
                    .orElseThrow();
        }

        AtomicReference<ActionsView> held = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            ActionsView view = new ActionsView();
            held.set(view);
            view.openSession(opened);
        });
        ActionsView view = held.get();
        await(() -> onEdt(() -> view.tableModelForTest() != null));
        long total = onEdt(() -> view.rowSourceForTest().rowCount());

        // Realized in a validated, sized container: setVisible alone
        // schedules no layout, so a test that never validates the tree
        // cannot tell a chip with real bounds from one the user can neither
        // see nor click (the bug this test exists to catch). Container
        // .validate() only recurses into children once the tree is
        // displayable (addNotify() gives it a lightweight peer -- no
        // JFrame needed, and none is possible under headless anyway); a
        // JPanel that never got addNotify() silently skips the recursive
        // layout validate() is supposed to do, which would make the bounds
        // assertion below pass or fail for the wrong reason.
        SwingUtilities.invokeAndWait(() -> {
            view.addNotify();
            view.setSize(900, 500);
            view.validate();
        });

        // A real running window relays out asynchronously once revalidate()
        // is called, but that pump needs a genuine Window ancestor, and
        // headless forbids creating one at all -- see RecordingRepaintManager.
        // Installed for the whole exchange (both the narrow and the clear),
        // and always restored, even on failure.
        RepaintManager originalManager = RepaintManager.currentManager(view);
        RecordingRepaintManager probe = new RecordingRepaintManager();
        SwingUtilities.invokeAndWait(() -> RepaintManager.setCurrentManager(probe));
        try {
            SwingUtilities.invokeAndWait(() -> view.filterToLabel(label));
            await(() -> onEdt(() ->
                    view.rowSourceForTest().filter().labelContains().isPresent()));

            Container toolbar = onEdt(() -> view.labelChipComponentForTest().getParent());
            // JComponent.setVisible(true) revalidates the chip *itself* as a
            // side effect -- that alone is not the fix: the toolbar, laid
            // out while the chip was invisible, has to be told to redo its
            // FlowLayout for the chip's neighbours to make room and for the
            // chip to get real bounds. Without an explicit toolbar
            // .revalidate() this set holds the chip but never the toolbar.
            assertThat(onEdt(() -> probe.requested.contains(toolbar)))
                    .as("filterToLabel must ask the chip's own toolbar to re-lay-out -- relying"
                            + " on the chip's own setVisible(true) alone leaves the toolbar's"
                            + " other children exactly where they were laid out while the chip"
                            + " was invisible, so the chip inherits stale (often zero) bounds"
                            + " and offers no click target")
                    .isTrue();

            assertThat(onEdt(() -> view.rowSourceForTest().filter().labelContains()))
                    .hasValue(label);
            long filtered = onEdt(() -> view.rowSourceForTest().rowCount());
            assertThat(filtered).isPositive().isLessThanOrEqualTo(total);
            // The narrowing is visible: the chip names the label, and the
            // status line says "of" the unfiltered total.
            assertThat(onEdt(view::labelChipForTest)).contains(label);
            assertThat(onEdt(view::statusForTest)).contains("match the filter");

            // Stands in for the layout pass the live app's window pumps on
            // its own once it sees the request just confirmed above;
            // headless, nothing pumps that automatically.
            SwingUtilities.invokeAndWait(view::validate);
            java.awt.Rectangle chipBounds = onEdt(() ->
                    view.labelChipComponentForTest().getBounds());
            assertThat(chipBounds.width).as("label chip width").isGreaterThan(0);
            assertThat(chipBounds.height).as("label chip height").isGreaterThan(0);

            onEdt(() -> {
                probe.requested.clear();
                return null;
            });

            // Cleared with a genuine click, exactly as a user would, rather
            // than reaching past the button into a private method.
            SwingUtilities.invokeAndWait(() -> view.labelChipComponentForTest().doClick());
            await(() -> onEdt(() ->
                    view.rowSourceForTest().filter().labelContains().isEmpty()));

            // clearLabelFilter must pair its setVisible(false) the same way:
            // hiding the chip gets no free revalidate from JComponent (that
            // only fires on becoming visible), so without the explicit call
            // this set stays empty.
            assertThat(onEdt(() -> probe.requested.contains(toolbar)))
                    .as("clearLabelFilter must also ask the toolbar to re-lay-out when the chip"
                            + " hides")
                    .isTrue();
            assertThat(onEdt(() -> view.rowSourceForTest().rowCount())).isEqualTo(total);
            assertThat(onEdt(view::labelChipForTest)).isNull();
            assertThat(onEdt(() -> view.labelChipComponentForTest().isVisible())).isFalse();
        } finally {
            SwingUtilities.invokeAndWait(() -> RepaintManager.setCurrentManager(originalManager));
        }

        SwingUtilities.invokeAndWait(view::closeSession);
        opened.close();
    }

    @Test
    @Timeout(180)
    @DisplayName("a loaded row's refs feed the shared menu, and the toolbar buttons dispatch through it")
    void rowsAndButtonsGoThroughTheFacility(@TempDir Path temporary) throws Exception {
        SqliteSessionSource opened = openImportedSession(temporary);
        Recorder recorder = new Recorder();
        EntityActions actions = new EntityActions(
                EnumSet.of(EntityActions.Command.OPEN_IN_GRAPH,
                        EntityActions.Command.SHOW_ON_TIMELINE,
                        EntityActions.Command.SHOW_SOURCE_EVENT,
                        EntityActions.Command.OPEN_TARGET,
                        EntityActions.Command.SHOW_ACTIONS_FOR_LABEL,
                        EntityActions.Command.REVEAL_ACTION),
                recorder);

        AtomicReference<ActionsView> held = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            ActionsView view = new ActionsView();
            view.installEntityActions(actions);
            held.set(view);
            view.openSession(opened);
        });
        ActionsView view = held.get();
        await(() -> onEdt(() -> view.tableModelForTest() != null));
        // Touching the cell is what makes its page load, as painting would.
        await(() -> onEdt(() -> {
            view.tableModelForTest().getValueAt(0, 0);
            return view.tableModelForTest().rowAt(0) != null;
        }));

        List<EntityRef> refs = onEdt(() -> view.refsAtRow(0));
        ActionRow first = onEdt(() -> view.tableModelForTest().rowAt(0));
        assertThat(refs).contains(new EntityRef.ActionId(first.id()));
        assertThat(refs).contains(new EntityRef.EventId(first.bepEventId().orElseThrow()));

        // The row menu omits "reveal action" — this view is where the action
        // already is — and still offers the cross-view jumps.
        onEdt(() -> {
            var menu = actions.popupFor(refs,
                    java.util.Set.of(EntityActions.Command.REVEAL_ACTION));
            List<String> titles = new ArrayList<>();
            for (int i = 0; i < menu.getComponentCount(); i++) {
                titles.add(((javax.swing.JMenuItem) menu.getComponent(i)).getText());
            }
            assertThat(titles)
                    .contains(EntityActions.Command.OPEN_IN_GRAPH.title(),
                            EntityActions.Command.SHOW_SOURCE_EVENT.title())
                    .doesNotContain(EntityActions.Command.REVEAL_ACTION.title());
            return null;
        });

        SwingUtilities.invokeAndWait(view::closeSession);
        opened.close();
    }

    private static SqliteSessionSource openImportedSession(Path temporary) throws Exception {
        SyntheticBepStream stream = SyntheticBepStream.of(EVENT_COUNT);
        Path source = temporary.resolve("build.bep");
        BepBinaryWriter.write(source, stream);
        SessionManager sessions = new SessionManager(temporary.resolve("sessions"), "0.1.0-test");
        ImportResult imported = new BepImporter(sessions).importFile(source);
        return SqliteSessionSource.open(sessions, imported.sessionRoot());
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition never became true");
            }
            TimeUnit.MILLISECONDS.sleep(10);
        }
    }

    private static <T> T onEdt(Callable<T> read) {
        AtomicReference<T> value = new AtomicReference<>();
        AtomicReference<Exception> failure = new AtomicReference<>();
        try {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    value.set(read.call());
                } catch (Exception e) {
                    failure.set(e);
                }
            });
        } catch (Exception e) {
            throw new AssertionError("EDT read failed", e);
        }
        if (failure.get() != null) {
            throw new AssertionError("EDT read failed", failure.get());
        }
        return value.get();
    }

    private static boolean onEdt(BooleanSupplier read) {
        return onEdt((Callable<Boolean>) read::getAsBoolean);
    }
}
