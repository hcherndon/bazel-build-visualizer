package com.holtherndon.bazelviz.ui.theme;

import com.formdev.flatlaf.FlatDarculaLaf;
import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatIntelliJLaf;
import com.formdev.flatlaf.FlatLightLaf;
import com.formdev.flatlaf.themes.FlatMacDarkLaf;
import com.formdev.flatlaf.themes.FlatMacLightLaf;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;
import javax.swing.LookAndFeel;

/** One bundled application theme with a stable persisted identifier. */
public enum AppTheme {
  LIGHT("light", "Light", "A clean, neutral light theme.", false, FlatLightLaf::new),
  DARK("dark", "Dark", "A neutral dark theme for low-light work.", true, FlatDarkLaf::new),
  INTELLIJ_LIGHT(
      "intellij-light",
      "IntelliJ Light",
      "A compact, higher-contrast light theme.",
      false,
      FlatIntelliJLaf::new),
  DARCULA("darcula", "Darcula", "A warmer, higher-contrast dark theme.", true, FlatDarculaLaf::new),
  MACOS_LIGHT(
      "macos-light",
      "macOS Light",
      "A light theme styled after macOS controls.",
      false,
      FlatMacLightLaf::new),
  MACOS_DARK(
      "macos-dark",
      "macOS Dark",
      "A dark theme styled after macOS controls.",
      true,
      FlatMacDarkLaf::new);

  private final String id;
  private final String displayName;
  private final String description;
  private final boolean dark;
  private final Supplier<? extends LookAndFeel> lookAndFeel;

  AppTheme(
      String id,
      String displayName,
      String description,
      boolean dark,
      Supplier<? extends LookAndFeel> lookAndFeel) {
    this.id = id;
    this.displayName = displayName;
    this.description = description;
    this.dark = dark;
    this.lookAndFeel = lookAndFeel;
  }

  /** Stable value written to settings and accepted by {@code -Dbbv.theme}. */
  public String id() {
    return id;
  }

  /** Human-readable menu label. */
  public String displayName() {
    return displayName;
  }

  /** Short explanation shown by the theme menu and accessibility tools. */
  public String description() {
    return description;
  }

  public boolean isDark() {
    return dark;
  }

  LookAndFeel createLookAndFeel() {
    return lookAndFeel.get();
  }

  /** The existing light appearance remains the first-run and recovery default. */
  public static AppTheme defaultTheme() {
    return LIGHT;
  }

  /** Resolves a stable id case-insensitively; blank and unknown ids are absent. */
  public static Optional<AppTheme> fromId(String id) {
    if (id == null || id.isBlank()) {
      return Optional.empty();
    }
    String normalized = id.strip().toLowerCase(Locale.ROOT);
    for (AppTheme theme : values()) {
      if (theme.id.equals(normalized)) {
        return Optional.of(theme);
      }
    }
    return Optional.empty();
  }

  @Override
  public String toString() {
    return displayName;
  }
}
