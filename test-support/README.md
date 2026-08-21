# test-support

Deterministic synthetic fixtures for benchmarks, spikes, and tests. Nothing in
this module ships in the application; other modules depend on it from their
test and benchmark source sets.

## What it provides

Package `com.holtherndon.bazelviz.testsupport.synthetic`:

| Class | Purpose |
| --- | --- |
| `SyntheticScale` | Benchmark tiers (action/event/edge counts, assumed machine class). |
| `SyntheticAction` | Cheap value carrier for one synthetic action span. |
| `SyntheticActionGenerator` | Pure O(1) `actionAt(index)` plus `stream()` over a tier. |
| `SyntheticEdges` | Streamed DAG edges (`producer < consumer` by construction). |
| `SyntheticRawEvents` | Length-delimited binary frames standing in for raw BEP journal payloads. |

## Why O(1) random access

The UI must page through tables and timelines backed by up to 50 million rows
without materializing them (bounded-memory rule: no one-object-per-event
retention). Benchmarks for that paging need a source with the same shape: any
index fetchable on demand, no precomputed arrays, no fixture files on disk.

Every generator here is a pure function of `(scale, seed, index)` built on
SplitMix64 hash chains, so:

- `actionAt(i)` / `frameAt(i, buf)` cost the same for `i = 0` and `i = 49_999_999`;
- two processes (or machines) with the same `(scale, seed)` see byte-identical
  data — no fixtures to check in or ship;
- a TIER3 "dataset" occupies zero memory until a caller asks for a row.

Consequently: never pre-generate collections of `SyntheticAction`s. Call
through the generator API from wherever the data is consumed.

## Tiers

| Tier | Actions | Events | Edges | Assumed RAM |
| --- | ---: | ---: | ---: | ---: |
| TIER1 | 100 k | 1 M | 1 M | 8 GB |
| TIER2 | 1 M | 10 M | 20 M | 16 GB |
| TIER3 | 5 M | 50 M | 100 M | 32 GB |

`SyntheticEdges.edgeCount()` reports the exact streamed count, which can fall a
fraction of a percent short of the nominal tier figure because the per-consumer
degree is `edgeCount / actionCount` rounded down.

## Seed conventions

- **Seed `42` is the canonical benchmark seed.** All recorded benchmark
  baselines use it; comparing numbers produced with any other seed is invalid.
- Unit tests may use any seed, and use small offsets (`43`, i.e. `42 + 1`) when
  they need a provably different dataset.
- Determinism contract: identical `(scale, seed)` yields identical data across
  processes, JVMs, and machines — forever. Changing generator internals breaks
  that contract and invalidates every recorded baseline, so treat the emitted
  values as frozen once baselines exist.

## SyntheticRawEvents

Frames are 64–2048 bytes, cubic-skewed toward small (mean ~560 bytes) to mimic
real BEP streams dominated by small progress/action events.
`frameAt(index, dest)` writes into the caller's buffer and allocates nothing,
so journal benchmarks can pump millions of frames without GC noise; size
buffers to `MAX_FRAME_BYTES`. `totalBytesEstimate()` is closed-form
(`frameCount * expected frame length`), not a scan.
