package com.holtherndon.bazelviz.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.ui.WindowNavigationKeys.Direction;
import java.awt.Component;
import java.awt.KeyEventDispatcher;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.DefaultListModel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JRootPane;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

final class WindowNavigationKeysTest {

  @Test
  void visibleSelectionWrapsInBothDirections() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          JList<String> navigation = navigation("Console", "Terminal", "Overview");

          navigation.setSelectedIndex(0);
          assertThat(WindowNavigationKeys.moveSelection(navigation, Direction.PREVIOUS)).isTrue();
          assertThat(navigation.getSelectedIndex()).isEqualTo(2);
          assertThat(WindowNavigationKeys.moveSelection(navigation, Direction.NEXT)).isTrue();
          assertThat(navigation.getSelectedIndex()).isZero();

          navigation.clearSelection();
          assertThat(WindowNavigationKeys.moveSelection(navigation, Direction.NEXT)).isTrue();
          assertThat(navigation.getSelectedIndex()).isZero();
          navigation.clearSelection();
          assertThat(WindowNavigationKeys.moveSelection(navigation, Direction.PREVIOUS)).isTrue();
          assertThat(navigation.getSelectedIndex()).isEqualTo(2);

          assertThat(WindowNavigationKeys.moveSelection(navigation(), Direction.NEXT)).isFalse();
        });
  }

  @Test
  void ctrlTabMovesOnceAndConsumesTheWholeKeySequence() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          Fixture fixture = fixture(true, "Console", "Terminal", "Overview");
          fixture.navigation().setSelectedIndex(0);

          KeyEvent pressed =
              keyPressed(fixture.focusOwner(), KeyEvent.VK_TAB, InputEvent.CTRL_DOWN_MASK);
          KeyEvent typed = keyTyped(fixture.focusOwner(), '\t', InputEvent.CTRL_DOWN_MASK);
          KeyEvent released =
              keyReleased(fixture.focusOwner(), KeyEvent.VK_TAB, InputEvent.CTRL_DOWN_MASK);

          assertThat(fixture.keys().dispatchKeyEvent(pressed)).isTrue();
          assertThat(fixture.navigation().getSelectedIndex()).isEqualTo(1);
          assertThat(fixture.keys().dispatchKeyEvent(typed)).isTrue();
          assertThat(fixture.keys().dispatchKeyEvent(released)).isTrue();
          assertThat(fixture.navigation().getSelectedIndex()).isEqualTo(1);
          assertThat(pressed.isConsumed()).isTrue();
          assertThat(typed.isConsumed()).isTrue();
          assertThat(released.isConsumed()).isTrue();
        });
  }

  @Test
  void ctrlShiftTabMovesBackwardFromATerminalLikeComponent() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          Fixture fixture = fixture(true, "Console", "Terminal", "Overview");
          fixture.navigation().setSelectedIndex(0);
          JPanel terminalSurface = new JPanel();
          fixture.content().add(terminalSurface);

          KeyEvent event =
              keyPressed(
                  terminalSurface,
                  KeyEvent.VK_TAB,
                  InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK);

          assertThat(fixture.keys().dispatchKeyEvent(event)).isTrue();
          assertThat(fixture.navigation().getSelectedIndex()).isEqualTo(2);
          assertThat(event.isConsumed()).isTrue();
        });
  }

  @Test
  void otherWindowsAndOtherModifiersAreNotIntercepted() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          Fixture first = fixture(true, "Console", "Overview");
          Fixture second = fixture(true, "Console", "Terminal", "Overview");
          first.navigation().setSelectedIndex(0);
          second.navigation().setSelectedIndex(0);

          KeyEvent secondWindow =
              keyPressed(second.focusOwner(), KeyEvent.VK_TAB, InputEvent.CTRL_DOWN_MASK);
          assertThat(first.keys().dispatchKeyEvent(secondWindow)).isFalse();
          assertThat(secondWindow.isConsumed()).isFalse();
          assertThat(second.keys().dispatchKeyEvent(secondWindow)).isTrue();
          assertThat(first.navigation().getSelectedIndex()).isZero();
          assertThat(second.navigation().getSelectedIndex()).isEqualTo(1);

          List<KeyEvent> ignored =
              List.of(
                  keyPressed(first.focusOwner(), KeyEvent.VK_TAB, 0),
                  keyPressed(first.focusOwner(), KeyEvent.VK_TAB, InputEvent.META_DOWN_MASK),
                  keyPressed(
                      first.focusOwner(),
                      KeyEvent.VK_TAB,
                      InputEvent.CTRL_DOWN_MASK | InputEvent.ALT_DOWN_MASK),
                  keyPressed(first.focusOwner(), KeyEvent.VK_A, InputEvent.CTRL_DOWN_MASK));
          for (KeyEvent event : ignored) {
            assertThat(first.keys().dispatchKeyEvent(event)).isFalse();
            assertThat(event.isConsumed()).isFalse();
          }
          assertThat(first.navigation().getSelectedIndex()).isZero();
        });
  }

  @Test
  void hiddenOrEmptyNavigationLeavesTheChordToTheFocusedComponent() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          Fixture hidden = fixture(false, "Console", "Overview");
          KeyEvent hiddenEvent =
              keyPressed(hidden.focusOwner(), KeyEvent.VK_TAB, InputEvent.CTRL_DOWN_MASK);
          assertThat(hidden.keys().dispatchKeyEvent(hiddenEvent)).isFalse();
          assertThat(hiddenEvent.isConsumed()).isFalse();

          Fixture empty = fixture(true);
          KeyEvent emptyEvent =
              keyPressed(empty.focusOwner(), KeyEvent.VK_TAB, InputEvent.CTRL_DOWN_MASK);
          assertThat(empty.keys().dispatchKeyEvent(emptyEvent)).isFalse();
          assertThat(emptyEvent.isConsumed()).isFalse();
        });
  }

  @Test
  void closeUnregistersOnceAndMakesAStaleDispatcherInert() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          Fixture fixture = fixture(true, "Console", "Overview");
          assertThat(fixture.registry().added).containsExactly(fixture.keys());

          fixture.keys().close();
          fixture.keys().close();

          assertThat(fixture.registry().removed).containsExactly(fixture.keys());
          KeyEvent event =
              keyPressed(fixture.focusOwner(), KeyEvent.VK_TAB, InputEvent.CTRL_DOWN_MASK);
          assertThat(fixture.keys().dispatchKeyEvent(event)).isFalse();
          assertThat(event.isConsumed()).isFalse();
        });
  }

  private static Fixture fixture(boolean active, String... entries) {
    JRootPane root = new JRootPane();
    JPanel content = new JPanel();
    JTextField focusOwner = new JTextField();
    JList<String> navigation = navigation(entries);
    content.add(focusOwner);
    content.add(navigation);
    root.setContentPane(content);
    FakeRegistry registry = new FakeRegistry();
    AtomicBoolean enabled = new AtomicBoolean(active);
    WindowNavigationKeys keys =
        WindowNavigationKeys.install(root, navigation, enabled::get, registry);
    return new Fixture(content, focusOwner, navigation, registry, keys);
  }

  private static JList<String> navigation(String... entries) {
    DefaultListModel<String> model = new DefaultListModel<>();
    for (String entry : entries) {
      model.addElement(entry);
    }
    return new JList<>(model);
  }

  private static KeyEvent keyPressed(JPanel source, int keyCode, int modifiers) {
    return keyEvent(source, KeyEvent.KEY_PRESSED, keyCode, KeyEvent.CHAR_UNDEFINED, modifiers);
  }

  private static KeyEvent keyPressed(JTextField source, int keyCode, int modifiers) {
    return keyEvent(source, KeyEvent.KEY_PRESSED, keyCode, KeyEvent.CHAR_UNDEFINED, modifiers);
  }

  private static KeyEvent keyReleased(JTextField source, int keyCode, int modifiers) {
    return keyEvent(source, KeyEvent.KEY_RELEASED, keyCode, KeyEvent.CHAR_UNDEFINED, modifiers);
  }

  private static KeyEvent keyTyped(JTextField source, char keyChar, int modifiers) {
    return keyEvent(source, KeyEvent.KEY_TYPED, KeyEvent.VK_UNDEFINED, keyChar, modifiers);
  }

  private static KeyEvent keyEvent(
      Component source, int id, int keyCode, char keyChar, int modifiers) {
    return new KeyEvent(source, id, System.currentTimeMillis(), modifiers, keyCode, keyChar);
  }

  private record Fixture(
      JPanel content,
      JTextField focusOwner,
      JList<String> navigation,
      FakeRegistry registry,
      WindowNavigationKeys keys) {}

  private static final class FakeRegistry implements WindowNavigationKeys.DispatcherRegistry {
    private final List<KeyEventDispatcher> added = new ArrayList<>();
    private final List<KeyEventDispatcher> removed = new ArrayList<>();

    @Override
    public void add(KeyEventDispatcher dispatcher) {
      added.add(dispatcher);
    }

    @Override
    public void remove(KeyEventDispatcher dispatcher) {
      removed.add(dispatcher);
    }
  }
}
