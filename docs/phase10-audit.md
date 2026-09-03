# Phase 10 audit

The release gate. The four standing checks were run again, and a fifth was added
because this is the phase where the plan's own definition of done gets walked
item by item.

1. **Call sites of every new public method** — 4 findings in Phase 6, 11 in
   Phase 7, 35 in Phase 8, 58 in Phase 9, **0 here**. Phase 10 added almost no
   production API: five members with no caller, all of them interface
   implementations or `main`. That is what a hardening phase should look like.
2. **Declared columns against INSERTs and against the redaction inventory** —
   no schema change this phase; the inventory test still passes in both
   directions.
3. **Read the prose** — three findings, below.
4. **Run the thing, and measure it** — four findings, and two of them were in
   the measuring apparatus rather than in the product.
5. **Walk plan section 25 item by item** — one finding.

---

## 1. The benchmark could not reach the scale it existed to measure

The first Tier 3 capture run died with an `OutOfMemoryError` in
`io.grpc.internal.DelayedStream`, **in the benchmark's client**, before the
server had done anything at all. Its send loop called `onNext` fifty million
times with no flow control, so gRPC buffered everything the transport could not
yet write.

Two things follow, and the second is the one that matters.

- The client now waits on `isReady()` through a `setOnReadyHandler`, which is
  the documented manual-flow-control pattern for a blocking producer.
- **The previously published capture figures were flattered by that defect.**
  83.8–86.1k events/sec was measured with a client running ahead of the
  transport; the honest figure for the same configuration is 79.4k/s.

This is the *second* time this page's capture numbers have had to be corrected
in a flattering direction — the first was a column that divided the server's
received count by the client's send duration. Both were found by making the
benchmark do more work, not by reading it.

## 2. Tier 3 throughput is five times slower than a burst, and the cause is not obvious

| Events in one capture | Rate |
|---:|---:|
| 200,000 | 79,359/s |
| 3,000,000 | 43,803/s |
| 50,000,000 | 16,157/s |

It is **not** index maintenance: `EventWriter` already builds indexes after the
load. It is the page cache — SQLite's default is about 2 MB, so once a
multi-gigabyte b-tree stops fitting, every insert is a random read of an evicted
page.

Three pragmas were added (`cache_size`, `temp_store`, `wal_autocheckpoint`) and
measured before and after at three million events: **+18%**.

**And then measured at fifty million: +4%.** That is the finding worth keeping.
128 MB of cache covers a useful fraction of a 700 MB database and none of a
six-gigabyte one, so the Tier 3 bottleneck is disk-bound random I/O into a
b-tree that fits in no cache this application would be willing to reserve. The
change is kept because mid-size sessions are the common case; the claim made for
it is the smaller one.

## 3. A rule that was too coarse, and the code that was right

`BoundedMemoryTest`'s first version flagged `EntityWriter.knownLabels` as an
unbounded map. It is not: it is a `LinkedHashMap` with `removeEldestEntry`,
bounded by the dictionary-cache size. The rule was wrong, not the field.

The rule split in two, which is a better rule than the one it replaced: what
must be *flat* (the CSR graph, the timeline pyramid, the concurrency spans, the
quantile sketch) may hold no collection at all, and what *streams* the build may
cache interned text but may not retain a domain object. The second half is
checked through generic type arguments, so a `Map<String, ActionRow>` fails and
a `Map<String, Boolean>` does not.

Recorded because the useful lesson is the shape of the mistake: a structural
rule that fires on correct code teaches people to delete the rule.

## 4. Three documents had drifted

- **`docs/performance.md`'s objectives table** still said "not yet measurable"
  for six of the twelve objectives, naming phases that finished months ago, and
  row 1 quoted a figure that had already been corrected elsewhere on the same
  page. All twelve now say what was measured.
- **`docs/bazel-compatibility.md`** described the flag matrix and the ground
  truth but had no release-level statement: no end-to-end result per version and
  no consolidated list of what each version cannot do. Both are now there, the
  first measured by a test that prints the row it asserts.
- **`docs/troubleshooting.md`** covered the build and the run and nothing about
  sessions, which is what a user actually gets stuck on.

## 5. The definition of done named something that was not there

Plan section 25's first Invocation item is "user can select a workspace and
executable". The launcher bar had a workspace field and passed the literal
string `"bazel"` — so a user running `bazelisk`, a wrapper, or a pinned binary
at a path could not capture the build they actually run, which is the build they
came here about. The resolver already handled both spellings; nothing was
reaching it. Found by walking §25 rather than by assuming the launcher matched
plan 17.1's list.

Post-v1, ADR-011 moved this choice into the initial **Workspaces** screen. This
section records the Phase 10 launcher as it existed; it is not the current
startup flow.

---

## What the checks did *not* find, and what that is worth

Nothing wrong with the Tier 3 capture itself. Fifty million events sent,
acknowledged, journaled and indexed, `received == journaled`, complete, at
0.48 GB resident. Nothing wrong with the Tier 3 indexed session: reopened from
cold, overview in 9.6 ms against a five-second objective. Nothing wrong on any
of the four Bazel versions: all four captured completely with nothing lost.

Those are the three exit criteria that could have gone badly, and none of them
did. It is worth saying plainly, because an audit that only lists problems
implies the rest was not checked.

---

## Deliberate omissions, restated for the release

These are in `docs/security-review.md` and `docs/limits.md` in full; collected
here so a release reviewer sees them in one place.

- **No fuzzing** (plan 23.7). The parsers have hand-built damage tests, which
  cover the failure modes somebody thought of.
- **No dependency advisory scan.** Dependencies are locked and were reviewed
  when introduced; nothing re-checks them per build.
- **No settings screen**, so fourteen of plan 20.3's nineteen configurable
  limits are constants a caller can change and a user cannot.
- **Intel macOS is unverified.** jpackage does not cross-compile; the Apple
  Silicon package was built and launched, and the Intel one needs an Intel
  machine.
- **The timeline's critical-path overlay was never drawn.** An unshipped Phase 6
  deliverable, recorded as such since Phase 8.
- **"Prefer rebuilding from raw files"** (plan 22.4) is implemented as *refuse
  with the remedy*, for the three reasons in the security review.
