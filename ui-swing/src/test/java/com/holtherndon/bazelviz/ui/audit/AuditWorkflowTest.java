package com.holtherndon.bazelviz.ui.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.devtools.build.lib.exec.Protos.Digest;
import com.google.devtools.build.lib.exec.Protos.File;
import com.google.devtools.build.lib.exec.Protos.Platform;
import com.google.devtools.build.lib.exec.Protos.SpawnExec;
import com.holtherndon.bazelviz.capture.repro.ReproducibilityCoordinator.Cleanup;
import com.holtherndon.bazelviz.capture.repro.ReproducibilityCoordinator.Result;
import com.holtherndon.bazelviz.capture.repro.ReproducibilityCoordinator.State;
import com.holtherndon.bazelviz.format.session.ManagedSessionLayout;
import com.holtherndon.bazelviz.runner.plan.InstrumentationPlanner;
import com.holtherndon.bazelviz.ui.capture.CaptureStatusModel;
import com.holtherndon.bazelviz.ui.repro.HermeticityView;
import com.holtherndon.bazelviz.ui.session.SessionMutationCoordinator;
import java.awt.Component;
import java.awt.Container;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import javax.swing.JButton;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests the finished-capture handoff, without creating a coordinator or executing Bazel. */
final class AuditWorkflowTest {
  @TempDir Path temporary;

  @Test
  void finishedPairAutomaticallyOpensComparisonAndLinksBothOriginalSessions() throws Exception {
    Fixture fixture = fixture(State.CAPTURED, true);
    Harness harness = harness();
    try {
      edt(
          () -> {
            harness.workflow().captureFinished(fixture.result());
            return null;
          });
      // No Choose or Compare button is clicked: the completed audit must open itself.
      await(() -> table(harness.view()).getRowCount() == 1);
      edt(
          () -> {
            assertThat(source(harness.view(), "Execution log A").getText())
                .isEqualTo(fixture.logA().toString());
            assertThat(source(harness.view(), "Execution log B").getText())
                .isEqualTo(fixture.logB().orElseThrow().toString());
            assertThat(area(harness.view(), "hermeticity.summary").getText())
                .contains(
                    "Run A: 1 recorded actions", "Run B: 1 recorded actions", "Matched pairs: 1");
            assertThat(table(harness.view()).getValueAt(0, 1)).isEqualTo("//fixture:output");
            assertThat(area(harness.view(), "hermeticity.coverage").getText())
                .contains(fixture.directory().toString(), "CAPTURED")
                .containsOnlyOnce("Fixture capture note");
            assertThat(harness.host().ready).isEqualTo(1);
            assertThat(harness.host().revealed).isEqualTo(1);
            assertThat(harness.host().status.phase()).isEqualTo(CaptureStatusModel.Phase.DONE);
            button(harness.view(), "Open run A").doClick();
            button(harness.view(), "Open run B").doClick();
            assertThat(harness.host().opened)
                .containsExactly(fixture.sessionA(), fixture.sessionB().orElseThrow());
            assertThat(harness.host().allCallbacksOnEdt).isTrue();
            return null;
          });
      try (var entries = Files.list(temporary.resolve("comparisons"))) {
        assertThat(entries).isNotEmpty();
      }
    } finally {
      close(harness);
    }
    assertScratchEmpty();
    assertThat(Files.exists(fixture.logA())).isTrue();
    assertThat(Files.exists(fixture.logB().orElseThrow())).isTrue();
  }

  @Test
  void reopeningUsesTheSavedRcPolicyAndKeepsLegacyRecordsIgnored() throws Exception {
    for (String rcPolicy : List.of("READ", "IGNORE", "")) {
      Fixture fixture = fixture(State.CANCELLED, false, rcPolicy);
      Harness harness = harness();
      try {
        edt(
            () -> {
              harness.workflow().captureFinished(fixture.result());
              return null;
            });
        await(() -> harness.host().revealed == 1);
        edt(
            () -> {
              String coverage = area(harness.view(), "hermeticity.coverage").getText();
              assertThat(coverage).contains("Two matching runs do not prove hermeticity.");
              if (rcPolicy.equals("READ")) {
                assertThat(coverage)
                    .contains("Normal rc files and named configs are enabled")
                    .doesNotContain("Rc files are ignored", "rc-free");
              } else {
                assertThat(coverage)
                    .contains("Rc files are ignored for both builds and helpers")
                    .doesNotContain("Normal rc files and named configs are enabled");
              }
              assertThat(table(harness.view()).getRowCount()).isZero();
              return null;
            });
        assertScratchEmpty();
      } finally {
        close(harness);
      }
    }
  }

  @Test
  void cancelledAndFailedPartialRunsStayLinkedWithoutStartingAPairComparison() throws Exception {
    for (State state : List.of(State.CANCELLED, State.FAILED)) {
      Fixture fixture = fixture(state, false);
      Harness harness = harness();
      try {
        edt(
            () -> {
              harness.workflow().captureFinished(fixture.result());
              return null;
            });
        await(() -> harness.host().revealed == 1);
        edt(
            () -> {
              assertThat(source(harness.view(), "Execution log A").getText())
                  .isEqualTo(fixture.logA().toString());
              assertThat(source(harness.view(), "Execution log B").getText()).isEmpty();
              assertThat(button(harness.view(), "Compare").isEnabled()).isFalse();
              assertThat(table(harness.view()).getRowCount()).isZero();
              assertThat(area(harness.view(), "hermeticity.coverage").getText())
                  .contains("did not complete successfully", "One or both verified execution logs");
              assertThat(harness.host().status.phase())
                  .isEqualTo(
                      state == State.CANCELLED
                          ? CaptureStatusModel.Phase.CANCELLED
                          : CaptureStatusModel.Phase.FAILED);
              assertThat(button(harness.view(), "Open run A").isEnabled()).isTrue();
              button(harness.view(), "Open run A").doClick();
              assertThat(button(harness.view(), "Open run B").isEnabled())
                  .isEqualTo(fixture.sessionB().isPresent());
              if (fixture.sessionB().isPresent()) {
                button(harness.view(), "Open run B").doClick();
                assertThat(harness.host().opened)
                    .containsExactly(fixture.sessionA(), fixture.sessionB().orElseThrow());
              } else assertThat(harness.host().opened).containsExactly(fixture.sessionA());
              assertThat(harness.host().allCallbacksOnEdt).isTrue();
              return null;
            });
        assertScratchEmpty();
      } finally {
        close(harness);
      }
    }
  }

  @Test
  void automaticallyLinkedLogsRejectChangedBytesBeforePublishingAComparison() throws Exception {
    Fixture fixture = fixture(State.CAPTURED, true);
    // The operation record still carries the checksum of the preserved original log.
    Files.writeString(fixture.logB().orElseThrow(), "replaced after capture");
    Harness harness = harness();
    try {
      edt(
          () -> {
            harness.workflow().captureFinished(fixture.result());
            return null;
          });
      await(
          () -> area(harness.view(), "hermeticity.status").getText().contains("evidence changed"));
      edt(
          () -> {
            assertThat(table(harness.view()).getRowCount()).isZero();
            assertThat(area(harness.view(), "hermeticity.summary").getText())
                .contains("No differences have been calculated");
            assertThat(harness.host().revealed).isEqualTo(1);
            assertThat(button(harness.view(), "Open run A").isEnabled()).isTrue();
            assertThat(button(harness.view(), "Open run B").isEnabled()).isTrue();
            return null;
          });
    } finally {
      close(harness);
    }
    assertScratchEmpty();
    assertThat(Files.readString(fixture.logB().orElseThrow())).isEqualTo("replaced after capture");
  }

  private Harness harness() throws Exception {
    return edt(
        () -> {
          HermeticityView view = new HermeticityView();
          RecordingHost host = new RecordingHost();
          return new Harness(
              view,
              new AuditWorkflow(null, view, temporary, new SessionMutationCoordinator(), host),
              host);
        });
  }

  private Fixture fixture(State state, boolean completePair) throws Exception {
    return fixture(state, completePair, "");
  }

  private Fixture fixture(State state, boolean completePair, String rcPolicy) throws Exception {
    Path directory = Files.createDirectory(temporary.resolve("audit-" + UUID.randomUUID()));
    Path sessions = Files.createDirectory(directory.resolve("sessions"));
    Path a = session(sessions);
    Path logA = log(a);
    Optional<Path> b = state == State.CANCELLED ? Optional.empty() : Optional.of(session(sessions));
    Optional<Path> logB = completePair ? Optional.of(log(b.orElseThrow())) : Optional.empty();
    Properties record = new Properties();
    record.setProperty("format", "1");
    if (!rcPolicy.isEmpty()) record.setProperty("rcPolicy", rcPolicy);
    record.setProperty("state", state.name());
    record.setProperty("cleanup", "REMOVED");
    record.setProperty("step", completePair ? "COMPARE_READY" : "BETWEEN_RUNS");
    record.setProperty("sessionA", a.toString());
    if (b.isPresent()) record.setProperty("sessionB", b.orElseThrow().toString());
    record.setProperty("preserved.PRESERVE_A", logA.toString());
    record.setProperty("execution.PRESERVE_A.sha256", sha256(logA));
    record.setProperty("execution.PRESERVE_A.evidenceBindingNotice", "Fixture capture note");
    if (logB.isPresent()) {
      record.setProperty("preserved.PRESERVE_B", logB.orElseThrow().toString());
      record.setProperty("execution.PRESERVE_B.sha256", sha256(logB.orElseThrow()));
    }
    try (var output = Files.newOutputStream(directory.resolve("operation.properties"))) {
      record.store(output, "Headless finished-capture fixture; no commands are executed");
    }
    return new Fixture(
        directory,
        a,
        b,
        logA,
        logB,
        new Result(
            directory,
            state,
            Cleanup.REMOVED,
            Optional.empty(),
            Optional.empty(),
            List.of("Fixture capture note")));
  }

  private static Path session(Path parent) throws IOException {
    String id = UUID.randomUUID().toString();
    Path root = Files.createDirectory(parent.resolve("session-" + id));
    ManagedSessionLayout layout = ManagedSessionLayout.at(root);
    Files.createDirectory(layout.rawDirectory());
    Files.writeString(
        layout.manifestFile(),
        """
        {"formatVersion":1,"appVersion":"0.1.0","sessionId":"%s","createdMicros":1,"state":"READY"}
        """
            .formatted(id));
    return root;
  }

  private static Path log(Path session) throws IOException {
    Path log =
        ManagedSessionLayout.at(session)
            .rawDirectory()
            .resolve(InstrumentationPlanner.EXECUTION_LOG_BINARY_FILE);
    SpawnExec spawn =
        SpawnExec.newBuilder()
            .setTargetLabel("//fixture:output")
            .setMnemonic("Genrule")
            .setRunner("local")
            .setPlatform(Platform.getDefaultInstance())
            .addCommandArgs("fixture-tool")
            .addListedOutputs("fixture.out")
            .addActualOutputs(
                File.newBuilder()
                    .setPath("fixture.out")
                    .setDigest(
                        Digest.newBuilder()
                            .setHash("1".repeat(64))
                            .setSizeBytes(1)
                            .setHashFunctionName("SHA-256")))
            .build();
    try (var output = Files.newOutputStream(log)) {
      spawn.writeDelimitedTo(output);
    }
    return log;
  }

  private static String sha256(Path source) throws Exception {
    return HexFormat.of()
        .formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source)));
  }

  private void assertScratchEmpty() throws IOException {
    Path scratch = temporary.resolve("comparisons");
    if (Files.exists(scratch)) {
      try (var entries = Files.list(scratch)) {
        assertThat(entries).isEmpty();
      }
    }
  }

  private static void close(Harness harness) throws Exception {
    edt(harness.workflow()::closeAsync).toCompletableFuture().get(5, TimeUnit.SECONDS);
  }

  private static void await(BooleanSupplier condition) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (!edt(condition::getAsBoolean)) {
      assertThat(System.nanoTime()).as("UI update completed before timeout").isLessThan(deadline);
      LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
    }
  }

  private static <T> T edt(Supplier<T> action) throws Exception {
    AtomicReference<T> result = new AtomicReference<>();
    SwingUtilities.invokeAndWait(() -> result.set(action.get()));
    return result.get();
  }

  private static JButton button(Container root, String text) {
    return find(root, JButton.class, button -> text.equals(button.getText()));
  }

  private static JTable table(Container root) {
    return find(root, JTable.class, table -> "hermeticity.actions".equals(table.getName()));
  }

  private static JTextArea area(Container root, String name) {
    return find(root, JTextArea.class, area -> name.equals(area.getName()));
  }

  private static JTextField source(Container root, String accessibleName) {
    return find(
        root,
        JTextField.class,
        field -> accessibleName.equals(field.getAccessibleContext().getAccessibleName()));
  }

  private static <T extends Component> T find(Container root, Class<T> type, Predicate<T> match) {
    if (type.isInstance(root) && match.test(type.cast(root))) return type.cast(root);
    for (Component child : root.getComponents()) {
      if (type.isInstance(child) && match.test(type.cast(child))) return type.cast(child);
      if (child instanceof Container nested) {
        T found = find(nested, type, match);
        if (found != null) return found;
      }
    }
    return null;
  }

  private record Fixture(
      Path directory,
      Path sessionA,
      Optional<Path> sessionB,
      Path logA,
      Optional<Path> logB,
      Result result) {}

  private record Harness(HermeticityView view, AuditWorkflow workflow, RecordingHost host) {}

  private static final class RecordingHost implements AuditWorkflow.Host {
    private int ready;
    private int revealed;
    private CaptureStatusModel status;
    private final List<Path> opened = new ArrayList<>();
    private boolean allCallbacksOnEdt = true;

    @Override
    public void ready() {
      allCallbacksOnEdt &= SwingUtilities.isEventDispatchThread();
      ready++;
    }

    @Override
    public void status(CaptureStatusModel value) {
      allCallbacksOnEdt &= SwingUtilities.isEventDispatchThread();
      status = value;
    }

    @Override
    public void reveal() {
      allCallbacksOnEdt &= SwingUtilities.isEventDispatchThread();
      revealed++;
    }

    @Override
    public void openSession(Path directory) {
      allCallbacksOnEdt &= SwingUtilities.isEventDispatchThread();
      opened.add(directory);
    }
  }
}
