package com.holtherndon.bazelviz.ui.capture;

import com.holtherndon.bazelviz.runner.plan.CapturePreset;

/** Console choices; a diagnostic is a reviewed workflow, not a new capture preset. */
public enum LaunchMode {
  LIVE_ESSENTIALS(CapturePreset.LIVE_ESSENTIALS),
  PERFORMANCE_DIAGNOSTICS(CapturePreset.PERFORMANCE_DIAGNOSTICS),
  FULL_GRAPH_DIAGNOSTICS(CapturePreset.FULL_GRAPH_DIAGNOSTICS),
  HERMETICITY_DIAGNOSTIC(CapturePreset.PERFORMANCE_DIAGNOSTICS);

  private final CapturePreset preset;

  LaunchMode(CapturePreset preset) {
    this.preset = preset;
  }

  /** Evidence requested by this choice; the caller separately dispatches diagnostic workflows. */
  public CapturePreset preset() {
    return preset;
  }

  public String displayName() {
    return this == HERMETICITY_DIAGNOSTIC
        ? "Hermeticity diagnostic"
        : preset.displayName() + (this == PERFORMANCE_DIAGNOSTICS ? " (recommended)" : "");
  }

  public String tooltip() {
    if (this == HERMETICITY_DIAGNOSTIC) {
      return "Review the diagnostic commands, run two clean/build cycles in a private output base,"
          + " then open their linked comparison automatically. Requires Bazel 9.2.0 and a"
          + " build command; ignores bazelrc files and disables build-cache reuse. Matching"
          + " builds do not prove hermeticity.";
    }
    String explanation =
        switch (this) {
          case LIVE_ESSENTIALS ->
              "Live BEP and console; no execution log, timing trace, or Starlark CPU profile.";
          case PERFORMANCE_DIAGNOSTICS ->
              "Recommended: adds the execution log, timing trace, and Starlark CPU profile"
                  + " to live BEP and console.";
          case FULL_GRAPH_DIAGNOSTICS ->
              "Currently the same sources as Performance Diagnostics; it adds no graph capture"
                  + " today.";
          case HERMETICITY_DIAGNOSTIC -> throw new AssertionError("handled above");
        };
    return explanation
        + " After every build, BBV runs aquery and cquery and indexes both graphs,"
        + " using extra disk, CPU, and indexing time.";
  }

  /** Persisted capture settings can restore only an ordinary build choice. */
  static LaunchMode fromPreset(CapturePreset preset) {
    if (preset == CapturePreset.LIVE_ESSENTIALS) {
      return LIVE_ESSENTIALS;
    }
    if (preset == CapturePreset.FULL_GRAPH_DIAGNOSTICS) {
      return FULL_GRAPH_DIAGNOSTICS;
    }
    return PERFORMANCE_DIAGNOSTICS;
  }
}
