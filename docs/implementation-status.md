# Implementation status

Last updated: 2026-08-21. This file states what exists in the tree, not what
is planned to exist. Update it in the same change that lands the work.

## Phases

Phase scopes are those of `docs/product-plan.md` section 24 and must not be
renumbered or re-scoped here.

| Phase | Scope | Status |
|---|---|---|
| 0 | Repository and architectural spikes | **In progress** (see checklist below) |
| 1 | Session, journal, and offline BEP import | Not started |
| 2 | Bazel launcher and embedded BES | Not started |
| 3 | Core target, action, test, and artifact normalization | Not started |
| 4 | Execution-log and profile enrichment | Not started |
| 5 | `aquery`, `cquery`, and graph construction | Not started |
| 6 | Timeline | Not started |
| 7 | Graph visualization | Not started |
| 8 | Metrics and findings | Not started |
| 9 | Session export, redaction, and macOS packaging | Not started |
| 10 | Scale hardening and compatibility release gate | Not started |

## Phase 0 checklist (as of 2026-08-21)

| Task | Status |
|---|---|
| Repo + Gradle multi-module skeleton (15 modules + build-logic, wrapper 9.7.1) | Done |
| Convention plugins (`bbv.java-common` / `-library` / `-application`), reproducible archives, dependency locking wiring | Done |
| Java 21 toolchain auto-provisioning (foojay resolver) | Done |
| Logging (slf4j everywhere, logback in `app`, uncaught-handler in `Main`) | Done |
| FlatLaf window shell (`app` `Main`, `ui-swing` `MainWindow`/`Themes`, smoke mode) | Done |
| Synthetic data generators (`test-support`: `SyntheticActionGenerator`, `SyntheticEdges`, Tier 1-3 scales, O(1) access) | Done |
| Bazel BEP/BES proto vendoring + Java/gRPC codegen in `proto` | Done — Bazel 9.2.0 and googleapis pinned, provenance in `proto/PROTO_SOURCES.md`, wire round-trip covered by `BepProtoSmokeTest` |
| Rendering spikes: table / timeline / graph (`:benchmarks:runTableSpike` etc., `--offscreen` mode) | Done — all pass, results in docs/performance.md |
| SQLite paging spike (`:benchmarks:runSqlPagingSpike`) | Done — 1.68M rows/s insert; keyset vs OFFSET finding recorded |
| JMH microbenchmark harness in `benchmarks` | Done — `SyntheticGeneratorBench`, `SqliteInsertBench` compile; not attached to `check` (run `./gradlew :benchmarks:jmh`) |
| ADRs 001-007 | Done |
| CI (GitHub Actions, macOS 14 + Ubuntu, `check`) | Done |
| Approved plan committed as `docs/product-plan.md` | Done |
| Dependency lockfiles written | Done — `./gradlew resolveAndLockAll --write-locks --no-configuration-cache` regenerates them |
| Phase 0 benchmark results recorded in docs/performance.md | Done |

## Phase 0 audit

Phase 0 was reviewed by an adversarial audit (five lenses — EDT discipline,
memory/scale invariants, algorithmic correctness, storage and build config,
documentation truthfulness — with independent refuters per finding and a
completeness critic). 14 candidates, 11 refuted, and these fixed:

- **`.gitignore` silently excluded five vendored protos**, including
  `build_event_stream.proto`, because a bare `build/` matches at any depth
  and the Bazel proto paths contain a `build` component. A fresh clone could
  not compile `:proto`. Every lens missed it; the completeness critic found
  it by diffing the tree against the lens scopes. Ignore patterns are now
  anchored to module roots.
- **`BatchedInsert` committed failed batches.** Restoring auto-commit in
  `close()` commits the open transaction, so an aborted batch became durable
  while `rowCount()` reported zero. Now rolls back before rethrowing.
- **`PagedTableModel` swallowed fetch failures**, rendering them identically
  to still-loading cells and retrying forever. Failures are now visible,
  counted, and not auto-retried.
- **`TimelineLodIndex` capped bins by count, not bytes**, permitting a ~2.9 GB
  pyramid against the 4 GB heap objective. Cap is now byte-derived.
- **Spike exit codes were inconsistent**, so two of four reported a budget
  breach as a green build.

The last two code fixes were formally refuted as unreachable in Phase 0 —
correct today, since nothing yet wires a `RowSource` that can throw. Both
refutations conceded the analysis itself; the fixes were kept because Phase 3
wires exactly such a source, and inheriting a known latent defect costs more
than fixing it now.

## Phase 0 exit criteria (plan section 24)

| Criterion | Status |
|---|---|
| Application window launches on macOS | Met — `./gradlew :app:run` with `-Dbbv.smoke=true` |
| Synthetic paged table remains responsive | Met — cached access max 23.6 µs, uncached fetch p99 120 µs at 50M rows |
| Aggregate timeline can pan and zoom without loading individual spans | Met — LOD-only painting, p95 0.79 ms at Tier 3 |
| Chosen Swing approach meets initial spike thresholds, or an ADR documents a revised rendering strategy | Met — all four spikes pass with >25x margin; ADR-001 stands, no revision needed |
| `./gradlew check` succeeds | Met |

The one structural limit the spikes surfaced: `JScrollPane` int pixel geometry
overflows near 107.4M rows at `rowHeight` 20. The 50M-row target fits; the
custom logical scrollbar of plan 17.5 becomes mandatory beyond that. See
docs/performance.md.
