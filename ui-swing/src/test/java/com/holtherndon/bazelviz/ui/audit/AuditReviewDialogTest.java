package com.holtherndon.bazelviz.ui.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.capture.bes.BesEndpoint;
import com.holtherndon.bazelviz.capture.live.Preflight;
import com.holtherndon.bazelviz.capture.repro.ReproducibilityCoordinator.Review;
import com.holtherndon.bazelviz.runner.caps.BazelCapabilities;
import com.holtherndon.bazelviz.runner.command.BazelCommand;
import com.holtherndon.bazelviz.runner.exec.BazelExecutable;
import com.holtherndon.bazelviz.runner.plan.CapturePreset;
import com.holtherndon.bazelviz.runner.plan.InstrumentationPlan;
import com.holtherndon.bazelviz.runner.plan.PlanRequest;
import com.holtherndon.bazelviz.runner.plan.SourceAvailability;
import com.holtherndon.bazelviz.runner.repro.ReproducibilityPlan;
import com.holtherndon.bazelviz.runner.workspace.WorkspaceInfo;
import com.holtherndon.bazelviz.ui.theme.ScrollableViewport;
import java.awt.Component;
import java.awt.Container;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.swing.JButton;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

final class AuditReviewDialogTest {
  @Test
  void launchingRequiresBothAValidPlanAndExplicitNoRcAcknowledgement() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          List<AuditReviewDialog.Choice> choices = new ArrayList<>();
          var content = AuditReviewDialog.content(review(List.of()), choices::add);
          assertThat(content.run().isEnabled()).isFalse();
          content.run().doClick();
          assertThat(choices).isEmpty();
          content.acknowledgement().doClick();
          assertThat(content.run().isEnabled()).isTrue();
          content.run().doClick();
          assertThat(choices).containsExactly(AuditReviewDialog.Choice.RUN_BOTH);
          content.acknowledgement().doClick();
          assertThat(content.run().isEnabled()).isFalse();

          var blocked =
              AuditReviewDialog.content(
                  review(List.of("Execution log was declined")), choices::add);
          assertThat(blocked.acknowledgement().isEnabled()).isFalse();
          blocked.acknowledgement().setSelected(true);
          blocked.run().doClick();
          assertThat(blocked.run().isEnabled()).isFalse();
          assertThat(choices).hasSize(1);
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
          button(content.panel(), "Review capture A").doClick();
          button(content.panel(), "Review capture B").doClick();
          content.cancel().doClick();
          assertThat(choices)
              .containsExactly(
                  AuditReviewDialog.Choice.REVIEW_A,
                  AuditReviewDialog.Choice.REVIEW_B,
                  AuditReviewDialog.Choice.CANCEL);
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
                  area -> area.getText().contains("ignores system, user and workspace rc files"));
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
    Path executable = Path.of("/usr/bin/bazel");
    Path workspace = Path.of("/workspace");
    BazelCommand original =
        BazelCommand.builder(executable, workspace)
            .command("build")
            .targets(List.of("//..."))
            .build();
    ReproducibilityPlan protocol =
        ReproducibilityPlan.controlled(original, "/private/audit/output-base");
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
            PlanRequest.initial(
                protocol.build(),
                capabilities,
                CapturePreset.defaultPreset(),
                Path.of("/private/audit/raw"),
                Optional.of("grpc://127.0.0.1:43210")));
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
