package com.holtherndon.bazelviz.ui.theme;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.formdev.flatlaf.FlatLaf;
import java.util.Arrays;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

final class ThemesTest {

  @Test
  @DisplayName("all bundled themes have stable unique identities")
  void themeIdentitiesAreStableAndUnique() {
    assertThat(AppTheme.values()).hasSize(6);
    assertThat(Arrays.stream(AppTheme.values()).map(AppTheme::id))
        .doesNotHaveDuplicates()
        .containsExactly("light", "dark", "intellij-light", "darcula", "macos-light", "macos-dark");
    assertThat(Arrays.stream(AppTheme.values()).map(AppTheme::displayName))
        .doesNotHaveDuplicates()
        .allSatisfy(name -> assertThat(name).isNotBlank());
  }

  @Test
  @DisplayName("every bundled theme can be switched to in one running JVM")
  void everyThemeInstalls() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          try {
            for (AppTheme theme : AppTheme.values()) {
              Themes.install(theme);

              assertThat(Themes.current()).isEqualTo(theme);
              assertThat(UIManager.getLookAndFeel().getClass())
                  .isEqualTo(theme.createLookAndFeel().getClass());
              assertThat(FlatLaf.isLafDark()).isEqualTo(theme.isDark());
              assertThat(UIManager.getColor("Panel.background")).isNotNull();
              assertThat(UIManager.getColor("Label.foreground")).isNotNull();
            }
          } finally {
            Themes.installDefault();
          }
        });
  }

  @Test
  @DisplayName("a valid process theme overrides disk without invalid input hiding it")
  void startupResolution() {
    assertThat(Themes.startupTheme("dark", AppTheme.MACOS_LIGHT)).isEqualTo(AppTheme.DARK);
    assertThat(Themes.startupTheme("  MACOS-DARK ", AppTheme.LIGHT)).isEqualTo(AppTheme.MACOS_DARK);
    assertThat(Themes.startupTheme("", AppTheme.DARCULA)).isEqualTo(AppTheme.DARCULA);
    assertThat(Themes.startupTheme("typo", AppTheme.INTELLIJ_LIGHT))
        .isEqualTo(AppTheme.INTELLIJ_LIGHT);
  }

  @Test
  @DisplayName("look-and-feel changes are confined to the EDT")
  void installRejectsBackgroundThreads() {
    assertThatThrownBy(() -> Themes.install(AppTheme.DARK))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("EDT");
  }

  @Test
  @DisplayName("selectable label-like text follows a live look-and-feel change")
  void labelLikeTextRefreshes() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          try {
            Themes.install(AppTheme.LIGHT);
            JTextArea wrapping = WrappingLabel.create("wrapping evidence");
            JTextField selectable = SelectableLabel.create("one-line evidence");
            var lightWrapping = wrapping.getForeground();
            var lightSelectable = selectable.getForeground();

            Themes.install(AppTheme.DARK);
            wrapping.updateUI();
            selectable.updateUI();

            assertThat(wrapping.getForeground()).isNotEqualTo(lightWrapping);
            assertThat(selectable.getForeground()).isNotEqualTo(lightSelectable);
            assertThat(wrapping.getForeground()).isEqualTo(UIManager.getColor("Label.foreground"));
            assertThat(selectable.getForeground())
                .isEqualTo(UIManager.getColor("Label.foreground"));
          } finally {
            Themes.installDefault();
          }
        });
  }
}
