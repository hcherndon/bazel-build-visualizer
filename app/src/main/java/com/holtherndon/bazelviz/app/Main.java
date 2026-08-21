package com.holtherndon.bazelviz.app;

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

        SwingUtilities.invokeLater(() -> {
            if (dark) {
                Themes.installDark();
            } else {
                Themes.installDefault();
            }
            MainWindow window = new MainWindow();
            DesktopIntegration.install(window);
            window.setVisible(true);
            log.info("Main window shown");
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
