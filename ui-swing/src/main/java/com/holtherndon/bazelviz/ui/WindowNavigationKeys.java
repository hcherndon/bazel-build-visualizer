package com.holtherndon.bazelviz.ui;

import java.awt.Component;
import java.awt.KeyEventDispatcher;
import java.awt.KeyboardFocusManager;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import javax.swing.JList;
import javax.swing.JRootPane;
import javax.swing.SwingUtilities;

/**
 * Owns the page-cycling keys for one {@link MainWindow}.
 *
 * <p>A focus-manager dispatcher deliberately handles the chord before a focused editor or terminal
 * can consume it. The owner root pane prevents one of the application's native windows from
 * changing another window's navigation. Closing the owner must close this registration too.
 */
final class WindowNavigationKeys implements KeyEventDispatcher, AutoCloseable {

  enum Direction {
    NEXT,
    PREVIOUS
  }

  interface DispatcherRegistry {
    void add(KeyEventDispatcher dispatcher);

    void remove(KeyEventDispatcher dispatcher);
  }

  private final JRootPane ownerRoot;
  private final JList<?> navigation;
  private final BooleanSupplier active;
  private final DispatcherRegistry registry;
  private boolean consumingChord;
  private boolean closed;

  /** Installs navigation for the visible sidebar in one native window. */
  static WindowNavigationKeys install(JRootPane ownerRoot, JList<?> navigation) {
    KeyboardFocusManager focusManager = KeyboardFocusManager.getCurrentKeyboardFocusManager();
    return install(
        ownerRoot,
        navigation,
        navigation::isShowing,
        new DispatcherRegistry() {
          @Override
          public void add(KeyEventDispatcher dispatcher) {
            focusManager.addKeyEventDispatcher(dispatcher);
          }

          @Override
          public void remove(KeyEventDispatcher dispatcher) {
            focusManager.removeKeyEventDispatcher(dispatcher);
          }
        });
  }

  /** Injectable registration and visibility seam for headless tests. */
  static WindowNavigationKeys install(
      JRootPane ownerRoot,
      JList<?> navigation,
      BooleanSupplier active,
      DispatcherRegistry registry) {
    WindowNavigationKeys keys = new WindowNavigationKeys(ownerRoot, navigation, active, registry);
    registry.add(keys);
    return keys;
  }

  private WindowNavigationKeys(
      JRootPane ownerRoot,
      JList<?> navigation,
      BooleanSupplier active,
      DispatcherRegistry registry) {
    this.ownerRoot = Objects.requireNonNull(ownerRoot, "ownerRoot");
    this.navigation = Objects.requireNonNull(navigation, "navigation");
    this.active = Objects.requireNonNull(active, "active");
    this.registry = Objects.requireNonNull(registry, "registry");
  }

  @Override
  public boolean dispatchKeyEvent(KeyEvent event) {
    Objects.requireNonNull(event, "event");
    if (closed || !belongsToOwner(event)) {
      return false;
    }

    if (event.getID() == KeyEvent.KEY_RELEASED
        && consumingChord
        && event.getKeyCode() == KeyEvent.VK_TAB) {
      consumingChord = false;
      event.consume();
      return true;
    }
    if (event.getID() == KeyEvent.KEY_TYPED && consumingChord && event.getKeyChar() == '\t') {
      event.consume();
      return true;
    }
    if (event.getID() != KeyEvent.KEY_PRESSED) {
      return false;
    }

    consumingChord = false;
    Direction direction = direction(event);
    if (direction == null || !active.getAsBoolean() || !moveSelection(navigation, direction)) {
      return false;
    }
    consumingChord = true;
    event.consume();
    return true;
  }

  private boolean belongsToOwner(KeyEvent event) {
    return event.getSource() instanceof Component source
        && SwingUtilities.getRootPane(source) == ownerRoot;
  }

  private static Direction direction(KeyEvent event) {
    if (event.getKeyCode() != KeyEvent.VK_TAB) {
      return null;
    }
    int modifiers = event.getModifiersEx();
    if (modifiers == InputEvent.CTRL_DOWN_MASK) {
      return Direction.NEXT;
    }
    if (modifiers == (InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK)) {
      return Direction.PREVIOUS;
    }
    return null;
  }

  static boolean moveSelection(JList<?> navigation, Direction direction) {
    Objects.requireNonNull(navigation, "navigation");
    int next =
        wrappedIndex(navigation.getSelectedIndex(), navigation.getModel().getSize(), direction);
    if (next < 0) {
      return false;
    }
    navigation.setSelectedIndex(next);
    navigation.ensureIndexIsVisible(next);
    return true;
  }

  static int wrappedIndex(int selectedIndex, int itemCount, Direction direction) {
    Objects.requireNonNull(direction, "direction");
    if (itemCount <= 0) {
      return -1;
    }
    if (selectedIndex < 0 || selectedIndex >= itemCount) {
      return direction == Direction.NEXT ? 0 : itemCount - 1;
    }
    int offset = direction == Direction.NEXT ? 1 : -1;
    return Math.floorMod(selectedIndex + offset, itemCount);
  }

  /** Removes the process-global registration without retaining the closed window. */
  @Override
  public void close() {
    if (closed) {
      return;
    }
    closed = true;
    consumingChord = false;
    registry.remove(this);
  }
}
