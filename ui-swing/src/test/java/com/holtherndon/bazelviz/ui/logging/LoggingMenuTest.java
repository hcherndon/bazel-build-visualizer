package com.holtherndon.bazelviz.ui.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.SwingUtilities;
import javax.swing.event.MenuEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

final class LoggingMenuTest {

  @Test
  @DisplayName("the menu exposes every level and both log-file actions")
  void menuIsCompleteAndAccessible() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          MutableRuntime runtime = new MutableRuntime(LogVerbosity.INFO, 3);
          AtomicReference<LogVerbosity> selected = new AtomicReference<>();
          AtomicInteger opened = new AtomicInteger();
          AtomicInteger revealed = new AtomicInteger();
          JMenu menu =
              LoggingMenu.create(
                  runtime,
                  verbosity -> {
                    runtime.setVerbosity(verbosity);
                    selected.set(verbosity);
                    return true;
                  },
                  opened::incrementAndGet,
                  revealed::incrementAndGet);

          assertThat(menu.getText()).isEqualTo("Diagnostics");
          assertThat(menu.getAccessibleContext().getAccessibleDescription()).isNotBlank();
          JMenu detail = (JMenu) menu.getItem(0);
          assertThat(detail.getText()).isEqualTo("Log Detail");
          assertThat(detail.getAccessibleContext().getAccessibleDescription()).isNotBlank();
          assertThat(detail.getItemCount()).isEqualTo(LogVerbosity.values().length);
          assertThat(
                  IntStream.range(0, detail.getItemCount())
                      .mapToObj(detail::getItem)
                      .filter(JRadioButtonMenuItem.class::isInstance)
                      .map(JRadioButtonMenuItem.class::cast)
                      .filter(JRadioButtonMenuItem::isSelected))
              .singleElement()
              .extracting(JRadioButtonMenuItem::getActionCommand)
              .isEqualTo("info");

          for (int index = 0; index < detail.getItemCount(); index++) {
            JRadioButtonMenuItem item = (JRadioButtonMenuItem) detail.getItem(index);
            LogVerbosity verbosity = LogVerbosity.values()[index];
            assertThat(item.getText()).isEqualTo(verbosity.displayName());
            assertThat(item.getActionCommand()).isEqualTo(verbosity.id());
            assertThat(item.getToolTipText()).isEqualTo(verbosity.description());
            assertThat(item.getAccessibleContext().getAccessibleDescription()).isNotBlank();
          }

          ((JRadioButtonMenuItem) detail.getItem(4)).doClick();
          assertThat(selected).hasValue(LogVerbosity.TRACE);
          assertThat(detail.getItem(4).isSelected()).isTrue();
          assertThat(detail.getItem(2).isSelected()).isFalse();

          JMenuItem open = menu.getItem(3);
          JMenuItem reveal = menu.getItem(4);
          assertThat(open.getText()).isEqualTo("Open Application Log…");
          assertThat(reveal.getText()).isEqualTo("Reveal Application Log");
          assertThat(open.getAccessibleContext().getAccessibleDescription()).isNotBlank();
          assertThat(reveal.getAccessibleContext().getAccessibleDescription()).isNotBlank();
          open.doClick();
          reveal.doClick();
          assertThat(opened).hasValue(1);
          assertThat(revealed).hasValue(1);
        });
  }

  @Test
  @DisplayName("opening the menu refreshes active level and dropped-record count")
  void refreshesRuntimeStateWhenOpened() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          MutableRuntime runtime = new MutableRuntime(LogVerbosity.WARN, 2);
          JMenu menu = LoggingMenu.create(runtime, verbosity -> true, () -> {}, () -> {});
          JMenu detail = (JMenu) menu.getItem(0);
          assertThat(menu.getItem(2).getText()).isEqualTo("Dropped records: 2");

          runtime.verbosity = LogVerbosity.DEBUG;
          runtime.dropped = 17;
          select(menu);

          assertThat(menu.getItem(2).getText()).isEqualTo("Dropped records: 17");
          assertThat(detail.getItem(3).isSelected()).isTrue();
          assertThat(detail.getItem(1).isSelected()).isFalse();
        });
  }

  @Test
  @DisplayName("a rejected level restores the last successfully applied choice")
  void failedSelectionRollsBack() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          MutableRuntime runtime = new MutableRuntime(LogVerbosity.INFO, 0);
          JMenu menu = LoggingMenu.create(runtime, verbosity -> false, () -> {}, () -> {});
          JMenu detail = (JMenu) menu.getItem(0);

          ((JRadioButtonMenuItem) detail.getItem(4)).doClick();

          assertThat(detail.getItem(2).isSelected()).isTrue();
          assertThat(detail.getItem(4).isSelected()).isFalse();
        });
  }

  @Test
  @DisplayName("an unavailable runtime disables every action and says why")
  void unavailableRuntimeDisablesControls() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          JMenu menu =
              LoggingMenu.create(
                  LoggingRuntime.unavailable(), verbosity -> true, () -> {}, () -> {});

          assertThat(menu.getItem(0).isEnabled()).isFalse();
          assertThat(menu.getItem(2).getText()).isEqualTo("Application logging unavailable");
          assertThat(menu.getItem(3).isEnabled()).isFalse();
          assertThat(menu.getItem(4).isEnabled()).isFalse();
        });
  }

  @Test
  @DisplayName("a backend status failure degrades to unavailable instead of breaking the menu")
  void runtimeFailureIsContained() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          LoggingRuntime broken =
              new MutableRuntime(LogVerbosity.INFO, 0) {
                @Override
                public boolean available() {
                  throw new IllegalStateException("backend stopped");
                }
              };
          JMenu menu = LoggingMenu.create(broken, verbosity -> true, () -> {}, () -> {});

          assertThat(menu.getItem(0).isEnabled()).isFalse();
          assertThat(menu.getItem(2).getText()).isEqualTo("Application logging unavailable");
        });
  }

  private static void select(JMenu menu) {
    MenuEvent event = new MenuEvent(menu);
    for (var listener : menu.getMenuListeners()) {
      listener.menuSelected(event);
    }
  }

  private static class MutableRuntime implements LoggingRuntime {

    private LogVerbosity verbosity;
    private long dropped;

    private MutableRuntime(LogVerbosity verbosity, long dropped) {
      this.verbosity = verbosity;
      this.dropped = dropped;
    }

    @Override
    public LogVerbosity verbosity() {
      return verbosity;
    }

    @Override
    public void setVerbosity(LogVerbosity verbosity) {
      this.verbosity = verbosity;
    }

    @Override
    public Path currentLog() {
      return Path.of("application.log");
    }

    @Override
    public long droppedRecordCount() {
      return dropped;
    }

    @Override
    public boolean available() {
      return true;
    }
  }
}
