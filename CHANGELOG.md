# Changelog

Notable user-visible changes are recorded here.

## 0.1.0 — 2026-09-04

First release of the local-first Bazel Build Visualizer.

- Captures local builds through a loopback BES and Linux builds through
  user-selected SSH Workspaces.
- Imports binary and JSON BEP files and portable `.bviz` archives.
- Provides paged tables, timelines, dependency trees and graphs, critical-path
  analysis, Starlark CPU views, findings, and read-only SQL queries.
- Preserves raw capture data and reports unavailable or partial results.
- Packages an Apple Silicon macOS app with product version 0.1.0.

Known limitations are listed in [README.md](README.md#known-release-limitations).
In particular, Intel macOS packaging is unsupported, the synthetic burst-rate
objective is unmet, security fuzzing and advisory scanning are absent, and
ordinary CI omits native packaging and host-state real-Bazel coverage.
