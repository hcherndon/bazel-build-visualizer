package com.holtherndon.bazelviz.ui.preferences;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JButton;
import javax.swing.SwingUtilities;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class WorkspaceDiscoveryPreferencesPanelTest {

  @BeforeAll
  static void requireHeadless() {
    assertThat(GraphicsEnvironment.isHeadless()).isTrue();
  }

  @Test
  void presentsTheDiscoveryContractInATitledSyntaxEditor() throws Exception {
    WorkspaceDiscoveryPreferencesPanel panel = onEdt(() -> panel(""));

    assertThat(panel.getComponent(0).getAccessibleContext().getAccessibleName())
        .isEqualTo("Workspace Discovery");
    assertThat(onEdt(() -> panel.editorForTest().getRows())).isGreaterThan(1);
    assertThat(onEdt(() -> panel.editorScrollForTest().getLineNumbersEnabled())).isTrue();
    assertThat(onEdt(() -> panel.editorForTest().getSyntaxEditingStyle()))
        .isEqualTo(SyntaxConstants.SYNTAX_STYLE_NONE);
    assertThat(onEdt(() -> panel.detectedLanguageForTest().getText()))
        .isEqualTo("Detected language: Plain text");
    assertThat(onEdt(() -> panel.instructionsForTest().getText()))
        .contains("local|name|working-directory")
        .contains("ssh|name|destination|working-directory")
        .contains("OpenSSH destination or configured Host alias")
        .contains("ports, jump hosts, identity files")
        .contains("~/.ssh/config")
        .contains("saves the current editor text before starting discovery");
    assertThat(button(panel, "Run Discovery Now").getToolTipText())
        .contains("Save the current script");
  }

  @Test
  void firstLineShebangChangesTheDetectedLanguageLive() throws Exception {
    WorkspaceDiscoveryPreferencesPanel panel = onEdt(() -> panel("#!/bin/sh\n"));

    assertLanguage(panel, "Shell", SyntaxConstants.SYNTAX_STYLE_UNIX_SHELL);
    setScript(panel, "#!/bin/bash -e\necho workspace\n");
    assertLanguage(panel, "Shell", SyntaxConstants.SYNTAX_STYLE_UNIX_SHELL);
    setScript(panel, "#!/usr/bin/env zsh\necho workspace\n");
    assertLanguage(panel, "Shell", SyntaxConstants.SYNTAX_STYLE_UNIX_SHELL);
    setScript(panel, "#!/usr/bin/env -S python3 -u\nprint('workspace')\n");
    assertLanguage(panel, "Python", SyntaxConstants.SYNTAX_STYLE_PYTHON);
    setScript(panel, "#!/opt/ruby3\nputs 'workspace'\n");
    assertLanguage(panel, "Ruby", SyntaxConstants.SYNTAX_STYLE_RUBY);
    setScript(panel, "#!/usr/bin/perl\nprint 'workspace';\n");
    assertLanguage(panel, "Perl", SyntaxConstants.SYNTAX_STYLE_PERL);
    setScript(panel, "#!/usr/bin/env node\nconsole.log('workspace');\n");
    assertLanguage(panel, "JavaScript", SyntaxConstants.SYNTAX_STYLE_JAVASCRIPT);
    setScript(panel, "echo no-first-line-shebang\n#!/bin/bash\n");
    assertLanguage(panel, "Plain text", SyntaxConstants.SYNTAX_STYLE_NONE);
    setScript(panel, "  #!/bin/bash\necho not-an-executable-shebang\n");
    assertLanguage(panel, "Plain text", SyntaxConstants.SYNTAX_STYLE_NONE);
  }

  @Test
  void actionsReceiveTheCurrentScriptWithoutPerformingIo() throws Exception {
    AtomicReference<String> saved = new AtomicReference<>();
    AtomicReference<String> run = new AtomicReference<>();
    WorkspaceDiscoveryPreferencesPanel panel =
        onEdt(() -> new WorkspaceDiscoveryPreferencesPanel("old", saved::set, run::set));
    String current = "#!/bin/bash\nprintf 'local|Repo|/code/repo\\n'\n";

    onEdt(
        () -> {
          panel.editorForTest().setText(current);
          button(panel, "Run Discovery Now").doClick();
          button(panel, "Save").doClick();
          return null;
        });

    assertThat(run).hasValue(current);
    assertThat(saved).hasValue(current);
  }

  @Test
  void operationStateDisablesMutatingActionsAndShowsProgress() throws Exception {
    WorkspaceDiscoveryPreferencesPanel panel = onEdt(() -> panel(""));

    onEdt(
        () -> {
          panel.setOperationState(true, "Running discovery…");
          return null;
        });

    assertThat(onEdt(() -> button(panel, "Run Discovery Now").isEnabled())).isFalse();
    assertThat(onEdt(() -> button(panel, "Save").isEnabled())).isFalse();
    assertThat(onEdt(() -> panel.editorForTest().isEditable())).isFalse();
    assertThat(onEdt(() -> panel.operationStatusForTest().getText()))
        .isEqualTo("Running discovery…");

    onEdt(
        () -> {
          panel.setScript("#!/usr/bin/env python3\nprint('workspace')\n");
          panel.setOperationState(false, "Loaded.");
          return null;
        });
    assertThat(onEdt(() -> panel.editorForTest().isEditable())).isTrue();
    assertThat(onEdt(() -> panel.editorForTest().getSyntaxEditingStyle()))
        .isEqualTo(SyntaxConstants.SYNTAX_STYLE_PYTHON);
  }

  private static WorkspaceDiscoveryPreferencesPanel panel(String script) {
    return new WorkspaceDiscoveryPreferencesPanel(script, ignored -> {}, ignored -> {});
  }

  private static void setScript(WorkspaceDiscoveryPreferencesPanel panel, String script)
      throws Exception {
    onEdt(
        () -> {
          panel.editorForTest().setText(script);
          return null;
        });
  }

  private static void assertLanguage(
      WorkspaceDiscoveryPreferencesPanel panel, String language, String syntax) throws Exception {
    assertThat(onEdt(() -> panel.detectedLanguageForTest().getText()))
        .isEqualTo("Detected language: " + language);
    assertThat(onEdt(() -> panel.editorForTest().getSyntaxEditingStyle())).isEqualTo(syntax);
  }

  private static JButton button(Container root, String text) {
    for (Component child : root.getComponents()) {
      if (child instanceof JButton candidate && text.equals(candidate.getText())) {
        return candidate;
      }
      if (child instanceof Container nested) {
        JButton candidate = buttonOrNull(nested, text);
        if (candidate != null) {
          return candidate;
        }
      }
    }
    throw new AssertionError("No button named " + text);
  }

  private static JButton buttonOrNull(Container root, String text) {
    for (Component child : root.getComponents()) {
      if (child instanceof JButton candidate && text.equals(candidate.getText())) {
        return candidate;
      }
      if (child instanceof Container nested) {
        JButton candidate = buttonOrNull(nested, text);
        if (candidate != null) {
          return candidate;
        }
      }
    }
    return null;
  }

  private static <T> T onEdt(Callable<T> work) throws Exception {
    if (SwingUtilities.isEventDispatchThread()) {
      return work.call();
    }
    AtomicReference<T> value = new AtomicReference<>();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    SwingUtilities.invokeAndWait(
        () -> {
          try {
            value.set(work.call());
          } catch (Throwable caught) {
            failure.set(caught);
          }
        });
    if (failure.get() != null) {
      throw new AssertionError(failure.get());
    }
    return value.get();
  }
}
