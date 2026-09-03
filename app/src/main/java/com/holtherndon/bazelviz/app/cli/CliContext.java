package com.holtherndon.bazelviz.app.cli;

import com.holtherndon.bazelviz.app.dirs.AppDirectories;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Everything a subcommand needs from the outside world: where to write, what version to record in a
 * manifest, where sessions live by default, and how to register a shutdown hook.
 *
 * <p>All four are injected rather than reached for statically so the commands can be driven
 * in-process by a test with captured streams, a temporary sessions root and a fake hook registry. A
 * command that called {@code System.out} directly could only be tested by shelling out, and a test
 * that shells out cannot see an exception or share a coverage report.
 *
 * <p>The default sessions root is a {@link Supplier} because resolving it creates directories under
 * the user's home. A run that passes {@code --sessions-root} must not leave an application-support
 * tree behind as a side effect of parsing its own arguments.
 */
final class CliContext {

  private final PrintStream out;
  private final PrintStream err;
  private final String appVersion;
  private final Supplier<Path> defaultSessionsRoot;
  private final CancellationGuard.HookRegistry hooks;
  private final boolean interactive;

  CliContext(
      PrintStream out,
      PrintStream err,
      String appVersion,
      Supplier<Path> defaultSessionsRoot,
      CancellationGuard.HookRegistry hooks,
      boolean interactive) {
    this.out = Objects.requireNonNull(out, "out");
    this.err = Objects.requireNonNull(err, "err");
    this.appVersion = Objects.requireNonNull(appVersion, "appVersion");
    this.defaultSessionsRoot = Objects.requireNonNull(defaultSessionsRoot, "defaultSessionsRoot");
    this.hooks = Objects.requireNonNull(hooks, "hooks");
    this.interactive = interactive;
  }

  /**
   * The context a real invocation uses: the JVM's streams and shutdown hooks, the application's
   * managed-sessions directory, and carriage-return progress only when stderr is attached to a
   * terminal.
   */
  static CliContext systemDefault(PrintStream out, PrintStream err, String appVersion) {
    return new CliContext(
        out,
        err,
        appVersion,
        () -> AppDirectories.systemDefault().managedSessions(),
        CancellationGuard.HookRegistry.jvm(),
        System.console() != null);
  }

  PrintStream out() {
    return out;
  }

  PrintStream err() {
    return err;
  }

  String appVersion() {
    return appVersion;
  }

  Path defaultSessionsRoot() {
    return defaultSessionsRoot.get();
  }

  CancellationGuard.HookRegistry hooks() {
    return hooks;
  }

  /** True when progress may redraw one line in place instead of scrolling. */
  boolean interactive() {
    return interactive;
  }
}
