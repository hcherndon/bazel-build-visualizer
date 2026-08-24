package com.holtherndon.bazelviz.ui.events;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildEvent;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildEventId;
import com.holtherndon.bazelviz.capture.file.importer.BepImporter;
import com.holtherndon.bazelviz.capture.file.importer.ImportResult;
import com.holtherndon.bazelviz.core.event.DecodeStatus;
import com.holtherndon.bazelviz.core.journal.JournalFormat.SourceKind;
import com.holtherndon.bazelviz.format.session.SessionManager;
import com.holtherndon.bazelviz.storage.events.RawLocation;
import com.holtherndon.bazelviz.testsupport.bep.BepBinaryWriter;
import com.holtherndon.bazelviz.testsupport.bep.SyntheticBepStream;
import com.holtherndon.bazelviz.ui.inspect.InspectorHeader;
import com.holtherndon.bazelviz.ui.nav.EntityActions;
import com.holtherndon.bazelviz.ui.nav.EntityRef;
import com.holtherndon.bazelviz.ui.session.RawPayload;
import com.holtherndon.bazelviz.ui.session.SqliteSessionSource;
import java.awt.GraphicsEnvironment;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Events tab's adoption of the shared navigation actions: the target
 * label is parsed at render time — structurally from the decoded payload
 * where one is decoded, from the stored id display where only that exists —
 * and becomes actionable in the rows and the inspector, honestly absent
 * everywhere it cannot be vouched for.
 */
class EventsViewEntityActionsTest {

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

    private static EventRow row(long id, Optional<String> idDisplay) {
        return new EventRow(id, id, 3, DecodeStatus.OK, false, OptionalLong.of(0x99),
                idDisplay, 0, false, new RawLocation(0, 64, 12),
                OptionalLong.of(1_700_000_000_000_000L), 1_700_000_000_000_100L);
    }

    @Test
    @DisplayName("a row's label is parsed from its stored id display, and only when it can be vouched for")
    void rowLabelComesFromTheIdDisplay() {
        assertThat(row(1, Optional.of("TargetCompleted //a:b [cfg k8]")).targetLabel())
                .hasValue("//a:b");
        assertThat(row(2, Optional.of("TestResult //t:t run 1 shard 1 attempt 1")).targetLabel())
                .hasValue("//t:t");
        assertThat(row(3, Optional.of("Progress #12")).targetLabel()).isEmpty();
        assertThat(row(4, Optional.of("TargetCompleted <no label>")).targetLabel()).isEmpty();
        assertThat(row(5, Optional.empty()).targetLabel()).isEmpty();
    }

    @Test
    @DisplayName("the renderer reads the label structurally from the decoded payload's id")
    void rendererExtractsTheStructuredLabel() {
        BuildEvent event = BuildEvent.newBuilder()
                .setId(BuildEventId.newBuilder().setTargetCompleted(
                        BuildEventId.TargetCompletedId.newBuilder().setLabel("//pkg:name")))
                .build();
        RawPayload payload = new RawPayload(event.toByteArray(), SourceKind.BEP_BINARY);

        RawPayloadRenderer.Rendered rendered =
                RawPayloadRenderer.render(payload, DecodeStatus.OK);

        assertThat(rendered.targetLabel()).hasValue("//pkg:name");
    }

    @Test
    @DisplayName("the inspection prefers the structured label and falls back to the display parse")
    void inspectionPrefersStructuredLabel() {
        EventRow displayRow = row(1, Optional.of("TargetCompleted //from-display:x"));
        RawPayload payload = new RawPayload(new byte[] {1}, SourceKind.BEP_BINARY);

        EventInspection structured = EventInspection.loaded(displayRow, payload,
                new RawPayloadRenderer.Rendered("text", Optional.empty(), List.of(),
                        Optional.of("//from-proto:x")),
                "hex");
        assertThat(structured.targetLabel()).hasValue("//from-proto:x");

        EventInspection fallback = EventInspection.loaded(displayRow, payload,
                new RawPayloadRenderer.Rendered("text", Optional.empty(), List.of()),
                "hex");
        assertThat(fallback.targetLabel()).hasValue("//from-display:x");

        assertThat(EventInspection.none().targetLabel()).isEmpty();
    }

    @Test
    @DisplayName("the inspector's header offers label actions for a labelled event"
            + " and nothing otherwise")
    void inspectorOverflowAppearsOnlyWithALabel() throws Exception {
        Recorder recorder = new Recorder();
        EntityActions actions = new EntityActions(
                EnumSet.of(EntityActions.Command.OPEN_TARGET,
                        EntityActions.Command.SHOW_ACTIONS_FOR_LABEL),
                recorder);

        SwingUtilities.invokeAndWait(() -> {
            EventInspectorPanel panel = new EventInspectorPanel();
            panel.installEntityActions(actions);
            InspectorHeader header = panel.headerForTest();

            EventRow labelled = row(1, Optional.of("TargetCompleted //a:b"));
            RawPayload payload = new RawPayload(new byte[] {1}, SourceKind.BEP_BINARY);
            panel.show(EventInspection.loaded(labelled, payload,
                    new RawPayloadRenderer.Rendered("t", Optional.empty(), List.of()), "h"));
            // The label is the subtitle and the thing the menu acts on, so
            // both arrive together or not at all.
            assertThat(header.subtitleForTest()).isEqualTo("//a:b");
            assertThat(header.titleForTest()).contains("Event row 1, sequence 1");
            assertThat(header.overflowForTest().isVisible())
                    .as("the overflow appears for a labelled event")
                    .isTrue();
            JPopupMenu menu = header.overflowMenuForTest();
            assertThat(menu.getComponentCount()).isEqualTo(2);
            ((JMenuItem) menu.getComponent(0)).doClick();
            assertThat(recorder.commands).containsExactly(EntityActions.Command.OPEN_TARGET);
            assertThat(recorder.refs).containsExactly(new EntityRef.TargetLabel("//a:b"));

            EventRow unlabelled = row(2, Optional.of("Progress #4"));
            panel.show(EventInspection.loaded(unlabelled, payload,
                    new RawPayloadRenderer.Rendered("t", Optional.empty(), List.of()), "h"));
            assertThat(header.overflowForTest().isVisible())
                    .as("no overflow for an event with no parseable label")
                    .isFalse();
            assertThat(header.subtitleForTest()).isEqualTo(" ");

            panel.show(EventInspection.none());
            assertThat(header.overflowForTest().isVisible()).isFalse();
        });
    }

    @Test
    @Timeout(180)
    @DisplayName("over a real session, a labelled row offers the label actions and dispatches the label")
    void rowsOfferLabelActionsOverARealSession(@TempDir Path temporary) throws Exception {
        SyntheticBepStream stream = SyntheticBepStream.of(EVENT_COUNT);
        Path source = temporary.resolve("build.bep");
        BepBinaryWriter.write(source, stream);
        SessionManager sessions = new SessionManager(temporary.resolve("sessions"), "0.1.0-test");
        ImportResult imported = new BepImporter(sessions).importFile(source);
        SqliteSessionSource opened = SqliteSessionSource.open(sessions, imported.sessionRoot());

        Recorder recorder = new Recorder();
        EntityActions actions = new EntityActions(
                EnumSet.of(EntityActions.Command.OPEN_TARGET,
                        EntityActions.Command.SHOW_ACTIONS_FOR_LABEL),
                recorder);

        AtomicReference<EventsView> held = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            EventsView view = new EventsView();
            view.installEntityActions(actions);
            view.openSession(opened, failure -> {
                throw new AssertionError(failure);
            });
            held.set(view);
        });
        EventsView view = held.get();
        await(() -> onEdt(() -> view.tableModelForTest() != null));

        // Find a row whose id display names a label. Touching the cell is what
        // makes its page load, exactly as painting it would.
        await(() -> onEdt(() -> firstLabelledRow(view) >= 0));
        int labelledRow = onEdt(() -> firstLabelledRow(view));

        List<EntityRef> refs = onEdt(() -> view.refsAtRow(labelledRow));
        assertThat(refs).hasSize(1);
        assertThat(refs.getFirst()).isInstanceOf(EntityRef.TargetLabel.class);
        String label = ((EntityRef.TargetLabel) refs.getFirst()).label();
        assertThat(label).startsWith("//");

        // The menu the right-click would show, built and clicked headlessly.
        onEdt(() -> {
            JPopupMenu menu = actions.popupFor(refs, Set.of());
            assertThat(menu.getComponentCount()).isEqualTo(2);
            ((JMenuItem) menu.getComponent(0)).doClick();
            return null;
        });
        assertThat(recorder.commands).containsExactly(EntityActions.Command.OPEN_TARGET);
        assertThat(recorder.refs).containsExactly(new EntityRef.TargetLabel(label));

        // A row that is not about a target offers nothing at all.
        int unlabelledRow = onEdt(() -> firstUnlabelledRow(view));
        if (unlabelledRow >= 0) {
            assertThat(onEdt(() -> view.refsAtRow(unlabelledRow))).isEmpty();
        }

        SwingUtilities.invokeAndWait(view::closeSession);
        opened.close();
    }

    /** EDT: the first loaded row with a parseable label, or -1; touches cells to load pages. */
    private static int firstLabelledRow(EventsView view) {
        var model = view.tableModelForTest();
        int limit = Math.min(model.getRowCount(), 250);
        for (int rowIndex = 0; rowIndex < limit; rowIndex++) {
            model.getValueAt(rowIndex, 0);
            if (!view.refsAtRow(rowIndex).isEmpty()) {
                return rowIndex;
            }
        }
        return -1;
    }

    /** EDT: the first loaded row with no parseable label, or -1. */
    private static int firstUnlabelledRow(EventsView view) {
        var model = view.tableModelForTest();
        int limit = Math.min(model.getRowCount(), 250);
        for (int rowIndex = 0; rowIndex < limit; rowIndex++) {
            if (model.rowAt(rowIndex) != null && view.refsAtRow(rowIndex).isEmpty()) {
                return rowIndex;
            }
        }
        return -1;
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

    private static int onEdt(java.util.function.IntSupplier read) {
        return onEdt((Callable<Integer>) read::getAsInt);
    }
}
