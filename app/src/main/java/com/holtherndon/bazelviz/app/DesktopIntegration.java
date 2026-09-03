package com.holtherndon.bazelviz.app;

import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import java.awt.Window;
import java.io.File;
import java.util.List;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Desktop-environment integration (plan 17.2, and plan 24's Phase 9
 * "macOS app menu and open-file handlers").
 *
 * <h2>Every call is guarded</h2>
 *
 * <p>{@code Desktop} is optional, and each action is optional separately.
 * Headless CI, Linux and older Windows all reach this code and all of it has to
 * be a silent no-op there — an application that failed to start because it
 * could not install a macOS menu handler would be worse than one without the
 * menu.
 *
 * <h2>Open-file is the one that changes what the application is</h2>
 *
 * <p>With a file association declared at packaging time, macOS launches the
 * app for a double-clicked {@code .bviz} and delivers the path through
 * {@code APP_OPEN_FILE} rather than through {@code main}. Without a handler the
 * app opens and shows an empty window, which looks exactly like the
 * association not working. The handler routes to
 * the application controller, which chooses the active workspace window or a
 * workspace-less analysis shell. This is the same classification route used
 * by the Open menu and a command-line argument.
 *
 * <h2>Preferences uses the same window route as the Swing menu</h2>
 *
 * <p>Where the desktop owns an application Preferences item, it opens the same
 * modeless settings window as {@code Settings > Preferences}. Unsupported
 * platforms keep the ordinary Swing menu.
 */
final class DesktopIntegration {

    private static final Logger log = LoggerFactory.getLogger(DesktopIntegration.class);

    private DesktopIntegration() {}

    /** Installs the handlers the platform supports. Safe everywhere. */
    static void install(ApplicationController application) {
        if (GraphicsEnvironment.isHeadless() || !Desktop.isDesktopSupported()) {
            return;
        }
        Desktop desktop = Desktop.getDesktop();
        if (desktop.isSupported(Desktop.Action.APP_ABOUT)) {
            desktop.setAboutHandler(event -> showAboutDialog(application.activeParent()));
            log.debug("Installed desktop About handler");
        }
        if (desktop.isSupported(Desktop.Action.APP_OPEN_FILE)) {
            desktop.setOpenFileHandler(event -> {
                List<File> files = event.getFiles();
                log.info("Desktop asked to open {} file(s)", files.size());
                // Delivered on the EDT by the JDK, but posted rather than run
                // inline: openPath touches the window's models, and one place
                // where that is guaranteed to be on the EDT is cheaper than
                // depending on a JDK detail staying true.
                SwingUtilities.invokeLater(() -> {
                    for (File file : files) {
                        application.openPath(file.toPath());
                    }
                });
            });
            log.debug("Installed desktop Open File handler");
        }
        if (desktop.isSupported(Desktop.Action.APP_PREFERENCES)) {
            desktop.setPreferencesHandler(event ->
                    SwingUtilities.invokeLater(application::showPreferences));
            log.debug("Installed desktop Preferences handler");
        }
        if (desktop.isSupported(Desktop.Action.APP_QUIT_HANDLER)) {
            desktop.setQuitHandler((event, response) -> {
                // Dispose rather than exit: the window releases its sessions,
                // which removes the lock files that would otherwise make the
                // next launch think another process holds them.
                log.info("Desktop quit requested");
                application.requestQuit().whenComplete((approved, failure) -> {
                    if (failure != null) {
                        log.warn("Application windows did not close cleanly", failure);
                        response.cancelQuit();
                    } else if (Boolean.TRUE.equals(approved)) {
                        response.performQuit();
                    } else {
                        response.cancelQuit();
                    }
                });
            });
            log.debug("Installed desktop Quit handler");
        }
    }

    // Runs on the EDT: About events are dispatched there, and the dialog is pure UI.
    private static void showAboutDialog(Window parent) {
        String message = """
                %s
                Version %s

                Java %s (%s)
                %s %s (%s)""".formatted(
                AppInfo.NAME,
                AppInfo.VERSION,
                System.getProperty("java.version"),
                System.getProperty("java.vendor"),
                System.getProperty("os.name"),
                System.getProperty("os.version"),
                System.getProperty("os.arch"));
        JOptionPane.showMessageDialog(
                parent, message, "About " + AppInfo.NAME, JOptionPane.INFORMATION_MESSAGE);
    }
}
