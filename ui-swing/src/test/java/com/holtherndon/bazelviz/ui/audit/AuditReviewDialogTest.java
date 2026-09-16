package com.holtherndon.bazelviz.ui.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.capture.bes.BesEndpoint;
import com.holtherndon.bazelviz.capture.live.Preflight;
import com.holtherndon.bazelviz.capture.repro.ReproducibilityCoordinator.Review;
import com.holtherndon.bazelviz.runner.caps.BazelCapabilities;
import com.holtherndon.bazelviz.runner.caps.Capability;
import com.holtherndon.bazelviz.runner.command.BazelCommand;
import com.holtherndon.bazelviz.runner.exec.BazelExecutable;
import com.holtherndon.bazelviz.runner.plan.CapturePreset;
import com.holtherndon.bazelviz.runner.plan.InstrumentationPlan;
import com.holtherndon.bazelviz.runner.plan.PlanRequest;
import com.holtherndon.bazelviz.runner.plan.SourceAvailability;
import com.holtherndon.bazelviz.runner.repro.ReproducibilityPlan;
import com.holtherndon.bazelviz.runner.workspace.WorkspaceInfo;
import com.holtherndon.bazelviz.ui.theme.ScrollableViewport;
import com.holtherndon.bazelviz.ui.theme.SectionPane;
import java.awt.Component;
import java.awt.Container;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

final class AuditReviewDialogTest {
  @Test
  void validSetupNeedsOnlyTheExplicitRunActionAndBlockedSetupCannotRun() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          List<AuditReviewDialog.Choice> choices = new ArrayList<>();
          var content = AuditReviewDialog.content(review(List.of()), choices::add);
          assertThat(choices).isEmpty();
          assertThat(content.run().isEnabled()).isTrue();
          assertThat(controls(content.panel(), JCheckBox.class))
              .singleElement()
              .satisfies(
                  checkbox -> {
                    assertThat(checkbox.getText()).isEqualTo("Ignore rc files");
                    assertThat(checkbox.isSelected()).isFalse();
                    assertThat(checkbox.isEnabled()).isTrue();
                  });
          assertThat(controls(content.panel(), JTextArea.class))
              .anyMatch(
                  area -> area.getText().contains("Run both builds approves two uncached builds"));
          content.run().doClick();
          assertThat(choices).containsExactly(AuditReviewDialog.Choice.RUN_BOTH);

          var blocked =
              AuditReviewDialog.content(
                  review(List.of("Execution log was declined")), choices::add);
          blocked.run().doClick();
          assertThat(blocked.run().isEnabled()).isFalse();
          assertThat(blocked.run().getToolTipText()).contains("Execution log was declined");
          assertThat(choices).hasSize(1);
          assertThat(blocked.tabs().getSelectedIndex()).isZero();
          SectionPane firstSection =
              controls((Container) blocked.tabs().getComponentAt(0), SectionPane.class).getFirst();
          assertThat(controls(firstSection, JTextArea.class))
              .anyMatch(area -> area.getText().equals("Execution log was declined"));
        });
  }

  @Test
  void rcModeCanBeChangedInEitherDirectionEvenWhenLaunchIsBlocked() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          for (boolean ignoreRcFiles : List.of(false, true)) {
            for (List<String> blockers :
                List.of(List.<String>of(), List.of("Resolve required capture option"))) {
              List<AuditReviewDialog.Choice> choices = new ArrayList<>();
              var content =
                  AuditReviewDialog.content(
                      review(blockers, Set.of(), ignoreRcFiles), choices::add);
              JCheckBox checkbox = controls(content.panel(), JCheckBox.class).getFirst();
              assertThat(checkbox.isSelected()).isEqualTo(ignoreRcFiles);
              assertThat(checkbox.isEnabled()).isTrue();
              assertThat(choices).isEmpty();
              checkbox.doClick();
              assertThat(choices)
                  .containsExactly(
                      ignoreRcFiles
                          ? AuditReviewDialog.Choice.USE_RC_FILES
                          : AuditReviewDialog.Choice.IGNORE_RC_FILES);
              assertThat(content.run().isEnabled()).isEqualTo(blockers.isEmpty());
            }
          }
        });
  }

  @Test
  void configurationCopyAndCommandsDescribeTheReviewedMode() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          for (boolean ignoreRcFiles : List.of(false, true)) {
            var content =
                AuditReviewDialog.content(review(List.of(), Set.of(), ignoreRcFiles), choice -> {});
            String mode = ignoreRcFiles ? "ignored" : "enabled";
            assertThat(content.run().getToolTipText()).contains("rc files " + mode);
            List<JTextArea> text = controls(content.panel(), JTextArea.class);
            assertThat(text)
                .anyMatch(
                    area ->
                        area.getText()
                            .equals(
                                "Run both builds approves two uncached builds with rc files "
                                    + mode
                                    + "."));
            assertThat(text)
                .anyMatch(
                    area ->
                        area.getText()
                            .contains(
                                ignoreRcFiles
                                    ? "ignores system, user and workspace rc files"
                                    : "reads your normal system, user and workspace rc files"));
            assertThat(
                    text.stream()
                        .anyMatch(area -> area.getText().contains("--ignore_all_rc_files")))
                .isEqualTo(ignoreRcFiles);
            assertThat(text).anyMatch(area -> area.getText().contains("over rc settings"));
          }
        });
  }

  @Test
  void captureReviewAndCancellationReturnChoicesWithoutRunningAnything() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          List<AuditReviewDialog.Choice> choices = new ArrayList<>();
          var content =
              AuditReviewDialog.content(
                  review(List.of("Resolve required capture option")), choices::add);
          button(content.panel(), "Capture A settings…").doClick();
          button(content.panel(), "Capture B settings…").doClick();
          content.cancel().doClick();
          assertThat(choices)
              .containsExactly(
                  AuditReviewDialog.Choice.REVIEW_A,
                  AuditReviewDialog.Choice.REVIEW_B,
                  AuditReviewDialog.Choice.CANCEL);
        });
  }

  @Test
  void disabledExecutionLogsHaveAnExplicitRecoveryActionThatDoesNotLaunch() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          for (Capability veto :
              List.of(Capability.EXECUTION_LOG_COMPACT, Capability.EXECUTION_LOG_BINARY)) {
            List<AuditReviewDialog.Choice> choices = new ArrayList<>();
            var content =
                AuditReviewDialog.content(
                    review(List.of("Execution logs are disabled"), Set.of(veto)), choices::add);
            button(content.panel(), "Enable execution logs").doClick();
            assertThat(choices).containsExactly(AuditReviewDialog.Choice.ENABLE_LOGS);
            assertThat(content.run().isEnabled()).isFalse();
          }
          var probeFailure =
              AuditReviewDialog.content(
                  review(List.of("Could not probe compact logging")), choice -> {});
          assertThat(controls(probeFailure.panel(), JButton.class))
              .noneMatch(button -> button.getText().equals("Enable execution logs"));
        });
  }

  @Test
  void allReviewTabsScrollAndUseSelectableWrappingPlainText() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          var content = AuditReviewDialog.content(review(List.of()), choice -> {});
          assertThat(content.tabs().getTabCount()).isEqualTo(4);
          content.panel().setSize(480, 420);
          content.panel().doLayout();
          for (int index = 0; index < content.tabs().getTabCount(); index++) {
            assertThat(content.tabs().getComponentAt(index)).isInstanceOf(JScrollPane.class);
            JScrollPane scroll = (JScrollPane) content.tabs().getComponentAt(index);
            assertThat(scroll.getViewport().getView()).isInstanceOf(ScrollableViewport.class);
            ScrollableViewport viewport = (ScrollableViewport) scroll.getViewport().getView();
            assertThat(viewport.getScrollableTracksViewportWidth()).isTrue();
            assertThat(viewport.getScrollableTracksViewportHeight()).isFalse();
            Container body = (Container) viewport.getComponent(0);
            for (Component child : body.getComponents()) {
              if (child instanceof JComponent component) {
                assertThat(component.getAlignmentX()).isEqualTo(Component.LEFT_ALIGNMENT);
              }
            }
          }
          List<JTextArea> text = controls(content.panel(), JTextArea.class);
          assertThat(text)
              .isNotEmpty()
              .allSatisfy(
                  area -> {
                    assertThat(area.isEditable()).isFalse();
                    assertThat(area.isFocusable()).isTrue();
                    assertThat(area.getLineWrap()).isTrue();
                    assertThat(area.getClientProperty("html.disable")).isEqualTo(Boolean.TRUE);
                  });
          assertThat(text)
              .anyMatch(
                  area ->
                      area.getText()
                          .contains("reads your normal system, user and workspace rc files"));
          assertThat(text).anyMatch(area -> area.getText().contains("/private/audit/output-base"));
        });
  }

  @Test
  void numberedArgumentsKeepWhitespaceQuotesAndLiteralMarkupUnambiguous() {
    BazelCommand command =
        BazelCommand.builder(Path.of("/path with spaces/bazel"), Path.of("/workspace"))
            .command("build")
            .commandArgs(List.of("--define=message=a\"b\nc\\d", "<html><img src='remote'>"))
            .targets(List.of("//:x"))
            .build();
    String displayed = AuditReviewDialog.commandText(command);
    assertThat(displayed)
        .contains(
            "[0] \"/path with spaces/bazel\"",
            "[2] \"--define=message=a\\\"b\\nc\\\\d\"",
            "<html><img src='remote'>");
    assertThat(displayed.lines().count()).isEqualTo(command.toArgv().size());
  }

  private static Review review(List<String> blockers) {
    return review(blockers, Set.of());
  }

  private static Review review(List<String> blockers, Set<Capability> vetoed) {
    return review(blockers, vetoed, false);
  }

  private static Review review(
      List<String> blockers, Set<Capability> vetoed, boolean ignoreRcFiles) {
    Path executable = Path.of("/usr/bin/bazel");
    Path workspace = Path.of("/workspace");
    BazelCommand original =
        BazelCommand.builder(executable, workspace)
            .command("build")
            .targets(List.of("//..."))
            .build();
    ReproducibilityPlan protocol =
        ignoreRcFiles
            ? ReproducibilityPlan.controlled(original, "/private/audit/output-base", true)
            : ReproducibilityPlan.controlled(original, "/private/audit/output-base");
    BazelCapabilities capabilities = BazelCapabilities.unprobed("inert UI fixture");
    InstrumentationPlan plan =
        new InstrumentationPlan(
            protocol.build(),
            protocol.build(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            new SourceAvailability(Map.of()),
            CapturePreset.defaultPreset());
    PlanRequest request =
        PlanRequest.initial(
            protocol.build(),
            capabilities,
            CapturePreset.defaultPreset(),
            Path.of("/private/audit/raw"),
            Optional.of("grpc://127.0.0.1:43210"));
    for (Capability capability : vetoed) request = request.vetoing(capability);
    Preflight capture =
        new Preflight(
            new BazelExecutable(
                "bazel",
                executable,
                "bazel 9.2.0",
                Optional.empty(),
                Optional.of("9.2.0"),
                Optional.empty(),
                false),
            new WorkspaceInfo(
                workspace,
                Optional.of(workspace),
                Optional.of("MODULE.bazel"),
                WorkspaceInfo.Detection.USER_SELECTED),
            capabilities,
            BesEndpoint.loopback(43210),
            plan,
            request);
    return new Review(
        Path.of("/private/audit"),
        protocol,
        capture,
        capture,
        blockers,
        List.of("Inputs outside the repository remain outside source snapshot coverage."));
  }

  private static JButton button(Container root, String name) {
    return controls(root, JButton.class).stream()
        .filter(button -> name.equals(button.getText()))
        .findFirst()
        .orElseThrow();
  }

  private static <T extends Component> List<T> controls(Container parent, Class<T> type) {
    List<T> result = new ArrayList<>();
    for (Component child : parent.getComponents()) {
      if (type.isInstance(child)) {
        result.add(type.cast(child));
      }
      if (child instanceof Container container) {
        result.addAll(controls(container, type));
      }
    }
    return result;
  }
}
