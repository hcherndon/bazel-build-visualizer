package com.holtherndon.bazelviz.ui.logging;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import javax.swing.ButtonGroup;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.event.MenuEvent;
import javax.swing.event.MenuListener;

/** Builds the discoverable diagnostics and logging controls. */
public final class LoggingMenu {

  private LoggingMenu() {}

  /**
   * Creates a Diagnostics menu backed only by the neutral runtime contract.
   *
   * <p>{@code onSelected} applies and persists an explicit user choice. It returns false when the
   * backend rejected the change, in which case the prior radio selection is restored. File actions
   * are supplied by the window so this package does not own file viewers or platform integration.
   */
  public static JMenu create(
      LoggingRuntime runtime,
      Predicate<LogVerbosity> onSelected,
      Runnable openLog,
      Runnable revealLog) {
    Objects.requireNonNull(runtime, "runtime");
    Objects.requireNonNull(onSelected, "onSelected");
    Objects.requireNonNull(openLog, "openLog");
    Objects.requireNonNull(revealLog, "revealLog");

    JMenu diagnostics = new JMenu("Diagnostics");
    diagnostics
        .getAccessibleContext()
        .setAccessibleDescription(
            "Control application log detail and inspect the current application log.");

    JMenu detail = new JMenu("Log Detail");
    detail
        .getAccessibleContext()
        .setAccessibleDescription("Choose how much diagnostic detail the application records.");
    ButtonGroup choices = new ButtonGroup();
    Map<LogVerbosity, JRadioButtonMenuItem> items = new EnumMap<>(LogVerbosity.class);
    LogVerbosity[] accepted = {safeVerbosity(runtime)};
    for (LogVerbosity verbosity : LogVerbosity.values()) {
      JRadioButtonMenuItem item = new JRadioButtonMenuItem(verbosity.displayName());
      item.setActionCommand(verbosity.id());
      item.setSelected(verbosity == accepted[0]);
      item.setToolTipText(verbosity.description());
      item.getAccessibleContext().setAccessibleDescription(verbosity.description());
      item.addActionListener(
          event -> {
            if (onSelected.test(verbosity)) {
              accepted[0] = verbosity;
            } else {
              items.get(accepted[0]).setSelected(true);
            }
          });
      choices.add(item);
      items.put(verbosity, item);
      detail.add(item);
    }

    JMenuItem dropped = new JMenuItem("Dropped records: 0");
    dropped.setEnabled(false);
    dropped
        .getAccessibleContext()
        .setAccessibleDescription(
            "Number of application log records the asynchronous writer could not retain.");

    JMenuItem open = new JMenuItem("Open Application Log…");
    open.setToolTipText("Open the current log in a modeless, read-only text viewer.");
    open.getAccessibleContext().setAccessibleDescription(open.getToolTipText());
    open.addActionListener(event -> openLog.run());

    JMenuItem reveal = new JMenuItem("Reveal Application Log");
    reveal.setToolTipText("Show the current application log in the platform file manager.");
    reveal.getAccessibleContext().setAccessibleDescription(reveal.getToolTipText());
    reveal.addActionListener(event -> revealLog.run());

    diagnostics.add(detail);
    diagnostics.addSeparator();
    diagnostics.add(dropped);
    diagnostics.add(open);
    diagnostics.add(reveal);
    diagnostics.addMenuListener(
        new MenuListener() {
          @Override
          public void menuSelected(MenuEvent event) {
            refresh(runtime, detail, items, accepted, dropped, open, reveal);
          }

          @Override
          public void menuDeselected(MenuEvent event) {}

          @Override
          public void menuCanceled(MenuEvent event) {}
        });
    refresh(runtime, detail, items, accepted, dropped, open, reveal);
    return diagnostics;
  }

  private static void refresh(
      LoggingRuntime runtime,
      JMenu detail,
      Map<LogVerbosity, JRadioButtonMenuItem> items,
      LogVerbosity[] accepted,
      JMenuItem dropped,
      JMenuItem open,
      JMenuItem reveal) {
    boolean available;
    try {
      available = runtime.available();
      if (available) {
        LogVerbosity current = Objects.requireNonNull(runtime.verbosity(), "runtime verbosity");
        accepted[0] = current;
        items.get(current).setSelected(true);
        long count = runtime.droppedRecordCount();
        dropped.setText(count < 0 ? "Dropped records: unavailable" : "Dropped records: " + count);
      } else {
        dropped.setText("Application logging unavailable");
      }
    } catch (RuntimeException unavailable) {
      available = false;
      dropped.setText("Application logging unavailable");
    }
    detail.setEnabled(available);
    open.setEnabled(available);
    reveal.setEnabled(available);
  }

  private static LogVerbosity safeVerbosity(LoggingRuntime runtime) {
    try {
      LogVerbosity verbosity = runtime.verbosity();
      return verbosity == null ? LogVerbosity.defaultVerbosity() : verbosity;
    } catch (RuntimeException unavailable) {
      return LogVerbosity.defaultVerbosity();
    }
  }
}
