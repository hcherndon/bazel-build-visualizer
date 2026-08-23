package com.holtherndon.bazelviz.app;

import com.holtherndon.bazelviz.app.cli.CliMain;
import com.holtherndon.bazelviz.app.dirs.AppDirectories;
import com.holtherndon.bazelviz.ui.MainWindow;
import com.holtherndon.bazelviz.ui.theme.Themes;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Application entry point. */
public final class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

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
        java.nio.file.Path openAtStartup = null;
        if (args.length == 1 && java.nio.file.Files.exists(java.nio.file.Path.of(args[0]))) {
            openAtStartup = java.nio.file.Path.of(args[0]);
        } else if (args.length > 0) {
            System.exit(CliMain.run(args, System.out, System.err, AppInfo.VERSION));
        }

        // -Dbbv.smoke=true launches the window, then exits after two seconds.
        // Used by CI and scripted verification; never set in normal runs.
        boolean smoke = Boolean.getBoolean("bbv.smoke");

        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) ->
                log.error("Uncaught exception on {}", thread.getName(), throwable));

        // Directory creation is I/O, so it happens here on the main thread,
        // before the EDT is ever involved.
        AppDirectories dirs = AppDirectories.systemDefault();
        log.info("Application support root: {}", dirs.root());
        log.info("Logging is console-only in Phase 0; file logging will land in {}", dirs.logs());

        boolean dark = "dark".equalsIgnoreCase(System.getProperty("bbv.theme"));
        java.nio.file.Path opening = openAtStartup;

        SwingUtilities.invokeLater(() -> {
            if (dark) {
                Themes.installDark();
            } else {
                Themes.installDefault();
            }
            // Where sessions live is configuration-directory discovery, which the
            // plan assigns to this module; ui-swing is handed the resolved path
            // rather than re-deriving the platform rules for itself.
            MainWindow window = new MainWindow(dirs.managedSessions());
            DesktopIntegration.install(window);
            window.setVisible(true);
            log.info("Main window shown");
            if (opening != null) {
                // The same route the Open menu and the desktop handler take.
                window.openPath(opening);
            }
            if (smoke) {
                Timer timer = new Timer(2000, e -> {
                    log.info("Smoke mode: exiting");
                    window.dispose();
                    System.exit(0);
                });
                timer.setRepeats(false);
                timer.start();
            }
        });
    }
}
