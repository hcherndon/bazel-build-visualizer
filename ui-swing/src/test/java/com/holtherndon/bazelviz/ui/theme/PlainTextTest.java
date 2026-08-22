package com.holtherndon.bazelviz.ui.theme;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.ui.inspect.Inspection;
import com.holtherndon.bazelviz.ui.inspect.InspectorPanel;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JTable;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.plaf.basic.BasicHTML;
import javax.swing.table.DefaultTableModel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Build text is shown as text.
 *
 * <p>Swing turns any string beginning with {@code <html>} into a live document,
 * and that document's {@code <img src="http://…">} is fetched when painted. The
 * strings this application displays all came from a build — a label, a compiler
 * message, a tag someone wrote in a BUILD file, or an imported session that is
 * untrusted outright — so without this the application makes an outbound
 * request on somebody else's say-so, which plan 22.1 says it never does.
 */
class PlainTextTest {

    /** What a hostile or merely unlucky build event might carry. */
    private static final String HOSTILE =
            "<html><img src=\"http://example.invalid/pixel.png\">gone";

    @BeforeAll
    static void requireHeadless() {
        assertThat(GraphicsEnvironment.isHeadless())
                .as("these tests must not depend on a display")
                .isTrue();
    }

    @Test
    @DisplayName("Swing really does build an HTML view for this string")
    void theHazardIsReal() {
        // The premise, asserted rather than assumed. If a future Swing stops
        // doing this, the guard below becomes unnecessary and this test says so.
        assertThat(BasicHTML.isHTMLString(HOSTILE)).isTrue();

        JLabel unguarded = new JLabel(HOSTILE);
        BasicHTML.updateRenderer(unguarded, HOSTILE);
        assertThat(unguarded.getClientProperty(BasicHTML.propertyKey))
                .as("an unguarded label renders build text as markup")
                .isNotNull();
    }

    @Test
    @DisplayName("a guarded component renders the string as text")
    void guardedComponentsShowText() {
        JLabel guarded = PlainText.disableHtml(new JLabel(HOSTILE));
        BasicHTML.updateRenderer(guarded, HOSTILE);

        assertThat(guarded.getClientProperty(BasicHTML.propertyKey)).isNull();
    }

    @Test
    @DisplayName("a table's cells are text, however the value renders")
    void tableCellsShowText() throws Exception {
        JTable table = onEdt(() -> {
            JTable created = new JTable(new DefaultTableModel(
                    new Object[][] {{HOSTILE}}, new Object[] {"Target"}));
            PlainText.install(created);
            return created;
        });

        Component cell = onEdt(() -> table.prepareRenderer(table.getCellRenderer(0, 0), 0, 0));
        assertThat(cell).isInstanceOf(JComponent.class);
        JComponent rendered = (JComponent) cell;
        BasicHTML.updateRenderer(rendered, HOSTILE);
        assertThat(rendered.getClientProperty(BasicHTML.propertyKey)).isNull();
    }

    @Test
    @DisplayName("a tree's nodes are text")
    void treeNodesShowText() throws Exception {
        JTree tree = onEdt(() -> {
            JTree created = new JTree(new Object[] {HOSTILE});
            PlainText.install(created);
            return created;
        });

        Component node = onEdt(() -> tree.getCellRenderer().getTreeCellRendererComponent(
                tree, HOSTILE, false, false, true, 0, false));
        JComponent rendered = (JComponent) node;
        BasicHTML.updateRenderer(rendered, HOSTILE);
        assertThat(rendered.getClientProperty(BasicHTML.propertyKey)).isNull();
    }

    @Test
    @DisplayName("a tooltip's text is made unrecognisable to the HTML parser")
    void tooltipsAreNeutralised() {
        // The tooltip component belongs to ToolTipManager, so the client
        // property cannot be set on it; the string is changed instead, by the
        // smallest edit that stops it parsing.
        String safe = PlainText.tooltip(HOSTILE);
        assertThat(BasicHTML.isHTMLString(safe)).isFalse();
        assertThat(safe).endsWith(HOSTILE);
        assertThat(safe).hasSize(HOSTILE.length() + 1);

        // Ordinary text is untouched, so nothing gains a stray space.
        assertThat(PlainText.tooltip("//pkg:target")).isEqualTo("//pkg:target");
        assertThat(PlainText.tooltip(null)).isNull();
        assertThat(PlainText.tooltip("")).isEmpty();
    }

    @Test
    @DisplayName("the shared inspector shows a hostile value as text, in every label")
    void theInspectorShowsTextEverywhere() throws Exception {
        InspectorPanel panel = onEdt(InspectorPanel::new);
        onEdt(() -> {
            panel.show(new Inspection.Builder(HOSTILE)
                    .subtitle(HOSTILE)
                    .section("Failure")
                    .field(HOSTILE, HOSTILE)
                    .field(Inspection.Field.unknown(HOSTILE, HOSTILE))
                    .build());
            return null;
        });

        List<JLabel> labels = new ArrayList<>();
        collect(panel, labels);
        assertThat(labels).isNotEmpty();
        for (JLabel label : labels) {
            BasicHTML.updateRenderer(label, label.getText());
            assertThat(label.getClientProperty(BasicHTML.propertyKey))
                    .as("label %s", label.getText())
                    .isNull();
        }
    }

    private static void collect(Container container, List<JLabel> into) {
        for (Component child : container.getComponents()) {
            if (child instanceof JLabel label) {
                into.add(label);
            }
            if (child instanceof Container nested) {
                collect(nested, into);
            }
        }
    }

    private static <T> T onEdt(Callable<T> work) throws Exception {
        AtomicReference<T> value = new AtomicReference<>();
        AtomicReference<Exception> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                value.set(work.call());
            } catch (Exception e) {
                failure.set(e);
            }
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return value.get();
    }
}
