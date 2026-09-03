package com.holtherndon.bazelviz.app;

import com.holtherndon.bazelviz.app.cli.CliMain;
import com.holtherndon.bazelviz.app.dirs.AppDirectories;
import com.holtherndon.bazelviz.app.logging.ApplicationLogging;
import com.holtherndon.bazelviz.ui.capture.LauncherStateStore;
import com.holtherndon.bazelviz.ui.logging.LogVerbosity;
import com.holtherndon.bazelviz.ui.logging.LoggingSettingsStore;
import com.holtherndon.bazelviz.ui.theme.AppTheme;
import com.holtherndon.bazelviz.ui.theme.ThemeSettingsStore;
import com.holtherndon.bazelviz.ui.theme.Themes;
import com.holtherndon.bazelviz.ui.workspace.WorkspaceLauncherHistoryMigration;
import com.holtherndon.bazelviz.ui.workspace.WorkspaceProfile;
import com.holtherndon.bazelviz.ui.workspace.WorkspaceStore;
import com.holtherndon.bazelviz.ui.workspace.WorkspaceUiSettings;
import com.holtherndon.bazelviz.ui.workspace.WorkspaceWindowStateStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Application entry point. */
public final class Main {
  private Main() {}

  public static void main(String[] args) {
    // The subcommand seam, deliberately the very first thing that happens.
    // Everything below it — the application directories, the look and feel,
    // the EDT, the window — is graphical-mode setup, and a headless run on a
    // CI machine with no display must reach none of it. Dispatching here
    // rather than later is what makes "never initializes Swing" a property
    // of the control flow instead of a promise.
    // A single path is the shape macOS uses on some launches and the shape
    // a user types when they mean "open this"; anything else is a
    // subcommand. Checking the file system rather than the argument's
    // spelling means `bbv session-0193…` opens a session and `bbv import`
    // stays a subcommand even if somebody creates a file called import.
    Path openAtStartup = null;
    if (args.length == 1 && Files.exists(Path.of(args[0]))) {
      openAtStartup = Path.of(args[0]);
    } else if (args.length > 0) {
      // Resolve this before CliMain (and therefore SLF4J) initializes.
      // Logback must never interpret an unknown level by itself: its
      // fallback may be more verbose than Warn, which would expose
      // dependency internals during a headless command. Canonicalizing a
      // valid value also makes whitespace and case harmless.
      String requestedLogLevel = System.getProperty(ApplicationLogging.LEVEL_PROPERTY);
      if (requestedLogLevel != null) {
        Optional<LogVerbosity> accepted = LogVerbosity.fromId(requestedLogLevel);
        if (accepted.isPresent()) {
          System.setProperty(ApplicationLogging.LEVEL_PROPERTY, accepted.orElseThrow().id());
        } else {
          System.setProperty(ApplicationLogging.LEVEL_PROPERTY, LogVerbosity.WARN.id());
          System.err.println(
              "bbv: invalid bbv.log.level; using warn"
                  + " (expected error, warn, info, debug, or trace)");
        }
      }
      System.exit(CliMain.run(args, System.out, System.err, AppInfo.VERSION));
    }

    // -Dbbv.smoke=true launches the window, then exits after two seconds.
    // Used by CI and scripted verification; never set in normal runs.
    boolean smoke = Boolean.getBoolean("bbv.smoke");

    // Keep a graphical launch's terminal quiet even when its rolling file
    // is recording Debug or Trace. This happens only after the CLI seam,
    // so subcommands retain their console-only logging contract.
    System.setProperty(ApplicationLogging.CONSOLE_LEVEL_PROPERTY, "WARN");

    // Directory creation is I/O, so it happens here on the main thread,
    // before the EDT is ever involved.
    AppDirectories dirs = AppDirectories.systemDefault();

    // Logging settings and directory setup are blocking I/O and therefore
    // stay on this startup thread. The process property is resolved by the
    // application backend without overwriting the saved user preference.
    Path settingsDirectory = dirs.settings();
    LoggingSettingsStore loggingStore = new LoggingSettingsStore(settingsDirectory);
    LoggingSettingsStore.LoadResult loggingSettings = loggingStore.loadWithDiagnostics();
    ApplicationLogging applicationLogging =
        ApplicationLogging.start(dirs.logs(), loggingSettings.verbosity());
    loggingSettings.warning().ifPresent(applicationLogging::recordConfigurationWarning);
    Logger log = LoggerFactory.getLogger(Main.class);
    Thread.setDefaultUncaughtExceptionHandler(
        (thread, throwable) -> log.error("Uncaught exception on {}", thread.getName(), throwable));
    log.info(
        "Application starting: version={}, java={}, os={}, supportRoot={}, verbosity={}",
        AppInfo.VERSION,
        System.getProperty("java.version", "unknown"),
        System.getProperty("os.name", "unknown"),
        dirs.root(),
        applicationLogging.verbosity().id());

    // Appearance is graphical-mode state, so it is deliberately resolved
    // only after the CLI's headless early exit. The read is blocking I/O
    // and therefore stays on this startup thread, before the EDT exists;
    // this also prevents a light window from flashing before a saved dark
    // theme is applied.
    ThemeSettingsStore themeStore = new ThemeSettingsStore(settingsDirectory);
    AppTheme savedTheme = themeStore.load();
    String themeOverride = System.getProperty("bbv.theme");
    if (themeOverride != null
        && !themeOverride.isBlank()
        && AppTheme.fromId(themeOverride).isEmpty()) {
      log.warn(
          "Unknown bbv.theme '{}'; using saved theme {}", themeOverride, savedTheme.displayName());
    }
    AppTheme startupTheme = Themes.startupTheme(themeOverride, savedTheme);
    WorkspaceStore workspaceStore = new WorkspaceStore(settingsDirectory);
    WorkspaceStore.LoadResult workspaceSettings =
        loadWorkspaces(workspaceStore, settingsDirectory, System.currentTimeMillis() * 1_000L);
    WorkspaceLauncherHistoryMigration.Result historyMigration =
        WorkspaceLauncherHistoryMigration.migrate(
            settingsDirectory, workspaceSettings.workspaces());
    WorkspaceWindowStateStore workspaceWindowStateStore =
        new WorkspaceWindowStateStore(settingsDirectory);
    WorkspaceWindowStateStore.LoadResult workspaceWindowSettings =
        workspaceWindowStateStore.loadWithDiagnostics();
    workspaceSettings
        .diagnostics()
        .forEach(diagnostic -> log.warn("Workspace startup: {}", diagnostic));
    historyMigration
        .diagnostics()
        .forEach(diagnostic -> log.warn("Workspace launcher history: {}", diagnostic));
    if (historyMigration.workspacesUpdated() > 0) {
      log.info(
          "Migrated legacy Bazel command history into {} Workspace {}",
          historyMigration.workspacesUpdated(),
          historyMigration.workspacesUpdated() == 1 ? "profile" : "profiles");
    }
    log.debug(
        "Workspace settings loaded: source={}, profileCount={}, persisted={}",
        workspaceSettings.source(),
        workspaceSettings.workspaces().size(),
        workspaceSettings.persisted());
    if (workspaceSettings.source() != WorkspaceStore.Source.UNUSABLE) {
      try {
        int removed =
            WorkspaceUiSettings.deleteOrphans(
                settingsDirectory,
                workspaceSettings.workspaces().stream().map(WorkspaceProfile::id).toList());
        if (removed > 0) {
          log.info(
              "Removed {} orphaned Workspace UI-state {}",
              removed,
              removed == 1 ? "directory" : "directories");
        }
      } catch (IOException | RuntimeException failure) {
        log.warn("Could not clean orphaned Workspace UI state", failure);
      }
    }
    Path opening = openAtStartup;

    SwingUtilities.invokeLater(
        () -> {
          Themes.install(startupTheme);
          // Where sessions live is configuration-directory discovery, which the
          // plan assigns to this module; ui-swing is handed the resolved path
          // rather than re-deriving the platform rules for itself.
          ApplicationController application =
              new ApplicationController(
                  dirs.managedSessions(),
                  dirs.catalog(),
                  settingsDirectory,
                  applicationLogging,
                  workspaceStore,
                  workspaceSettings.workspaces(),
                  workspaceWindowStateStore,
                  workspaceWindowSettings.state());
          DesktopIntegration.install(application);
          application.showInitialWindows();
          log.info("Application windows shown");
          if (!applicationLogging.available()) {
            applicationLogging
                .startupWarning()
                .ifPresent(
                    warning ->
                        JOptionPane.showMessageDialog(
                            application.activeParent(),
                            warning,
                            "Application logging unavailable",
                            JOptionPane.WARNING_MESSAGE));
          }
          if (!workspaceSettings.diagnostics().isEmpty()) {
            JOptionPane.showMessageDialog(
                application.activeParent(),
                String.join("\n", workspaceSettings.diagnostics()),
                "Workspace settings",
                JOptionPane.WARNING_MESSAGE);
          }
          if (!workspaceWindowSettings.diagnostics().isEmpty()) {
            JOptionPane.showMessageDialog(
                application.activeParent(),
                String.join("\n", workspaceWindowSettings.diagnostics()),
                "Workspace windows",
                JOptionPane.WARNING_MESSAGE);
          }
          if (opening != null) {
            // The same route the Open menu and the desktop handler take.
            application.openPath(opening);
          }
          if (smoke) {
            Timer timer =
                new Timer(
                    2000,
                    e -> {
                      log.info("Smoke mode: exiting");
                      application
                          .quit()
                          .whenComplete(
                              (ignored, failure) -> {
                                if (failure != null) {
                                  log.warn("Smoke-mode application did not close cleanly", failure);
                                }
                                System.exit(failure == null ? 0 : 1);
                              });
                    });
            timer.setRepeats(false);
            timer.start();
          }
        });
  }

  /** Loads saved workspaces, importing launcher settings only when that legacy file exists. */
  static WorkspaceStore.LoadResult loadWorkspaces(
      WorkspaceStore workspaceStore, Path settingsDirectory, long migrationTimeMicros) {
    LauncherStateStore legacyStore = new LauncherStateStore(settingsDirectory);
    if (!Files.isRegularFile(legacyStore.file())) {
      return workspaceStore.loadWithDiagnostics();
    }
    return workspaceStore.loadOrMigrate(legacyStore.load(), migrationTimeMicros);
  }
}
