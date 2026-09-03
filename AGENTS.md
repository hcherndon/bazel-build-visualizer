# Bazel Build Visualizer instructions

## Source of truth

- Start with `README.md`, then read the architecture/status/ADR documents relevant to the task.
- `docs/product-plan.md` section 26 is the standing execution contract, with accepted ADRs taking precedence over historical plan text.
- ADR-009 supersedes ADR-003 and the Gradle command still preserved in the historical product plan. This repository is Bazel-only.
- Keep `docs/implementation-status.md` current when behavior or project status changes. Add or update an ADR before changing a fixed architectural decision.

## Verification and resource safety

- Use scoped Bazel targets while iterating. The default full gate after a meaningful code or build change is:

  ```bash
  bazel build //...
  bazel test //... --test_tag_filters=-bazel-sweep
  ```

- Never run the `bazel-sweep` suite casually, remove its default tag filter, or invoke `//capture-bes:BazelVersionMatrixTest` with an empty filter. It starts multiple Bazel servers and has crashed the development machine. Preserve the repository's Bazel memory caps and reduce concurrency further if memory pressure appears.
- Add tests for every parser, metric, migration, and graph algorithm change. When Bazel behavior is uncertain, prove it with a real Bazel fixture before encoding the assumption.
- Any new named limit constant must be documented in `docs/limits.md` with its fully qualified name and default; `LimitsDocTest` enforces this.
- Keep `docs/performance.md` and `docs/bazel-compatibility.md` current when changes affect those claims.

## Product invariants

- Preserve raw source data before deriving normalized data. Never execute commands embedded in imported files.
- Never block the Swing EDT with I/O or computation.
- Prefer bounded-memory streaming algorithms. Never retain all events or edges as Java object graphs.
- Never use an ORM. UI components depend on service interfaces, not SQLite implementation classes.
- Unknown or unavailable is not zero. Never silently truncate, sample, drop, or override data; expose exact limits, known totals, and partial-result status.
- Never claim a graph or total is complete unless its source and correlation checks support that claim. Never present a derived finding as causal without explicit evidence.
- New third-party dependencies require license review, maintenance assessment, performance justification, and lockfile updates. Avoid adding dependencies when existing code or the JDK is sufficient.
