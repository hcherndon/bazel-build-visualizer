package com.holtherndon.bazelviz.app.dirs;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Resolves the per-platform application-support root and the plan-mandated subdirectories beneath
 * it (plan section 10.1):
 *
 * <ul>
 *   <li>macOS: {@code ~/Library/Application Support/BazelBuildVisualizer}
 *   <li>Linux: {@code $XDG_DATA_HOME/bazel-build-visualizer}, falling back to {@code
 *       ~/.local/share/bazel-build-visualizer}
 *   <li>Windows: {@code %APPDATA%\BazelBuildVisualizer}, falling back to {@code
 *       ~\AppData\Roaming\BazelBuildVisualizer}
 * </ul>
 *
 * <p>The system property {@code bbv.appdir} overrides the resolved root on every platform; tests
 * use it to avoid touching the real home directory.
 *
 * <p>Nothing is created at class load or construction time — each directory is created lazily by
 * its accessor via {@link Files#createDirectories}, so merely resolving paths never mutates the
 * filesystem.
 */
public final class AppDirectories {

  /** System property that overrides the application-support root on all platforms. */
  public static final String OVERRIDE_PROPERTY = "bbv.appdir";

  private final Path root;

  /**
   * @param override explicit root override (wins on all platforms), or null to resolve per platform
   * @param osName the value of the {@code os.name} system property
   * @param env the process environment (only XDG_DATA_HOME / APPDATA are read)
   * @param home the user home directory
   */
  public AppDirectories(Path override, String osName, Map<String, String> env, Path home) {
    this.root = resolveRoot(override, osName, env, home);
  }

  /** Wires the real system property, os name, environment, and home directory. */
  public static AppDirectories systemDefault() {
    String override = System.getProperty(OVERRIDE_PROPERTY);
    return new AppDirectories(
        override == null || override.isBlank() ? null : Path.of(override),
        System.getProperty("os.name", ""),
        System.getenv(),
        Path.of(System.getProperty("user.home")));
  }

  private static Path resolveRoot(
      Path override, String osName, Map<String, String> env, Path home) {
    if (override != null) {
      return override;
    }
    String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
    if (os.contains("mac")) {
      return home.resolve("Library").resolve("Application Support").resolve("BazelBuildVisualizer");
    }
    if (os.contains("win")) {
      String appData = env.get("APPDATA");
      Path base =
          appData == null || appData.isBlank()
              ? home.resolve("AppData").resolve("Roaming")
              : Path.of(appData);
      return base.resolve("BazelBuildVisualizer");
    }
    // Linux and anything else: XDG Base Directory spec.
    String xdgDataHome = env.get("XDG_DATA_HOME");
    Path base =
        xdgDataHome == null || xdgDataHome.isBlank()
            ? home.resolve(".local").resolve("share")
            : Path.of(xdgDataHome);
    return base.resolve("bazel-build-visualizer");
  }

  /** The resolved root path. Does not create it; use {@link #root()} for that. */
  public Path rootPath() {
    return root;
  }

  /** The application-support root, created on first access. */
  public Path root() {
    return ensure(root);
  }

  /** Session catalog database directory, created on first access. */
  public Path catalog() {
    return ensure(root.resolve("catalog"));
  }

  /** Managed (app-launched) session storage, created on first access. */
  public Path managedSessions() {
    return ensure(root.resolve("managed-sessions"));
  }

  /** Cache for imported external BEP files, created on first access. */
  public Path importCache() {
    return ensure(root.resolve("import-cache"));
  }

  /** Scratch space for in-flight captures, created on first access. */
  public Path temporaryCaptures() {
    return ensure(root.resolve("temporary-captures"));
  }

  /** Application settings directory, created on first access. */
  public Path settings() {
    return ensure(root.resolve("settings"));
  }

  /** Log file directory, created on first access. */
  public Path logs() {
    return ensure(root.resolve("logs"));
  }

  /** Creates every plan-mandated subdirectory and returns them. */
  public List<Path> ensureAll() {
    return List.of(
        catalog(), managedSessions(), importCache(), temporaryCaptures(), settings(), logs());
  }

  private static Path ensure(Path dir) {
    try {
      return Files.createDirectories(dir);
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot create application directory " + dir, e);
    }
  }
}
