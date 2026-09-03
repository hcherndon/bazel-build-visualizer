package com.holtherndon.bazelviz.ui.theme;

import com.formdev.flatlaf.FlatLaf;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Window;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.Style;
import org.fife.ui.rsyntaxtextarea.SyntaxScheme;
import org.fife.ui.rsyntaxtextarea.TokenTypes;
import org.fife.ui.rtextarea.RTextScrollPane;

/** Applies the active Swing palette to syntax-highlighted text surfaces. */
public final class SyntaxTextTheme {

  private SyntaxTextTheme() {}

  public static void apply(RSyntaxTextArea area, RTextScrollPane scroll) {
    // Applying dark colours mutates the syntax scheme. Always restore the
    // library's baseline first so dark -> light is a real round trip.
    area.restoreDefaultSyntaxScheme();
    Color background = uiColor("TextArea.background", area.getBackground());
    Color foreground = uiColor("TextArea.foreground", area.getForeground());
    Color disabled = uiColor("Label.disabledForeground", foreground);
    area.setBackground(background);
    area.setForeground(foreground);
    area.setCaretColor(foreground);
    area.setSelectionColor(uiColor("TextArea.selectionBackground", area.getSelectionColor()));
    area.setSelectedTextColor(uiColor("TextArea.selectionForeground", area.getSelectedTextColor()));

    var gutter = scroll.getGutter();
    gutter.setBackground(background);
    gutter.setLineNumberColor(disabled);
    gutter.setCurrentLineNumberColor(foreground);
    gutter.setBorderColor(uiColor("Component.borderColor", disabled));

    if (!FlatLaf.isLafDark()) {
      area.setMatchedBracketBGColor(RSyntaxTextArea.getDefaultBracketMatchBGColor());
      area.setMatchedBracketBorderColor(RSyntaxTextArea.getDefaultBracketMatchBorderColor());
      return;
    }
    SyntaxScheme scheme = area.getSyntaxScheme();
    for (int type = 0; type < scheme.getStyleCount(); type++) {
      Style existing = scheme.getStyle(type);
      if (existing != null) {
        Style styled = (Style) existing.clone();
        styled.foreground = foreground;
        styled.background = null;
        scheme.setStyle(type, styled);
      }
    }
    syntaxStyle(scheme, TokenTypes.RESERVED_WORD, new Color(0xA7D46F));
    syntaxStyle(scheme, TokenTypes.RESERVED_WORD_2, new Color(0xA7D46F));
    syntaxStyle(scheme, TokenTypes.DATA_TYPE, new Color(0x9EC9F5));
    syntaxStyle(scheme, TokenTypes.LITERAL_BOOLEAN, new Color(0xA7D46F));
    syntaxStyle(scheme, TokenTypes.LITERAL_NUMBER_DECIMAL_INT, new Color(0xF4D35E));
    syntaxStyle(scheme, TokenTypes.LITERAL_NUMBER_FLOAT, new Color(0xF4D35E));
    syntaxStyle(scheme, TokenTypes.LITERAL_NUMBER_HEXADECIMAL, new Color(0xF4D35E));
    syntaxStyle(scheme, TokenTypes.LITERAL_STRING_DOUBLE_QUOTE, new Color(0xFFA75A));
    syntaxStyle(scheme, TokenTypes.LITERAL_CHAR, new Color(0xFFA75A));
    syntaxStyle(scheme, TokenTypes.LITERAL_BACKQUOTE, new Color(0xFFA75A));
    syntaxStyle(scheme, TokenTypes.COMMENT_EOL, new Color(0xB5BDC5));
    syntaxStyle(scheme, TokenTypes.COMMENT_MULTILINE, new Color(0xB5BDC5));
    syntaxStyle(scheme, TokenTypes.COMMENT_DOCUMENTATION, new Color(0xB5BDC5));
    syntaxStyle(scheme, TokenTypes.ANNOTATION, new Color(0xD3B5F0));
    syntaxStyle(scheme, TokenTypes.PREPROCESSOR, new Color(0xD3B5F0));
    syntaxStyle(scheme, TokenTypes.VARIABLE, new Color(0xD3B5F0));
    syntaxStyle(scheme, TokenTypes.OPERATOR, new Color(0xE8E2B7));
    syntaxStyle(scheme, TokenTypes.SEPARATOR, new Color(0xE8E2B7));
    area.setSyntaxScheme(scheme);
    area.setMatchedBracketBGColor(new Color(0x607078));
    area.setMatchedBracketBorderColor(new Color(0xB5BDC5));
  }

  /** Re-applies custom syntax palettes in every open application window. EDT only. */
  static void refreshOpenWindows() {
    if (!SwingUtilities.isEventDispatchThread()) {
      throw new IllegalStateException("syntax theme changes must run on the EDT");
    }
    for (Window window : Window.getWindows()) {
      refresh(window);
    }
  }

  /** Walks one component tree; package-visible for headless theme tests. */
  static void refresh(Component component) {
    if (component instanceof RTextScrollPane scroll
        && scroll.getTextArea() instanceof RSyntaxTextArea area) {
      apply(area, scroll);
    }
    if (component instanceof Container container) {
      for (Component child : container.getComponents()) {
        refresh(child);
      }
    }
  }

  private static void syntaxStyle(SyntaxScheme scheme, int tokenType, Color foreground) {
    Style existing = scheme.getStyle(tokenType);
    Style styled = existing == null ? new Style() : (Style) existing.clone();
    styled.foreground = foreground;
    styled.background = null;
    scheme.setStyle(tokenType, styled);
  }

  private static Color uiColor(String key, Color fallback) {
    Color colour = UIManager.getColor(key);
    return colour == null ? fallback : colour;
  }
}
