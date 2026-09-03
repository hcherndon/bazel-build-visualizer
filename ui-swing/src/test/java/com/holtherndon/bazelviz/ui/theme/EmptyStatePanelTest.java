package com.holtherndon.bazelviz.ui.theme;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JPanel;
import javax.swing.JTextPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.text.Element;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledEditorKit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The reusable full-pane message shared by empty navigation cards. */
final class EmptyStatePanelTest {

  @BeforeAll
  static void requireHeadless() {
    assertThat(GraphicsEnvironment.isHeadless()).isTrue();
  }

  @Test
  @DisplayName("the pane fills its card, wraps narrowly, and centres the message both ways")
  void geometryRespondsToWidth() throws Exception {
    String text =
        "No configured targets are available because this session did not publish "
            + "a completed configured-target query. Top Level Targets remains available.";
    EmptyStatePanel panel = onEdt(() -> new EmptyStatePanel(text));
    JPanel host =
        onEdt(
            () -> {
              JPanel card = new JPanel(new BorderLayout());
              card.add(panel, BorderLayout.CENTER);
              return card;
            });

    onEdt(
        () -> {
          layout(host, panel, 900, 420);
          JTextPane message = messageOf(panel);
          Rectangle wide = new Rectangle(message.getBounds());
          assertThat(panel.getBounds()).isEqualTo(new Rectangle(0, 0, 900, 420));
          assertVerticallyCentred(wide, panel.getHeight());
          assertParagraphsCentred(message);

          layout(host, panel, 190, 420);
          Rectangle narrow = new Rectangle(message.getBounds());
          assertThat(panel.getBounds()).isEqualTo(new Rectangle(0, 0, 190, 420));
          assertThat(narrow.height).isGreaterThan(wide.height);
          assertThat(narrow.x).isGreaterThan(0);
          assertThat(narrow.x + narrow.width).isLessThan(panel.getWidth());
          assertVerticallyCentred(narrow, panel.getHeight());
          assertParagraphsCentred(message);
          return null;
        });
  }

  @Test
  @DisplayName("the read-only message is selectable without disturbing the user's selection")
  void messageIsSelectableAndPreservesSelection() throws Exception {
    EmptyStatePanel panel = onEdt(() -> new EmptyStatePanel("No session is open."));

    onEdt(
        () -> {
          JTextPane message = messageOf(panel);
          message.select(3, 10);
          int dot = message.getCaret().getDot();
          int mark = message.getCaret().getMark();

          assertThat(panel.isTextSelectable()).isTrue();
          assertThat(message.isEditable()).isFalse();
          assertThat(message.isFocusable()).isTrue();
          assertThat(message.getCaret().getDot()).isEqualTo(dot);
          assertThat(message.getCaret().getMark()).isEqualTo(mark);

          panel.setText("");
          assertThat(panel.isTextSelectable()).isTrue();
          return null;
        });
  }

  @Test
  @DisplayName("text updates are exact and recompute the centred wrapped block")
  void textUpdatesReflow() throws Exception {
    EmptyStatePanel panel = onEdt(() -> new EmptyStatePanel("Waiting."));

    onEdt(
        () -> {
          panel.setSize(210, 360);
          panel.doLayout();
          int shortHeight = messageOf(panel).getHeight();

          String replacement =
              "Could not read configurations: the remote operation returned "
                  + "a detailed failure that must remain visible and copyable.";
          panel.setText(replacement);
          panel.doLayout();

          Rectangle updated = messageOf(panel).getBounds();
          assertThat(panel.getText()).isEqualTo(replacement);
          assertThat(updated.height).isGreaterThan(shortHeight);
          assertVerticallyCentred(updated, panel.getHeight());
          assertParagraphsCentred(messageOf(panel));
          return null;
        });
  }

  @Test
  @DisplayName("hostile markup remains inert plain text")
  void hostileTextIsNeverParsedAsHtml() throws Exception {
    String hostile =
        "<html><img src='https://invalid.example/pixel'>" + "<script>alert('x')</script></html>";
    EmptyStatePanel panel = onEdt(() -> new EmptyStatePanel(hostile));

    onEdt(
        () -> {
          JTextPane message = messageOf(panel);
          assertThat(panel.getText()).isEqualTo(hostile);
          assertThat(message.getContentType()).isEqualTo("text/plain");
          assertThat(message.getEditorKit()).isExactlyInstanceOf(StyledEditorKit.class);
          assertThat(message.getClientProperty("html.disable")).isEqualTo(Boolean.TRUE);
          assertThat(panel.isTextSelectable()).isTrue();
          return null;
        });
  }

  @Test
  @DisplayName("the selectable message follows the muted empty-state theme colour")
  void themeRefreshUsesLabelStyle() throws Exception {
    onEdt(
        () -> {
          Object oldFont = UIManager.get("Label.font");
          Object oldForeground = UIManager.get("Label.foreground");
          Object oldDisabledForeground = UIManager.get("Label.disabledForeground");
          try {
            Font firstFont = new Font(Font.DIALOG, Font.PLAIN, 13);
            Color firstForeground = new Color(24, 48, 72);
            Color firstDisabledForeground = new Color(72, 84, 96);
            UIManager.put("Label.font", firstFont);
            UIManager.put("Label.foreground", firstForeground);
            UIManager.put("Label.disabledForeground", firstDisabledForeground);
            EmptyStatePanel panel = new EmptyStatePanel("No session is open.");
            JTextPane message = messageOf(panel);
            assertThat(message.getFont()).isEqualTo(firstFont);
            assertThat(message.getForeground()).isEqualTo(firstDisabledForeground);

            Font secondFont = new Font(Font.MONOSPACED, Font.BOLD, 17);
            Color secondForeground = new Color(180, 150, 120);
            Color secondDisabledForeground = new Color(120, 105, 90);
            UIManager.put("Label.font", secondFont);
            UIManager.put("Label.foreground", secondForeground);
            UIManager.put("Label.disabledForeground", secondDisabledForeground);
            SwingUtilities.updateComponentTreeUI(panel);

            assertThat(message.getFont()).isEqualTo(secondFont);
            assertThat(message.getForeground()).isEqualTo(secondDisabledForeground);
            assertThat(message.getContentType()).isEqualTo("text/plain");
          } finally {
            restore("Label.font", oldFont);
            restore("Label.foreground", oldForeground);
            restore("Label.disabledForeground", oldDisabledForeground);
          }
          return null;
        });
  }

  private static JTextPane messageOf(EmptyStatePanel panel) {
    assertThat(panel.getComponentCount()).isOne();
    assertThat(panel.getComponent(0)).isInstanceOf(JTextPane.class);
    return (JTextPane) panel.getComponent(0);
  }

  private static void layout(JPanel host, EmptyStatePanel panel, int width, int height) {
    host.setSize(width, height);
    host.doLayout();
    panel.doLayout();
  }

  private static void assertVerticallyCentred(Rectangle bounds, int parentHeight) {
    assertThat(Math.abs((bounds.y * 2 + bounds.height) - parentHeight))
        .as("message centre differs from the panel centre by at most half a pixel")
        .isLessThanOrEqualTo(1);
  }

  private static void assertParagraphsCentred(JTextPane message) {
    Element root = message.getStyledDocument().getDefaultRootElement();
    for (int index = 0; index < root.getElementCount(); index++) {
      assertThat(StyleConstants.getAlignment(root.getElement(index).getAttributes()))
          .as("paragraph %s", index)
          .isEqualTo(StyleConstants.ALIGN_CENTER);
    }
  }

  private static void restore(String key, Object value) {
    if (value == null) {
      UIManager.getDefaults().remove(key);
    } else {
      UIManager.put(key, value);
    }
  }

  private static <T> T onEdt(Callable<T> work) throws Exception {
    AtomicReference<T> value = new AtomicReference<>();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    SwingUtilities.invokeAndWait(
        () -> {
          try {
            value.set(work.call());
          } catch (Throwable throwable) {
            failure.set(throwable);
          }
        });
    if (failure.get() instanceof Exception exception) {
      throw exception;
    }
    if (failure.get() instanceof Error error) {
      throw error;
    }
    return value.get();
  }
}
