package com.holtherndon.bazelviz.ui.capture;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** What Bazel's console actually emits, and what the console pane should show. */
class ConsoleModelTest {

    private static final String ESC = String.valueOf((char) 0x1B);

    @Test
    @DisplayName("a carriage return replaces the line being built, as a progress repaint does")
    void carriageReturnReplacesTheLine() {
        ConsoleModel model = new ConsoleModel();

        model.append("Analyzing: 1 target\rAnalyzing: 2 targets\rAnalyzing: 3 targets\n");

        // One line, the last one — not three overlapping ones.
        assertThat(model.lines()).containsExactly("Analyzing: 3 targets");
    }

    @Test
    @DisplayName("ANSI escape sequences are removed for display")
    void ansiSequencesAreStripped() {
        ConsoleModel model = new ConsoleModel();

        model.append(ESC + "[32mINFO:" + ESC + "[0m Build completed successfully\n");

        assertThat(model.lines()).containsExactly("INFO: Build completed successfully");
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

        assertThat(model.lines()).containsExactly(
                "INFO: Analyzed 2 targets.",
                "INFO: Build completed successfully, 3 total actions");
    }
}
