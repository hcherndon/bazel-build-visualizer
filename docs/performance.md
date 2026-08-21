# Performance

Performance is a feature gate, not an aspiration. Numbers in this file are
only ever *measured* numbers — never estimates; a row with no measurement
says so explicitly. The plan's own framing (section 20.2) applies: these are
benchmark targets, not guarantees.

macOS on Apple Silicon is the platform the objectives are stated against
(plan 20.2). Linux is a portability target and CI runs `check` there, but no
performance objective is gated on Linux numbers.

## Benchmark tiers (plan 20.1)

Authoritative constants live in
`test-support` `SyntheticScale`; this table mirrors them.

| Tier | Actions | Events | Edges | Assumed machine |
|---|---:|---:|---:|---|
| Tier 1 | 100 thousand | 1 million | 1 million | 8 GB RAM |
| Tier 2 | 1 million | 10 million | 20 million | 16 GB RAM |
| Tier 3 | 5 million | 50 million | 100 million | 32 GB RAM |

All benchmark/spike data comes from the deterministic synthetic generators
(`SyntheticActionGenerator`, `SyntheticEdges`) — O(1) random access, nothing
materialized, identical across machines for a fixed seed.

## Performance objectives (plan 20.2)

These are the plan's twelve initial objectives, quoted from
`docs/product-plan.md` section 20.2, on "a representative Apple Silicon Mac
with SSD". The plan is explicit that they are **benchmark targets, not
unsupported guarantees**.

Most of them cannot be measured until the subsystem they describe exists.
The "Measurable from" column names the phase that makes each one real; only
the rows marked Phase 0 are in scope for the Phase 0 exit criteria.

| # | Objective | Measurable from | Measured |
|---|---|---|---|
| 1 | Raw capture path sustains 100,000 small synthetic events per second for burst tests without loss | Phase 2 | not yet measurable |
| 2 | Normalization sustains at least 25,000 representative events per second | Phase 3 | not yet measurable |
| 3 | Capture remains correct if normalization temporarily falls behind | Phase 2 | not yet measurable |
| 4 | Live UI updates at least four times per second under ordinary load | Phase 3 | not yet measurable |
| 5 | Panning and zooming aggregate timeline/graph views targets 30 frames per second | **Phase 0** (spike) | **met** — timeline p95 0.79–0.96 ms, graph p95 0.81–1.22 ms vs 33 ms |
| 6 | A cached action-table page appears within 100 milliseconds | **Phase 0** (spike) | **met** — table cache max 23.6 µs; SQLite keyset p95 0.63 ms |
| 7 | An uncached indexed page normally appears within 500 milliseconds | **Phase 0** (spike) | **met** — SQLite OFFSET max 23.1 ms at 2M rows (but see keyset finding) |
| 8 | Opening an already indexed Tier 3 session shows its overview within five seconds without loading all actions | Phase 3 | not yet measurable |
| 9 | Application-managed heap remains below 4 GB for Tier 3 under normal aggregate viewing | Phase 6/7 (partial signal in Phase 0 spikes) | partial — see spike retained-bytes figures |
| 10 | No routine EDT pause exceeds 100 milliseconds | Phase 3 | not yet measurable |
| 11 | Long queries are cancellable | Phase 3 | not yet measurable |
| 12 | Session finalization can resume after application restart | Phase 1 | not yet measurable |

### What the Phase 0 spikes do and do not prove

The spikes measure the *rendering and access architecture* against objectives
5–7 using synthetic in-process data. They deliberately do not model the
production data path: the table spike's rows come from an O(1) arithmetic
generator rather than SQLite, and the timeline/graph spikes paint from an
already-built in-memory index. Read the numbers as "the chosen Swing/Java2D
approach has adequate headroom", not as end-to-end product latency. The
SQLite spike is the one that exercises a real storage path, and its OFFSET-vs-
keyset result is the load-bearing finding for the actions table.

## Phase 0 spike results

**Measurement environment.** Apple M4 Pro (10 performance + 4 efficiency
cores), 48 GB RAM, macOS 26.6.2 (arm64), Temurin 21.0.7+6-LTS (the
Gradle-provisioned toolchain — confirm with `./gradlew
:benchmarks:printSpikeJvm`), spike JVMs at `-Xmx4g`, synthetic seed 42.
Measured 2026-08-21. These are single-run figures from one machine, not a
regression baseline with variance bounds; treat a later run within the same
order of magnitude as agreement.

### Paged table at 50 million rows (`runTableSpike --offscreen`)

| Measurement | Result | Budget |
|---|---|---|
| Cached page access (1000 reads) | mean 0.49 µs, max 23.6 µs | < 100 ms (objective 6) |
| Uncached page fetch (220 fetches, 200 random viewport jumps) | mean 71.8 µs, p50 65.7 µs, p95 98.8 µs, p99 120.3 µs, max 753 µs | < 500 ms (objective 7) |
| Page cache behaviour | 1000 hits / 8000 misses / 156 evictions, bounded at 64 pages | must stay bounded |

**Swing geometry finding.** 50M rows at `rowHeight` 20 needs a 1,000,000,000 px
preferred height — 46.6% of `Integer.MAX_VALUE`, confirmed against a real
headless `JTable`. The int pixel space overflows near **107.4M rows**, so the
50M target fits `JScrollPane` but 100M+ rows requires the custom logical
scrollbar that plan section 17.5 anticipates. This is the single most
important structural result from the table spike.

Caveat: rows come from the arithmetic synthetic generator, so these latencies
measure the paging machinery, not SQLite. The SQLite figures below are the
realistic page-fetch cost.

### Timeline (`runTimelineSpike --offscreen [--tier3]`)

| Tier | LOD build (streamed) | Frame time (300 frames, 1600x900) |
|---|---|---|
| Tier 2 — 1M spans | 0.095 s, 5 levels (1 ms … 256 ms bins) | mean 0.60 ms, p95 0.96 ms, p99 1.47 ms |
| Tier 3 — 5M spans | 0.798 s, 7 levels (1 ms … 4096 ms bins) | mean 0.56 ms, p95 0.79 ms, p99 0.91 ms |

Against the 33 ms / 30 FPS target (objective 5) this is a ~34x margin. Frame
cost is near-constant across tiers by construction: level selection caps
painted bins near 2048 regardless of zoom, which is the property the design
depends on.

### Graph CSR + aggregate painting (`runGraphSpike --offscreen [--tier2]`)

| Tier | CSR build | Reverse | BFS throughput | Retained (both directions) | Frame p95 |
|---|---|---|---|---|---|
| Tier 1 — 100k nodes / 1.0M edges | 25.3 ms | 5.7 ms | 48.6M nodes/s | 9.6 MB (vs 38.4 MB object-per-edge) | 0.81 ms |
| Tier 2 — 1M nodes / 20.0M edges | 295.0 ms | 41.0 ms | 32.5M nodes/s | 176 MB (vs 704 MB object-per-edge) | 1.22 ms |

The 8x memory advantage over an object-per-edge representation is the
quantitative justification for ADR-006/007. Extrapolating Tier 2's 176 MB
linearly, Tier 3 (100M edges) lands near 880 MB for both directions — inside
the 4 GB heap objective, but close enough that the memory-mapped CSR files of
plan 13.2 remain necessary rather than optional.

Caveat: the synthetic edge generator biases producers to within 4096 indices
of the consumer, which equals the spike's cluster size, so only ~c-1
inter-cluster pairs exist. A real action graph will have far denser cluster
connectivity, making the painting numbers optimistic. The spike also uses a
dense `int[c][c]` weight matrix that the real implementation must replace with
sparse weights.

### SQLite ingestion and paging (`runSqlPagingSpike --rows=2000000`)

| Measurement | Result |
|---|---|
| Batched insert (batch 20,000, WAL, single writer) | 2,000,000 rows in 1.2 s = 1,684,012 rows/s |
| Index creation + `ANALYZE` after load | 1.6 s |
| OFFSET page — `ORDER BY duration DESC LIMIT 100` | mean 11.7 ms, p95 21.3 ms, max 23.1 ms |
| Keyset page — `(duration, id)` anchor | mean 0.218 ms, p95 0.626 ms, max 3.0 ms |
| Point lookup by id | mean 0.006 ms, max 0.012 ms |

**Load-bearing finding: use keyset pagination, not OFFSET.** OFFSET cost grows
linearly with row count (p50 2.6 ms at 500k rows, 11.8 ms at 2M) while keyset
stays flat at ~0.1–0.2 ms. Extrapolated, OFFSET threatens the 100 ms cached-page
objective somewhere around Tier 3 scale, so the actions table (plan 17.4/17.5)
should standardize on keyset anchors from the start.

The spike cannot portably drop the OS page cache, so "cached" is read as p50
and "uncached" as the worst observed sample. Both interpretations pass with
large margins, but neither is a true cold-cache measurement.

## Running the spikes

Each spike defaults to an interactive window; `--offscreen` paints to a
BufferedImage and prints frame statistics for CI/headless use.

```
./gradlew :benchmarks:runTableSpike     --args="--offscreen"
./gradlew :benchmarks:runTimelineSpike  --args="--offscreen"
./gradlew :benchmarks:runGraphSpike     --args="--offscreen"
./gradlew :benchmarks:runSqlPagingSpike --args="--offscreen"
```

Spike JVMs run with `-Xmx4g` (set in `benchmarks/build.gradle.kts`). Record
alongside every measurement: OS + version, CPU, RAM, JDK build, display
scale (for windowed runs), and the seed/tier used.

JMH microbenchmarks (allocation-sensitive inner loops) live in
`:benchmarks` under the `jmh` source set; run them with
`./gradlew :benchmarks:jmh`.

## Build performance

`gradle.properties` enables parallel execution, the build cache, and the
configuration cache (`-Xmx3g` daemon). If a change regresses configuration
time noticeably, treat it as a defect.
