# Changelog

Notable user-visible changes are recorded here.

## 0.1.0 — 2026-09-04

Source candidate for the first local-first Bazel Build Visualizer release.

- Captures local builds through a loopback BES and Linux builds through
  user-selected SSH Workspaces.
- Imports binary and JSON BEP files and portable `.bviz` archives.
- Provides paged tables, timelines, dependency trees and graphs, critical-path
  analysis, Starlark CPU views, findings, and read-only SQL queries.
- Bounds mapped graph indexes, retained graph renderings, and graph scratch
  under one per-session budget; oversized work is refused with its accounting.
- Opens a selected repository file with Enter and closes only the active editor
  with the platform menu shortcut plus W (Command+W on macOS).
- Preserves raw capture data and reports unavailable or partial results.
- Packages an Apple Silicon macOS app with product version 0.1.0.

This is not yet a verified release artifact. Known limitations are listed in
[README.md](README.md#known-release-limitations). In particular, three late
input/inspection hardening batches remain blocked and unmerged, saved Query
library updates are not crash-atomic, Intel macOS packaging is unsupported,
the synthetic burst-rate objective is unmet, and the manual signing,
notarization, stapling, packaged-app, fuzzing, advisory, and supervised
real-Bazel gates have not all passed.
