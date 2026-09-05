package com.holtherndon.bazelviz.ui.errors;

import static org.assertj.core.api.Assertions.assertThat;

import javax.swing.SwingUtilities;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

final class ErrorConsolePanelTest {

  @Test
  @DisplayName("the Errors transcript discloses lines omitted from its bounded tail")
  void disclosesDroppedLines() throws Exception {
    StringBuilder stderr = new StringBuilder();
    for (int index = 0; index < ErrorConsolePanel.MAX_LINES + 5; index++) {
      stderr.append("line ").append(index).append('\n');
    }
    ErrorConsolePanel.Content content = ErrorConsolePanel.parse(stderr.toString(), "");
    ErrorConsolePanel[] held = new ErrorConsolePanel[1];

    SwingUtilities.invokeAndWait(
        () -> {
          held[0] = new ErrorConsolePanel();
          held[0].show(content);
        });

    assertThat(held[0].textForTest("stderr"))
        .startsWith("line 5\n")
        .endsWith("line " + (ErrorConsolePanel.MAX_LINES + 4) + "\n");
    assertThat(held[0].limitTextForTest("stderr"))
        .contains("5 earlier line(s) are not shown")
        .contains("complete in the journal");
  }
}
