package com.holtherndon.bazelviz.ui.preferences;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.ui.theme.AppTheme;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JButton;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class PreferencesPanelTest {

  @BeforeAll
  static void requireHeadless() {
    assertThat(GraphicsEnvironment.isHeadless()).isTrue();
  }

  @Test
  void presentsThemeAndDiscoveryAsOrderedPreferenceTabs() throws Exception {
    AtomicInteger closed = new AtomicInteger();
    ThemePreferencesPanel theme =
        onEdt(() -> new ThemePreferencesPanel(AppTheme.DARK, ignored -> true));
    WorkspaceDiscoveryPreferencesPanel discovery =
        onEdt(() -> new WorkspaceDiscoveryPreferencesPanel("", ignored -> {}, ignored -> {}));
    PreferencesPanel panel =
        onEdt(() -> new PreferencesPanel(theme, discovery, closed::incrementAndGet));

    assertThat(onEdt(() -> panel.tabsForTest().getTabCount())).isEqualTo(2);
    assertThat(onEdt(() -> panel.tabsForTest().getTitleAt(0))).isEqualTo("Theme");
    assertThat(onEdt(() -> panel.tabsForTest().getTitleAt(1))).isEqualTo("Discovery");
    assertThat(onEdt(() -> panel.tabsForTest().getSelectedIndex())).isZero();
    assertThat(onEdt(() -> panel.tabsForTest().getComponentAt(0))).isSameAs(theme);
    assertThat(onEdt(() -> panel.tabsForTest().getComponentAt(1))).isSameAs(discovery);
    assertThat(onEdt(() -> panel.tabsForTest().getAccessibleContext().getAccessibleName()))
        .isEqualTo("Preference categories");

    onEdt(
        () -> {
          button(panel, "Close").doClick();
          return null;
        });
    assertThat(closed).hasValue(1);
  }

  private static JButton button(Container root, String text) {
    for (Component child : root.getComponents()) {
      if (child instanceof JButton candidate && text.equals(candidate.getText())) {
        return candidate;
      }
      if (child instanceof Container nested) {
        JButton candidate = buttonOrNull(nested, text);
        if (candidate != null) {
          return candidate;
        }
      }
    }
    throw new AssertionError("No button named " + text);
  }

  private static JButton buttonOrNull(Container root, String text) {
    for (Component child : root.getComponents()) {
      if (child instanceof JButton candidate && text.equals(candidate.getText())) {
        return candidate;
      }
      if (child instanceof Container nested) {
        JButton candidate = buttonOrNull(nested, text);
        if (candidate != null) {
          return candidate;
        }
      }
    }
    return null;
  }

  private static <T> T onEdt(Callable<T> work) throws Exception {
    if (SwingUtilities.isEventDispatchThread()) {
      return work.call();
    }
    AtomicReference<T> value = new AtomicReference<>();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    SwingUtilities.invokeAndWait(
        () -> {
          try {
            value.set(work.call());
          } catch (Throwable caught) {
            failure.set(caught);
          }
        });
    if (failure.get() != null) {
      throw new AssertionError(failure.get());
    }
    return value.get();
  }
}
