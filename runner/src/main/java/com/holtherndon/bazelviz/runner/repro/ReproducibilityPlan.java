package com.holtherndon.bazelviz.runner.repro;

import com.holtherndon.bazelviz.runner.caps.BazelCapabilities;
import com.holtherndon.bazelviz.runner.command.BazelCommand;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Pure, explicitly controlled repeat-build protocol. Constructing a plan executes nothing. */
public record ReproducibilityPlan(
    BazelCommand original,
    BazelCommand build,
    BazelCommand clean,
    BazelCommand outputBaseProbe,
    BazelCommand shutdown,
    String outputBase,
    List<Change> changes,
    List<String> blockers) {

  /** Every protocol flag is reviewed separately from ordinary capture instrumentation. */
  public record Change(String argument, String explanation) {}

  private static final Map<String, String> REQUIRED_BUILD_VALUES =
      Map.of(
          "disk_cache", "",
          "remote_cache", "",
          "remote_executor", "",
          "symlink_prefix", "/",
          "jobs", "2",
          "lockfile_mode", "off");

  public ReproducibilityPlan {
    Objects.requireNonNull(original, "original");
    Objects.requireNonNull(build, "build");
    Objects.requireNonNull(clean, "clean");
    Objects.requireNonNull(outputBaseProbe, "outputBaseProbe");
    Objects.requireNonNull(shutdown, "shutdown");
    Objects.requireNonNull(outputBase, "outputBase");
    changes = List.copyOf(changes);
    blockers = List.copyOf(blockers);
  }

  /**
   * A controlled audit deliberately ignores rc files. This is not the workspace's ordinary
   * configured build, and a caller must obtain explicit approval of that distinction.
   */
  public static ReproducibilityPlan controlled(BazelCommand original, String outputBase) {
    Objects.requireNonNull(original, "original");
    requireOwnedPath(outputBase);
    List<String> blockers = new ArrayList<>();
    if (!original.command().equals("build")) {
      blockers.add("Repeat-build audits support build only, not test, run, or clean.");
    }
    if (original.shellMode()) {
      blockers.add("Shell-mode commands cannot be audited safely; use a command name or path.");
    }
    if (!original.startupArgs().isEmpty()) {
      blockers.add(
          "The controlled audit owns startup options. Remove explicit startup options before"
              + " reviewing the audit; they are not silently replaced.");
    }
    if (!original.argsAfterDoubleDash().isEmpty()) {
      blockers.add("Arguments after -- are not supported by the initial audit protocol.");
    }
    validateBuildOptions(original.commandArgs(), blockers);
    if (original.commandArgs().stream()
        .map(ReproducibilityPlan::optionName)
        .anyMatch(ReproducibilityPlan::isExecutionLogOutput)) {
      blockers.add(
          "The managed audit requires its own fresh compact execution log for each build."
              + " Remove explicit execution-log output flags before reviewing the audit.");
    }
    List<String> startup =
        List.of(
            "--ignore_all_rc_files",
            "--output_base=" + outputBase,
            "--host_jvm_args=-Xmx1g",
            "--max_idle_secs=15");
    List<Change> changes =
        List.of(
            new Change(
                startup.get(0),
                "Opt-in controlled configuration: ignore system, user and workspace rc files."
                    + " This can change toolchains, platforms and build behavior; this is not"
                    + " an audit of your usual rc-configured build."),
            new Change(startup.get(1), "Use this private, app-owned base at the same path twice."),
            new Change(startup.get(2), "Cap the private Bazel server at 1 GiB of Java heap."),
            new Change(startup.get(3), "Stop an idle private server after 15 seconds."),
            new Change("--disk_cache=", "Disable disk action-cache reads and writes."),
            new Change("--remote_cache=", "Disable the remote action-cache endpoint."),
            new Change("--remote_executor=", "Execute on this machine, not a build cluster."),
            new Change("--symlink_prefix=/", "Do not create or remove ordinary bazel-* links."),
            new Change("--jobs=2", "Limit the audit to two concurrent jobs."),
            new Change("--lockfile_mode=off", "Do not update MODULE.bazel.lock during the audit."));
    List<String> buildArgs = new ArrayList<>(original.commandArgs());
    for (Change change : changes.subList(startup.size(), changes.size())) {
      String name = optionName(change.argument());
      if (original.commandArgs().stream().noneMatch(arg -> optionName(arg).equals(name))) {
        buildArgs.add(change.argument());
      }
    }
    BazelCommand build = original.toBuilder().startupArgs(startup).commandArgs(buildArgs).build();
    BazelCommand.Builder helper =
        build.toBuilder().commandArgs(List.of()).targets(List.of()).argsAfterDoubleDash(List.of());
    return new ReproducibilityPlan(
        original,
        build,
        helper.command("clean").commandArgs(List.of("--symlink_prefix=/")).build(),
        helper.command("info").commandArgs(List.of()).targets(List.of("output_base")).build(),
        helper.command("shutdown").targets(List.of()).build(),
        outputBase,
        changes,
        blockers);
  }

  /** Supported release families still require observed flags and per-operation safety checks. */
  public List<String> capabilityBlockers(BazelCapabilities capabilities) {
    List<String> result = new ArrayList<>(blockers);
    if (!supportsVersion(capabilities.bazelVersion().orElse(""))) {
      result.add(
          "The managed repeat-build protocol supports Bazel 9.2.0 and release versions 7.4.x;"
              + " prereleases, forks and other versions are not supported.");
    }
    for (String name : REQUIRED_BUILD_VALUES.keySet()) {
      if (capabilities.flag(name).filter(flag -> flag.appliesTo("build")).isEmpty()) {
        result.add("The selected Bazel did not confirm support for --" + name + ".");
      }
    }
    return List.copyOf(result);
  }

  public static boolean supportsVersion(String version) {
    return "9.2.0".equals(version) || allowsCaptureBoundIdentity(version);
  }

  /** Only this supported release family may use capture-bound evidence without an embedded ID. */
  public static boolean allowsCaptureBoundIdentity(String version) {
    return version != null && version.matches("7\\.4\\.(0|[1-9][0-9]*)");
  }

  private static boolean isExecutionLogOutput(String option) {
    return Set.of(
            "execution_log_compact_file", "experimental_execution_log_compact_file",
            "execution_log_binary_file", "execution_log_json_file")
        .contains(option);
  }

  public boolean canLaunch() {
    return blockers.isEmpty();
  }

  /** Replanning capture instrumentation must not alter the audit's isolation contract. */
  public List<String> effectiveBlockers(BazelCommand effective) {
    List<String> result = new ArrayList<>();
    if (!effective.startupArgs().equals(build.startupArgs())
        || !effective.workingDirectory().equals(build.workingDirectory())
        || !effective.command().equals("build")
        || effective.shellMode()) {
      result.add(
          "Capture replanning changed the controlled audit's startup, directory or command.");
    }
    if (!effective.executable().equals(build.executable())
        || !effective.targets().equals(build.targets())
        || !effective.environmentOverrides().equals(build.environmentOverrides())
        || effective.inheritance() != build.inheritance()
        || !effective.argsAfterDoubleDash().equals(build.argsAfterDoubleDash())) {
      result.add(
          "Capture replanning changed the reviewed executable, targets, environment or target"
              + " arguments.");
    }
    validateBuildOptions(effective.commandArgs(), result);
    for (String name : REQUIRED_BUILD_VALUES.keySet()) {
      if (effective.commandArgs().stream().noneMatch(arg -> optionName(arg).equals(name))) {
        result.add("The required audit flag --" + name + " was removed.");
      }
    }
    return List.copyOf(result);
  }

  private static void validateBuildOptions(List<String> args, List<String> blockers) {
    for (int index = 0; index < args.size(); index++) {
      String arg = args.get(index);
      if (!arg.startsWith("--")) {
        continue;
      }
      String name = optionName(arg);
      int equals = arg.indexOf('=');
      String value =
          equals >= 0
              ? arg.substring(equals + 1)
              : index + 1 < args.size() ? args.get(index + 1) : "";
      if (REQUIRED_BUILD_VALUES.containsKey(name)
          && !REQUIRED_BUILD_VALUES.get(name).equals(value)) {
        blockers.add("Explicit --" + name + " conflicts with the controlled audit protocol.");
      }
      if (name.equals("config")) {
        blockers.add(
            "--config requires rc files, which this opt-in controlled audit does not read.");
      }
      if (Set.of("spawn_strategy", "strategy", "strategy_regexp").contains(name)
          && (value.contains("remote") || value.contains("dynamic"))) {
        blockers.add("Remote/dynamic execution strategies are outside the initial audit scope.");
      }
      if (name.equals("experimental_spawn_scheduler")
          || name.equals("experimental_convenience_symlinks")) {
        blockers.add("Explicit --" + name + " is not supported by the controlled audit protocol.");
      }
    }
  }

  private static String optionName(String arg) {
    if (!arg.startsWith("--")) {
      return "";
    }
    int equals = arg.indexOf('=');
    return arg.substring(2, equals < 0 ? arg.length() : equals);
  }

  private static void requireOwnedPath(String path) {
    Objects.requireNonNull(path, "outputBase");
    if (!path.startsWith("/")
        || !path.endsWith("/output-base")
        || path.contains("/../")
        || path.contains("/./")
        || path.indexOf('\0') >= 0
        || path.indexOf('\n') >= 0) {
      throw new IllegalArgumentException("an audit needs an absolute private output-base path");
    }
  }
}
