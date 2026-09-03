package com.holtherndon.bazelviz.ui.theme;

import com.formdev.flatlaf.FlatLaf;
import java.util.Objects;
import javax.swing.SwingUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Central look-and-feel setup so no other class installs FlatLaf directly. */
public final class Themes {

    private static final Logger log = LoggerFactory.getLogger(Themes.class);
    private static volatile AppTheme current = AppTheme.defaultTheme();

    private Themes() {}

    /**
     * Installs a bundled theme and refreshes every open Swing and syntax-text window.
     * Must run on the EDT.
     */
    public static void install(AppTheme theme) {
        requireEdt();
        Objects.requireNonNull(theme, "theme");
        if (!FlatLaf.setup(theme.createLookAndFeel())) {
            throw new IllegalStateException("Could not install the " + theme.displayName()
                    + " theme");
        }
        // From here the process-wide look and feel has changed. Record that
        // fact before best-effort refreshes so retries cannot be suppressed by
        // stale state when one unusual component rejects updateUI().
        current = theme;
        try {
            FlatLaf.updateUI();
        } catch (RuntimeException refreshFailure) {
            log.warn("some Swing components did not refresh for theme {}",
                    theme.id(), refreshFailure);
        }
        try {
            SyntaxTextTheme.refreshOpenWindows();
        } catch (RuntimeException refreshFailure) {
            log.warn("some syntax text components did not refresh for theme {}",
                    theme.id(), refreshFailure);
        }
    }

    /** Installs the first-run light theme. */
    public static void installDefault() {
        install(AppTheme.defaultTheme());
    }

    /** Compatibility shortcut retained for existing tests and {@code bbv.theme=dark}. */
    public static void installDark() {
        install(AppTheme.DARK);
    }

    public static AppTheme current() {
        return current;
    }

    /** A recognized process override wins; blank or invalid input keeps the saved theme. */
    public static AppTheme startupTheme(String processOverride, AppTheme saved) {
        Objects.requireNonNull(saved, "saved");
        return AppTheme.fromId(processOverride).orElse(saved);
    }

    private static void requireEdt() {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("look and feel changes must run on the EDT");
        }
    }
}
