package com.holtherndon.bazelviz.ui.tests;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.GraphicsEnvironment;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Tests tab's slice of the shared column machinery: no header sorts —
 * the worst-first order is documented product intent ({@link TestRowSource})
 * — and the headers say so instead of silently not sorting.
 */
final class TestsViewColumnStateTest {

    @BeforeAll
    static void requireHeadless() {
        assertThat(GraphicsEnvironment.isHeadless())
                .as("these tests must not depend on a display")
                .isTrue();
    }

    @Test
    @DisplayName("no header sorts, and the reason offered is the worst-first design")
    void headersExplainTheFixedOrder() throws Exception {
        AtomicReference<TestsView> held = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> held.set(new TestsView()));
        TestsView view = held.get();

        SwingUtilities.invokeAndWait(() -> {
            for (String column : new String[] {"Test", "Status", "Elapsed"}) {
                assertThat(view.headerInteractionsForTest().isSortableForTest(column))
                        .as("%s must not offer a sort that would undo failures-first", column)
                        .isFalse();
            }
            assertThat(view.headerInteractionsForTest().explanationForTest("Status"))
                    .contains("worst-first")
                    .contains("failures");
        });
    }
}
