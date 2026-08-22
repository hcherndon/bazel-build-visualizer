package com.holtherndon.bazelviz.ui.capture;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The console pane's incremental rendering.
 *
 * <p>Runs headless on the EDT. The properties being checked are about what the
 * document holds after a sequence of appends, which needs no display.
 */
class ConsoleViewTest {

    @Test
    @DisplayName("committed lines and the unfinished line both reach the document")
    void rendersLinesAndThePartialLine() throws Exception {
        ConsoleView view = onEdt(ConsoleView::new);

        onEdt(() -> {
            append(view, "first\nsecond\n");
            append(view, "third-in-progress");
            return null;
        });
        flushEdt();

        assertThat(text(view)).isEqualTo("first\nsecond\nthird-in-progress");
    }

    @Test
    @DisplayName("a progress repaint rewrites only the unfinished line")
    void progressRepaintReplacesTheTail() throws Exception {
        ConsoleView view = onEdt(ConsoleView::new);

        onEdt(() -> {
            append(view, "INFO: starting\n");
            append(view, "Analyzing: 1 target\r");
            append(view, "Analyzing: 2 targets\r");
            append(view, "Analyzing: 3 targets");
            return null;
        });
        flushEdt();

        // The committed line survives; the three progress repaints collapse to
        // the last one rather than stacking up.
        assertThat(text(view)).isEqualTo("INFO: starting\nAnalyzing: 3 targets");
    }

    @Test
    @DisplayName("appending does not rebuild what is already rendered")
    void appendingIsIncremental() throws Exception {
        ConsoleView view = onEdt(ConsoleView::new);

        onEdt(() -> {
            for (int i = 0; i < 200; i++) {
                append(view, "line " + i + "\n");
            }
            return null;
        });
        flushEdt();

        String rendered = text(view);
        assertThat(rendered).startsWith("line 0\n").contains("line 199\n");
        assertThat(rendered.lines().count()).isEqualTo(200);
    }

    @Test
    @DisplayName("the model's delta reports what a renderer has not seen")
    void deltaTracksWhatWasRendered() {
        ConsoleModel model = new ConsoleModel();
        model.append("a\nb\n");

        ConsoleModel.Delta first = model.since(0);
        assertThat(first.lines()).containsExactly("a", "b");
        assertThat(first.total()).isEqualTo(2);
        assertThat(first.resetRequired()).isFalse();

        model.append("c\n");
        ConsoleModel.Delta second = model.since(first.total());
        assertThat(second.lines()).containsExactly("c");
    }

    @Test
    @DisplayName("a renderer whose lines the cap discarded is told to start again")
    void overflowRequiresAReset() {
        ConsoleModel model = new ConsoleModel(3);
        model.append("a\nb\n");
        long seen = model.since(0).total();

        for (int i = 0; i < 10; i++) {
            model.append("x" + i + "\n");
        }

        // Everything the renderer had is gone from the model, so appending to
        // its document would splice unrelated text together.
        ConsoleModel.Delta delta = model.since(seen);
        assertThat(delta.resetRequired()).isTrue();
        assertThat(delta.lines()).hasSize(3);
    }

    private static void append(ConsoleView view, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        view.append(bytes, 0, bytes.length);
    }

    private static String text(ConsoleView view) throws Exception {
        return onEdt(() -> {
            try {
                return view.model().text();
            } catch (RuntimeException failure) {
                throw new IllegalStateException(failure);
            }
        });
    }

    private static <T> T onEdt(java.util.function.Supplier<T> work) throws Exception {
        Object[] held = new Object[1];
        SwingUtilities.invokeAndWait(() -> held[0] = work.get());
        @SuppressWarnings("unchecked")
        T result = (T) held[0];
        return result;
    }

    private static void flushEdt() throws Exception {
        // Two round trips: the view coalesces its update with invokeLater, so
        // the first flush runs the scheduled render and the second waits for
        // anything it queued.
        SwingUtilities.invokeAndWait(() -> {});
        SwingUtilities.invokeAndWait(() -> {});
    }
}
