package com.holtherndon.bazelviz.ui.theme;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rsyntaxtextarea.TokenTypes;
import org.fife.ui.rtextarea.RTextScrollPane;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

final class SyntaxTextThemeTest {

  @Test
  @DisplayName("an existing syntax editor makes a complete dark-to-light round trip")
  void existingEditorChangesBothWays() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          JPanel root = new JPanel();
          RSyntaxTextArea area = new RSyntaxTextArea("value = \"text\"");
          area.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_PYTHON);
          RTextScrollPane scroll = new RTextScrollPane(area);
          root.add(scroll);
          try {
            Themes.install(AppTheme.DARK);
            SyntaxTextTheme.refresh(root);
            Color darkBackground = area.getBackground();
            Color darkString =
                area.getSyntaxScheme().getStyle(TokenTypes.LITERAL_STRING_DOUBLE_QUOTE).foreground;

            Themes.install(AppTheme.LIGHT);
            SyntaxTextTheme.refresh(root);
            Color lightBackground = area.getBackground();
            Color lightString =
                area.getSyntaxScheme().getStyle(TokenTypes.LITERAL_STRING_DOUBLE_QUOTE).foreground;

            assertThat(darkBackground).isNotEqualTo(lightBackground);
            assertThat(darkString).isNotEqualTo(lightString);
            assertThat(lightBackground).isEqualTo(UIManager.getColor("TextArea.background"));
            assertThat(scroll.getGutter().getBackground()).isEqualTo(lightBackground);
            assertThat(contrast(lightString, lightBackground)).isGreaterThanOrEqualTo(4.0);
          } finally {
            Themes.installDefault();
          }
        });
  }

  private static double contrast(Color first, Color second) {
    double light = Math.max(luminance(first), luminance(second));
    double dark = Math.min(luminance(first), luminance(second));
    return (light + 0.05) / (dark + 0.05);
  }

  private static double luminance(Color color) {
    return 0.2126 * channel(color.getRed())
        + 0.7152 * channel(color.getGreen())
        + 0.0722 * channel(color.getBlue());
  }

  private static double channel(int value) {
    double normalized = value / 255.0;
    return normalized <= 0.03928 ? normalized / 12.92 : Math.pow((normalized + 0.055) / 1.055, 2.4);
  }
}
