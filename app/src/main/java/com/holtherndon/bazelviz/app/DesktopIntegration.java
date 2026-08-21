package com.holtherndon.bazelviz.app;

import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import java.awt.Window;
import javax.swing.JOptionPane;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Desktop-environment integration (plan 17.2). On macOS this replaces the
 * default AWT About dialog; every call is guarded by {@code isSupported}
 * checks so headless CI and platforms without the capability (Linux, older
 * Windows) are silent no-ops. Taskbar/dock icon is deliberately skipped in
 * Phase 0.
 */
final class DesktopIntegration {

    private static final Logger log = LoggerFactory.getLogger(DesktopIntegration.class);

    private DesktopIntegration() {}

    /** Installs the About handler if the platform supports it. Safe everywhere. */
    static void install(Window parent) {
        if (GraphicsEnvironment.isHeadless() || !Desktop.isDesktopSupported()) {
            return;
        }
        Desktop desktop = Desktop.getDesktop();
        if (desktop.isSupported(Desktop.Action.APP_ABOUT)) {
            desktop.setAboutHandler(event -> showAboutDialog(parent));
            log.debug("Installed desktop About handler");
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
