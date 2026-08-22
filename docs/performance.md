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
| 5 | Panning and zooming aggregate timeline/graph views targets 30 frames per second | **Phase 0** (spike) | **met** — timeline p95 0.88–0.94 ms, graph p95 0.87–1.28 ms vs 33 ms |
| 6 | A cached action-table page appears within 100 milliseconds | **Phase 0** (spike) | **met** — table cache max 25.0 µs; SQLite keyset p95 0.65 ms |
| 7 | An uncached indexed page normally appears within 500 milliseconds | **Phase 0** (spike) | **met** — SQLite OFFSET max 23.0 ms at 2M rows (but see keyset finding) |
| 8 | Opening an already indexed Tier 3 session shows its overview within five seconds without loading all actions | Phase 3 | not yet measurable |
| 9 | Application-managed heap remains below 4 GB for Tier 3 under normal aggregate viewing | Phase 6/7 (partial signal in Phase 0 spikes) | partial — see spike retained-bytes figures |
| 10 | No routine EDT pause exceeds 100 milliseconds | Phase 3 | not yet measurable |
| 11 | Long queries are cancellable | Phase 3 | not yet measurable |
| 12 | Session finalization can resume after application restart | **Phase 1** | **met** — an import interrupted at 145,000 of 300,000 events resumes to a state identical to a clean import; see docs/implementation-status.md |

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
cores), 48 GB RAM, macOS 26.6.2 (arm64), **Amazon Corretto 25.0.1+8-LTS**
(ADR-008's Java 25 baseline; confirm the exact JVM the spikes launch with
`./gradlew :benchmarks:printSpikeJvm`), spike JVMs at `-Xmx4g` with
`--enable-native-access=ALL-UNNAMED`, synthetic seed 42, default GC (G1),
default (non-compact) object headers, no AOT cache. Measured 2026-08-21.
These are single-run figures from one machine, not a regression baseline with
variance bounds; treat a later run within the same order of magnitude as
agreement.

**Java version note.** Every figure in this section was re-measured on Java 25
after the Java 21 → 25 toolchain upgrade. The earlier Java 21 (Temurin
21.0.7+6-LTS) figures have been replaced, not annotated, so no number in this
file is a Java 21 measurement. Every spike still returns PASS and every
conclusion is unchanged.

Do not read a Java 21 → 25 delta out of these numbers. An attempt to do so was
already withdrawn once: a single-run comparison appeared to show the table's
uncached-fetch mean improving from 71.8 to 55.5 µs, but three consecutive runs
land at 67.4–69.2 µs, so the "improvement" was run-to-run noise. Only the
compact-object-header comparison below was measured properly, with paired
repeated runs. Everything else here is a single run per configuration and is
reported only to show headroom against a budget, not to compare JDKs.

### Paged table at 50 million rows (`runTableSpike --offscreen`)

Three consecutive runs, reported as a range because a single run does not
reproduce to the precision a single figure implies:

| Measurement | Result (3 runs) | Budget |
|---|---|---|
| Cached page access (1000 reads) | mean 0.38–0.43 µs, max 23.6–32.7 µs | < 100 ms (objective 6) |
| Uncached page fetch (220 fetches, 200 random viewport jumps) | mean 67.4–69.2 µs, p50 62.4–63.5 µs, p95 94.1–96.1 µs, p99 114.7–150.1 µs, max 689–744 µs | < 500 ms (objective 7) |
| Page cache behaviour | 1000 hits / 8000 misses / 156 evictions, bounded at 64 pages | must stay bounded |

The p99 column is the noisiest (114.7–150.1 µs across runs), which is what a
220-sample tail looks like. Every figure sits three to four orders of
magnitude inside its budget, so the run-to-run spread changes no conclusion.

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
| Tier 2 — 1M spans | 0.099 s, 5 levels (1 ms … 256 ms bins) | mean 0.61 ms, p95 0.94 ms, p99 1.48 ms |
| Tier 3 — 5M spans | 0.822 s, 7 levels (1 ms … 4096 ms bins) | mean 0.62 ms, p95 0.88 ms, p99 0.98 ms |

Against the 33 ms / 30 FPS target (objective 5) this is a ~35x margin. Frame
cost is near-constant across tiers by construction: level selection caps
painted bins near 2048 regardless of zoom, which is the property the design
depends on.

### Graph CSR + aggregate painting (`runGraphSpike --offscreen [--tier2]`)

| Tier | CSR build | Reverse | BFS throughput | Retained (both directions) | Frame p95 |
|---|---|---|---|---|---|
| Tier 1 — 100k nodes / 1.0M edges | 26.9 ms | 4.8 ms | 48.6M nodes/s | 9.6 MB (vs 38.4 MB object-per-edge) | 0.87 ms |
| Tier 2 — 1M nodes / 20.0M edges | 311.0 ms | 49.4 ms | 29.6M nodes/s | 176 MB (vs 704 MB object-per-edge) | 1.28 ms |

The "Retained" column is an **analytic** figure, not a heap measurement:
`CsrGraph.retainedArrayBytes()` returns `8·offsets.length + 4·targets.length`,
and the object-per-edge column is a hand-written `32 B/edge + 64 B/node`
estimate. Both are therefore invariant to JVM object-layout flags — see
"Java 25 runtime options" below.

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
| Batched insert (batch 20,000, WAL, single writer) | 2,000,000 rows in 1.2 s = 1,710,315 rows/s |
| Index creation + `ANALYZE` after load | 1.6 s |
| OFFSET page — `ORDER BY duration DESC LIMIT 100` | mean 11.28 ms, p50 11.54 ms, p95 21.61 ms, max 23.03 ms |
| Keyset page — `(duration, id)` anchor | mean 0.229 ms, p50 0.117 ms, p95 0.649 ms, max 3.15 ms |
| Point lookup by id | mean 0.005 ms, max 0.016 ms |

**Load-bearing finding: use keyset pagination, not OFFSET.** OFFSET cost grows
with row count (p50 3.16 ms at 500k rows, 11.54 ms at 2M — measured with
`--rows=500000` and `--rows=2000000`) while keyset stays flat (p50 0.095 ms at
500k, 0.117 ms at 2M). Extrapolated, OFFSET threatens the 100 ms cached-page
objective somewhere around Tier 3 scale, so the actions table (plan 17.4/17.5)
should standardize on keyset anchors from the start.

The spike cannot portably drop the OS page cache, so "cached" is read as p50
and "uncached" as the worst observed sample. Both interpretations pass with
large margins, but neither is a true cold-cache measurement.

## Running the spikes

Only the timeline and graph spikes paint frames; the table spike measures
page-fetch latency and the SQL spike is console-only (it accepts
`--offscreen` for runner uniformity and ignores it). All four print a
PASS/FAIL verdict and exit nonzero on a budget breach in `--offscreen` mode,
so a regression fails the command rather than scrolling past.

```
./gradlew :benchmarks:runTableSpike     --args="--offscreen"
./gradlew :benchmarks:runTimelineSpike  --args="--offscreen"
./gradlew :benchmarks:runTimelineSpike  --args="--offscreen --tier3"
./gradlew :benchmarks:runGraphSpike     --args="--offscreen"
./gradlew :benchmarks:runGraphSpike     --args="--offscreen --tier2"
./gradlew :benchmarks:runSqlPagingSpike --args="--rows=2000000"
```

The tier flags matter: the timeline and graph spikes default to Tier 2 and
Tier 1 respectively, so the Tier 3 / Tier 2 figures in the results above come
from the flagged invocations.

Spike JVMs run with `-Xmx4g` (set in `benchmarks/build.gradle.kts`). Record
alongside every measurement: OS + version, CPU, RAM, JDK build, display
scale (for windowed runs), and the seed/tier used.

JMH microbenchmarks (allocation-sensitive inner loops) live in
`:benchmarks` under the `jmh` source set; run them with
`./gradlew :benchmarks:jmh`.

## Java 25 runtime options

**Status: evaluated, none adopted.** ADR-008 moved the toolchain from Java 21
to Java 25 LTS. Java 25 finalizes three runtime features that could plausibly
help the section 20.2 objectives, so each was measured rather than assumed.
The build ships **none** of these flags today; the Phase 0 figures above are
all default-JVM figures. This section records what was measured, how, and what
would have to change for adoption.

### How these were run

The spike `JavaExec` tasks fix their own `jvmArgs` in
`benchmarks/build.gradle.kts` and expose no property for adding more, and no
build file was modified for this evaluation. The flagged runs therefore
invoked the JDK directly on a hand-assembled runtime classpath: the
`build/classes/java/main` directories of `:benchmarks`, `:core-model`,
`:test-support`, `:ui-swing`, `:graph-core` and `:storage-sqlite`, plus the
five external runtime jars named in `benchmarks/gradle.lockfile`
(`logback-classic`, `logback-core`, `flatlaf`, `slf4j-api`, `sqlite-jdbc`)
resolved out of `~/.gradle/caches/modules-2`. After `./gradlew
:benchmarks:classes`:

```
JAVA=~/Library/Java/JavaVirtualMachines/corretto-25.0.1/Contents/Home/bin/java
$JAVA -Xmx4g --enable-native-access=ALL-UNNAMED [FLAGS] -cp "$CP" \
    com.holtherndon.bazelviz.benchmarks.spike.GraphSpike --offscreen --tier2
$JAVA -Xmx4g --enable-native-access=ALL-UNNAMED [FLAGS] -cp "$CP" \
    com.holtherndon.bazelviz.benchmarks.spike.TableSpike --offscreen
```

`-Xmx4g` and `--enable-native-access=ALL-UNNAMED` reproduce what the Gradle
tasks pass. Each configuration ran 5 times; figures below are medians of 5,
with peak RSS taken from `/usr/bin/time -l`. Absolute numbers from these
direct runs are not identical to the Gradle-task numbers earlier in this file
(different process, no daemon), which is why baseline and flagged runs were
always paired within the same method.

### Compact object headers — JEP 519 (`-XX:+UseCompactObjectHeaders`)

Final in JDK 25; a plain product flag on Corretto 25.0.1 (no
`-XX:+UnlockExperimentalVMOptions`, no warning). Shrinks the object header
from 12 to 8 bytes.

`runGraphSpike --offscreen --tier2` (1M nodes / 20.0M edges), median of 5:

| Measurement | Default headers | `+UseCompactObjectHeaders` |
|---|---|---|
| CSR build | 313.7 ms | 311.0 ms |
| CSR reverse | 46.1 ms | 48.2 ms |
| BFS throughput | 30.1M nodes/s | 31.0M nodes/s |
| Frame p95 | 1.27 ms | 1.29 ms |
| Reported retained (both directions) | 176.0 MB | 176.0 MB |
| Process peak RSS | 346.5 MB | 345.0 MB |

`runTableSpike --offscreen` (50M logical rows, 64-page × 512-row cache),
median of 5:

| Measurement | Default headers | `+UseCompactObjectHeaders` |
|---|---|---|
| Cached access max | 27.5 µs | 25.0 µs |
| Uncached fetch p99 | 130.2 µs | 131.0 µs |
| Process peak RSS | 93.9 MB | 93.1 MB |

**Result: negligible, and for a structural reason.** Two things are going on
and both matter more than the raw numbers:

1. The graph spike's "Retained" line **cannot** move under this flag. It is
   computed arithmetically from array lengths
   (`CsrGraph.retainedArrayBytes()`), not sampled from the heap, so it is
   1.76e8 bytes with or without compact headers. Anyone reading that row as
   evidence about object layout is reading it wrong.
2. The thing the flag would shrink barely exists yet. CSR is two primitive
   arrays — two headers total for 20M edges — so there is nothing to save. The
   table spike holds only 64 × 512 = 32,768 live row objects, far too few to
   register. The ~1.5 MB (0.4%) and ~0.8 MB (0.9%) RSS differences above are
   inside run-to-run noise and are not claimed as a win.

To get a real per-object number rather than guessing, a throwaway probe
(written to `/tmp`, deliberately not added to the repo) allocated 10,000,000
live instances of two shapes under `-Xmx4g` and measured live heap after
forced GC via `MemoryMXBean`, subtracting the holding `Object[]`:

| Object shape | Default headers | `+UseCompactObjectHeaders` |
|---|---|---|
| `record Edge(int, int)` — the ADR-006 object-per-edge shape | 24.2 B/object | 16.2 B/object (**−33%**) |
| `record RowRec(long, int, int, String)` — a plausible table-row shape | 32.2 B/object | 32.2 B/object (**no change**) |

That contrast is the useful finding: the saving is real (33% on a two-int
object) but it only materialises when removing 4 header bytes actually crosses
an 8-byte alignment boundary. A 20-byte payload pads back to 32 bytes either
way. So "compact headers will shrink our object graph" is not a safe
assumption — it depends on the exact field layout of whatever per-action or
per-row object Phase 3+ introduces.

**Recommendation: do not adopt now; revisit in Phase 3+.** There is no
measurable benefit against today's primitive-array structures, and adopting a
flag with no measured payoff adds a variable to every future measurement.
Re-measure once a real per-action row cache and a real event/action object
model exist (objective 9, Tier 3 heap), and only then with the field layout of
the actual classes.

### AOT cache — JEP 514/515 (`-XX:AOTCacheOutput` / `-XX:AOTCache`)

Measured against application startup (objective 8's "shows its overview
quickly" is the objective this would help, though objective 8 itself stays
**not yet measurable** until Phase 3 gives it a session to open).

Method: `./gradlew :app:installDist`, then run `:app`'s `Main` in smoke mode
(`-Dbbv.smoke=true`, which shows the window and exits after a fixed 2 s timer)
off the installed jar classpath. "Launch → window shown" is wall-clock from
just before `exec` to the timestamp of the `Main window shown` log line, so it
includes JVM startup, class loading, FlatLaf init and the first Swing paint.
12 reps per configuration — 6 run as a block per configuration, then 6 more
alternating baseline/AOT so thermal or background drift could not favour one
side.

```
./gradlew :app:installDist
CP=$(ls -d app/build/install/app/lib/*.jar | paste -sd: -)
# one-step cache creation (JDK 25):
$JAVA -XX:AOTCacheOutput=/tmp/bbv-app.aot --enable-native-access=ALL-UNNAMED \
    -Dbbv.smoke=true -cp "$CP" com.holtherndon.bazelviz.app.Main
# use it:
$JAVA -XX:AOTCache=/tmp/bbv-app.aot --enable-native-access=ALL-UNNAMED \
    -Dbbv.smoke=true -cp "$CP" com.holtherndon.bazelviz.app.Main
```

| Measurement (12 reps) | No AOT cache | `-XX:AOTCache` |
|---|---|---|
| Launch → window shown, median | 650.5 ms | 521.5 ms (**−129 ms, −20%**) |
| Launch → window shown, range | 634–698 ms | 505–633 ms |
| Total smoke-run wall clock, median | 2704 ms | 2581 ms |
| Cache file size | — | ~31.8 MiB (33.4 MB decimal); varies by a few KiB per generation |

This is the one measured win of the three. Two hard constraints came out of
the measurement and both are load-bearing for any future adoption:

- **The AOT cache cannot be built over exploded class directories.** The first
  attempt, using `build/classes/java/main` directories on the classpath, failed
  with `Error: non-empty directory ...` / `Cannot have non-empty directory in
  paths`. Cache creation requires a jar-only classpath, i.e. the
  `installDist`/`jpackage` layout, not a Gradle `run`.
- **The cache is bound to the object-header mode.** Running a cache built with
  default headers under `-XX:+UseCompactObjectHeaders` refuses to map: *"The
  AOT cache's UseCompactObjectHeaders setting (disabled) does not equal the
  current UseCompactObjectHeaders setting (enabled)"*, and the JVM falls back
  to no cache. If both features are ever adopted, the cache must be produced
  with the same flags the app runs with.

Cache creation also logs `Preload Warning: Verification failed` for
`ch.qos.logback.classic.net.SMTPAppender` and its base class; those two classes
are simply skipped and the cache is still produced and used. The project does
not use SMTP appenders, so this is cosmetic.

**Recommendation: do not adopt yet; revisit at packaging.** The 129 ms is
worth having, but the cache is a build-and-distribution artifact — it must be
generated per JDK build, per platform, per flag set, and shipped as a 33 MB
file inside the app image. That belongs with the jpackage/distribution work,
not with a toolchain bump, and it should be re-measured then against a real
session-open path rather than an empty window. **Unmeasured:** whether the
cache survives a JDK patch update, and its effect on a run that actually opens
a session (no such path exists in Phase 0).

### Generational Shenandoah — JEP 521

**Unmeasured.** `-XX:+UseShenandoahGC -XX:ShenandoahGCMode=generational` was
confirmed to start on this JDK (Corretto 25.0.1, macOS arm64), but no GC
comparison was run. The reason is that the objective it would serve —
objective 10, "no routine EDT pause exceeds 100 milliseconds" — has no
measurable subject in Phase 0: the spikes are batch, single-phase, and
allocate almost nothing steady-state, so any GC comparison against them would
measure the benchmark rather than the product. The default collector is G1
(confirmed via `-XX:+PrintFlagsFinal`) and nothing has yet shown a pause
problem.

**Recommendation: revisit in Phase 3+**, when live capture plus a running EDT
gives objective 10 something real to measure.

### Summary

| Feature | Measured effect here | Recommendation |
|---|---|---|
| Compact object headers (JEP 519) | None on either spike (structures are primitive arrays); −33% on a scratch two-int object, 0% on a 20-byte-payload record | Do not adopt; revisit in Phase 3+ with real per-object data |
| AOT cache (JEP 514/515) | −129 ms (−20%) launch → window shown | Do not adopt yet; revisit with packaging, requires jar-only classpath |
| Generational Shenandoah (JEP 521) | Unmeasured | Revisit in Phase 3+ when EDT pauses are measurable |

Preview features remain out of scope under ADR-008; all three flags above are
final product features in JDK 25, not preview.

## Build performance

`gradle.properties` enables parallel execution, the build cache, and the
configuration cache (`-Xmx3g` daemon). If a change regresses configuration
time noticeably, treat it as a defect.
