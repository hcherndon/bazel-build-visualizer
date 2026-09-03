package com.holtherndon.bazelviz.ui.query;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.storage.query.TempViewDefinition;
import com.holtherndon.bazelviz.storage.query.SchemaTable;
import com.holtherndon.bazelviz.ui.theme.AppTheme;
import com.holtherndon.bazelviz.ui.theme.Themes;
import java.awt.GraphicsEnvironment;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Query tab's slice of the shared column machinery: result headers never
 * sort — the order belongs to the user's SQL — and they say so.
 */
final class QueryTabColumnStateTest {

    @BeforeAll
    static void requireHeadless() {
        assertThat(GraphicsEnvironment.isHeadless())
                .as("these tests must not depend on a display")
                .isTrue();
    }

    private static final QueryTab.Host NO_HOST = new QueryTab.Host() {
        @Override
        public List<TempViewDefinition> savedViewDefinitions() {
            return List.of();
        }

        @Override
        public void schemaUpdated(QueryTab tab, List<SchemaTable> tables) {
            // Nothing to update in this test.
        }
    };

    @Test
    @DisplayName("result headers never sort, and the reason offered is the SQL's ownership of order")
    void headersPointAtOrderBy() throws Exception {
        AtomicReference<QueryTab> held = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> held.set(new QueryTab(NO_HOST, "SELECT 1")));
        QueryTab tab = held.get();

        SwingUtilities.invokeAndWait(() -> {
            assertThat(tab.headerInteractionsForTest().isSortableForTest("anything"))
                    .isFalse();
            assertThat(tab.headerInteractionsForTest().explanationForTest("anything"))
                    .contains("ORDER BY")
                    .contains("belongs to the query");
        });
    }

    @Test
    @DisplayName("a query editor created under a dark theme is readable immediately")
    void queryEditorStartsWithTheActiveTheme() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                Themes.install(AppTheme.DARK);
                QueryTab tab = new QueryTab(NO_HOST, "SELECT 'value'");

                assertThat(tab.editorForTest().getBackground())
                        .isEqualTo(UIManager.getColor("TextArea.background"));
                assertThat(tab.editorScrollForTest().getGutter().getBackground())
                        .isEqualTo(UIManager.getColor("TextArea.background"));
                assertThat(tab.editorScrollForTest().getLineNumbersEnabled()).isTrue();
            } finally {
                Themes.installDefault();
            }
        });
    }
}
