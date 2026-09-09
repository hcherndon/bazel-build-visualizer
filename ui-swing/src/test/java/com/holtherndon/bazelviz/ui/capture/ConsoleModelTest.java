package com.holtherndon.bazelviz.ui.capture;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** What Bazel's console actually emits, and what the console pane should show. */
class ConsoleModelTest {

  private static final String ESC = String.valueOf((char) 0x1B);

  @Test
  void crlfPreservesTextAndStylesAtEveryChunkBoundary() {
    String output = ESC + "[32mINFO:" + ESC + "[0m Build completed successfully\r\n";
    byte[] bytes = output.getBytes(StandardCharsets.UTF_8);
    for (int split = 0; split <= bytes.length; split++) {
      ConsoleModel model = new ConsoleModel();
      model.append(bytes, 0, split);
      model.append(bytes, split, bytes.length - split);
      assertThat(model.lines()).containsExactly("INFO: Build completed successfully");
      assertThat(model.styledLines().getFirst().runs().getFirst().style().foregroundRgb())
          .isEqualTo(0x00AA00);
    }
  }

  @Test
  void remoteBazelCrLfProgressRedrawKeepsFinalSummary() {
    ConsoleModel model = new ConsoleModel();
    String output =
        "[94 / 100] 2 actions running\r\n    deps; 0s\r\n    logging; 0s\r\n"
            + clearPreviousLines(3)
            + ESC
            + "[32m[98 / 100]"
            + ESC
            + "[0m logging; 0s\r\n"
            + clearPreviousLines(1)
            + ESC
            + "[32mINFO: "
            + ESC
            + "[0mFound 374 targets...\r\n"
            + "[100 / 100] no actions running\r\n"
            + clearPreviousLines(1)
            + ESC
            + "[32mINFO: "
            + ESC
            + "[0mBuild completed successfully, 31 total actions\r\n"
            + ESC
            + "[32mINFO:"
            + ESC
            + "[0m \r\n"
            + clearPreviousLines(1)
            + ESC
            + "[0m"
            + "Shared connection to host closed.\r\n";
    byte[] bytes = output.getBytes(StandardCharsets.UTF_8);
    for (int index = 0; index < bytes.length; index++) {
      model.append(bytes, index, 1);
    }
    assertThat(model.lines())
        .containsExactly(
            "INFO: Found 374 targets...",
            "INFO: Build completed successfully, 31 total actions",
            "Shared connection to host closed.");
  }

  @Test
  void pendingCarriageReturnPreservesTextUntilReplacementOrErase() {
    ConsoleModel model = new ConsoleModel();
    model.append("progress\r");
    assertThat(model.partialLine()).isEqualTo("progress");
    model.append(ESC + "[0m\n");
    assertThat(model.lines()).containsExactly("progress");
    model.append("old\r" + ESC + "[K");
    assertThat(model.partialLine()).isEmpty();
    model.append("replacement\rnew\n");
    assertThat(model.lines()).containsExactly("progress", "new");
    model.append("discarded\r");
    model.clear();
    model.append("fresh\r\n");
    assertThat(model.lines()).containsExactly("fresh");
  }

  @Test
  @DisplayName("a carriage return replaces the line being built, as a progress repaint does")
  void carriageReturnReplacesTheLine() {
    ConsoleModel model = new ConsoleModel();

    model.append("Analyzing: 1 target\rAnalyzing: 2 targets\rAnalyzing: 3 targets\n");

    // One line, the last one — not three overlapping ones.
    assertThat(model.lines()).containsExactly("Analyzing: 3 targets");
  }

  @Test
  @DisplayName("ANSI colours are retained while escape bytes stay out of plain text")
  void ansiColoursAreRetained() {
    ConsoleModel model = new ConsoleModel();

    model.append(ESC + "[32mINFO:" + ESC + "[0m Build completed successfully\n");

    assertThat(model.lines()).containsExactly("INFO: Build completed successfully");
    assertThat(model.styledLines().getFirst().runs()).hasSize(2);
    assertThat(model.styledLines().getFirst().runs().getFirst().text()).isEqualTo("INFO:");
    assertThat(model.styledLines().getFirst().runs().getFirst().style().foregroundRgb())
        .isEqualTo(0x00AA00);
    assertThat(model.styledLines().getFirst().runs().getLast().style().foregroundRgb()).isNull();
  }

  @Test
  @DisplayName("an escape sequence split across two chunks is still removed")
  void escapeSequencesSurviveChunkBoundaries() {
    ConsoleModel model = new ConsoleModel();

    // The pump delivers whatever the pipe gives it, which is not aligned to
    // anything. A state machine that reset per chunk would leak "[32m".
    model.append(ESC + "[3");
    model.append("2mgreen" + ESC + "[0m\n");

    assertThat(model.lines()).containsExactly("green");
    assertThat(model.styledLines().getFirst().runs().getFirst().style().foregroundRgb())
        .isEqualTo(0x00AA00);
  }

  @Test
  @DisplayName("SGR reset restores every attribute without changing earlier text")
  void resetRestoresDefaultAttributes() {
    ConsoleModel model = new ConsoleModel();

    model.append(ESC + "[1;3;4;91mimportant" + ESC + "[0m ordinary\n");

    ConsoleModel.StyledLine line = model.styledLines().getFirst();
    assertThat(line.runs()).hasSize(2);
    assertThat(line.runs().getFirst().style().bold()).isTrue();
    assertThat(line.runs().getFirst().style().italic()).isTrue();
    assertThat(line.runs().getFirst().style().underline()).isTrue();
    assertThat(line.runs().getFirst().style().foregroundRgb()).isEqualTo(0xFF5555);
    assertThat(line.runs().getLast().style().bold()).isFalse();
    assertThat(line.runs().getLast().style().foregroundRgb()).isNull();
  }

  @Test
  @DisplayName("256-colour and true-colour sequences retain their exact display colours")
  void extendedColoursAreRetained() {
    ConsoleModel model = new ConsoleModel();

    model.append(ESC + "[38;5;202mindexed" + ESC + "[38;2;12;34;56mtrue\n");

    ConsoleModel.StyledLine line = model.styledLines().getFirst();
    assertThat(line.runs()).hasSize(2);
    assertThat(line.runs().getFirst().style().foregroundRgb()).isEqualTo(0xFF5F00);
    assertThat(line.runs().getLast().style().foregroundRgb()).isEqualTo(0x0C2238);
  }

  @Test
  @DisplayName("OSC window titles are consumed even when their terminator crosses chunks")
  void terminalControlStringsAreNotShown() {
    ConsoleModel model = new ConsoleModel();

    model.append("before" + ESC + "]0;secret title" + ESC);
    model.append("\\after\n");

    assertThat(model.lines()).containsExactly("beforeafter");
  }

  @Test
  @DisplayName("an overlong CSI sequence is bounded and ignored through its terminator")
  void overlongCsiIsIgnored() {
    ConsoleModel model = new ConsoleModel();

    model.append(ESC + "[" + "1;".repeat(ConsoleModel.MAX_ANSI_SEQUENCE_CHARACTERS) + "31mplain\n");

    assertThat(model.lines()).containsExactly("plain");
    assertThat(model.styledLines().getFirst().runs().getFirst().style().foregroundRgb()).isNull();
    assertThat(model.styledLines().getFirst().runs().getFirst().style().bold()).isFalse();
  }

  @Test
  @DisplayName("Bazel cursor-up redraw replaces its multi-line progress block")
  void cursorUpReplacesBazelProgressBlock() {
    ConsoleModel model = new ConsoleModel();
    String first = progressBlock("1,124", "old action");
    String second = progressBlock("1,129", "new action");

    model.append(first);
    ConsoleModel.Delta initial = model.since(0);
    assertThat(initial.lines()).hasSize(9);

    model.append(clearPreviousLines(9) + second);

    assertThat(model.lines()).hasSize(9);
    assertThat(model.text()).contains("[1,129 / 1,776]").contains("new action");
    assertThat(model.text()).doesNotContain("[1,124 / 1,776]").doesNotContain("old action");
    ConsoleModel.Delta replacement = model.since(initial.total());
    assertThat(replacement.replaceFrom()).isZero();
    assertThat(replacement.lines()).hasSize(9);
  }

  @Test
  @DisplayName("the retained-line cap is bounded and says how much it dropped")
  void theCapIsDisclosed() {
    ConsoleModel model = new ConsoleModel(3);

    for (int i = 1; i <= 10; i++) {
      model.append("line " + i + "\n");
    }

    assertThat(model.lines()).containsExactly("line 8", "line 9", "line 10");
    // Plan section 3: a display limit that fired is stated, never silent.
    assertThat(model.droppedLines()).isEqualTo(7);
  }

  @Test
  @DisplayName("a line with no newline yet is shown as it is being written")
  void partialLinesAreVisible() {
    ConsoleModel model = new ConsoleModel();

    model.append("Building //foo:bar");

    assertThat(model.lines()).isEmpty();
    assertThat(model.partialLine()).isEqualTo("Building //foo:bar");
    assertThat(model.text()).isEqualTo("Building //foo:bar");
  }

  @Test
  @DisplayName("bytes are decoded as UTF-8")
  void utf8IsDecoded() {
    ConsoleModel model = new ConsoleModel();
    byte[] bytes = "naïve ✓\n".getBytes(StandardCharsets.UTF_8);

    model.append(bytes, 0, bytes.length);

    assertThat(model.lines()).containsExactly("naïve ✓");
  }

  @Test
  @DisplayName("a real Bazel progress block collapses to its final state")
  void realProgressBlock() {
    ConsoleModel model = new ConsoleModel();

    model.append("Loading: \r");
    model.append("Loading: 0 packages loaded\r");
    model.append("Analyzing: 2 targets (5 packages loaded)\r");
    model.append("INFO: Analyzed 2 targets.\n");
    model.append("INFO: Build completed successfully, 3 total actions\n");

    assertThat(model.lines())
        .containsExactly(
            "INFO: Analyzed 2 targets.", "INFO: Build completed successfully, 3 total actions");
  }

  private static String progressBlock(String completed, String action) {
    StringBuilder block =
        new StringBuilder("[")
            .append(completed)
            .append(" / 1,776] 1 / 35 tests; 8 actions, 7 running\n");
    for (int index = 0; index < 7; index++) {
      block.append("    queued action ").append(index).append('\n');
    }
    return block.append("    ").append(action).append('\n').toString();
  }

  /** Bazel emits one CR / cursor-up / erase-line triplet for each old progress row. */
  private static String clearPreviousLines(int count) {
    return ("\r" + ESC + "[1A" + ESC + "[K").repeat(count);
  }
}
