package com.holtherndon.bazelviz.ui.preferences;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.ui.theme.AppTheme;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JRadioButton;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class ThemePreferencesPanelTest {

  @BeforeAll
  static void requireHeadless() {
    assertThat(GraphicsEnvironment.isHeadless()).isTrue();
  }

  @Test
  void presentsEveryThemeAsOneAccessibleExclusiveChoice() throws Exception {
    ThemePreferencesPanel panel = onEdt(() -> panel(AppTheme.DARK));
    List<JRadioButton> choices = descendants(panel, JRadioButton.class);

    assertThat(panel.getComponent(0).getAccessibleContext().getAccessibleName()).isEqualTo("Theme");
    assertThat(choices).hasSize(AppTheme.values().length);
    assertThat(choices.stream().filter(JRadioButton::isSelected))
        .singleElement()
        .extracting(JRadioButton::getActionCommand)
        .isEqualTo(AppTheme.DARK.id());

    for (int index = 0; index < choices.size(); index++) {
      JRadioButton choice = choices.get(index);
      AppTheme theme = AppTheme.values()[index];
      assertThat(choice.getText()).isEqualTo(theme.displayName());
      assertThat(choice.getActionCommand()).isEqualTo(theme.id());
      assertThat(choice.getToolTipText()).isEqualTo(theme.description());
      assertThat(choice.getAccessibleContext().getAccessibleName()).isNotBlank();
      assertThat(choice.getAccessibleContext().getAccessibleDescription())
          .isEqualTo(theme.description());
    }

    List<JTextArea> explanatoryText = descendants(panel, JTextArea.class);
    assertThat(explanatoryText).hasSize(AppTheme.values().length + 1);
    assertThat(explanatoryText)
        .allSatisfy(
            text -> {
              assertThat(text.isEditable()).isFalse();
              assertThat(text.isFocusable()).isTrue();
            })
        .anySatisfy(
            note ->
                assertThat(note.getText())
                    .contains("apply immediately")
                    .contains("save automatically"));
  }

  @Test
  void acceptedSelectionInvokesTheCallbackAndBecomesActive() throws Exception {
    AtomicReference<AppTheme> selected = new AtomicReference<>();
    ThemePreferencesPanel panel =
        onEdt(
            () ->
                new ThemePreferencesPanel(
                    AppTheme.LIGHT,
                    theme -> {
                      selected.set(theme);
                      return true;
                    }));
    List<JRadioButton> choices = descendants(panel, JRadioButton.class);

    onEdt(
        () -> {
          choices.get(AppTheme.MACOS_DARK.ordinal()).doClick();
          return null;
        });

    assertThat(selected).hasValue(AppTheme.MACOS_DARK);
    assertThat(onEdt(() -> choices.get(AppTheme.MACOS_DARK.ordinal()).isSelected())).isTrue();
    assertThat(onEdt(() -> choices.get(AppTheme.LIGHT.ordinal()).isSelected())).isFalse();
  }

  @Test
  void rejectedSelectionRestoresTheLastSuccessfulChoice() throws Exception {
    List<AppTheme> attempted = new ArrayList<>();
    ThemePreferencesPanel panel =
        onEdt(
            () ->
                new ThemePreferencesPanel(
                    AppTheme.DARK,
                    theme -> {
                      attempted.add(theme);
                      return theme == AppTheme.INTELLIJ_LIGHT;
                    }));
    List<JRadioButton> choices = descendants(panel, JRadioButton.class);

    onEdt(
        () -> {
          choices.get(AppTheme.INTELLIJ_LIGHT.ordinal()).doClick();
          choices.get(AppTheme.MACOS_LIGHT.ordinal()).doClick();
          return null;
        });

    assertThat(attempted).containsExactly(AppTheme.INTELLIJ_LIGHT, AppTheme.MACOS_LIGHT);
    assertThat(onEdt(() -> choices.stream().filter(JRadioButton::isSelected).toList()))
        .singleElement()
        .extracting(JRadioButton::getActionCommand)
        .isEqualTo(AppTheme.INTELLIJ_LIGHT.id());
  }

  private static ThemePreferencesPanel panel(AppTheme active) {
    return new ThemePreferencesPanel(active, ignored -> true);
  }

  private static <T extends Component> List<T> descendants(Container root, Class<T> componentType) {
    List<T> matches = new ArrayList<>();
    for (Component child : root.getComponents()) {
      if (componentType.isInstance(child)) {
        matches.add(componentType.cast(child));
      }
      if (child instanceof Container nested) {
        matches.addAll(descendants(nested, componentType));
      }
    }
    return matches;
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
