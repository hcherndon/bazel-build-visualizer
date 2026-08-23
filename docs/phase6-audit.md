# Phase 6 audit

The mechanical checks from Phases 4 and 5, run again. **Four findings, all
fixed.** Three from the greps, one from measuring instead of assuming.

Phase 6 is mostly new code in one module, so the checks that found dead
database columns in earlier phases had nothing to find. The one that earned its
keep was counting call sites.

---

## From the checks

### 1. Four per-bin aggregates computed, stored, and read by nobody

Plan 14.3 lists what each timeline bin should carry, and Phase 6 added the ones
Phase 0 had not: cache hits, the count of spans anything reported a cache result
for, the remote and known-runner counts, a byte total, and a category vote.

Counting call sites outside the class that computes them found four —
`uniformCategory`, `byteTotal`, `runnerKnownCount`, `cacheKnownCount` — with
main-source usage of zero. Only their own tests read them.

That is not a harmless omission. These are stored per bin at every level, and
the byte total alone is eight bytes a bin against a cap of 3.7 million: a
build storing them and showing none pays about thirty megabytes for nothing,
and pays 72% more build time to compute them.

Fixed by delivering them rather than removing them, because the plan asks for
them and they are worth showing. The hover readout under the timeline now
reports every part of a bin, and each part appears only where something knows —
a bin reading "0 cache hits" in a session with no execution log would be the
loudest wrong claim on the screen.

### 2. The time-range filter had nothing to filter

`TimelineController.selectedRange` had no callers at all. Plan 14.5 lists
"filter selected time range" as a timeline interaction, and dragging one out
did nothing beyond shading the plot.

`ActionFilter` had no time range to give it, which is presumably how it came to
be forgotten: the deliverable needed a change one module away and stopped at the
boundary. It has one now, and the timeline pushes it into the actions table.

Two decisions inside that worth recording. The range matches actions
**overlapping** the window rather than contained in it — an action that started
before and finished inside was running then, and it is usually the long one a
user dragging a range around a slow patch is looking for. And an action with no
timestamps matches no range at all, because it is not known to have run then and
guessing either way would invent a fact.

`selectedRange` is gone rather than left as dead API now that the push model
covers it.

### 3. A comment describing a check the code did not make

`TimelineLodIndex`'s category accessor was documented as returning the category
holding a strict majority of a bin's spans, "or empty" when none did. The
Boyer-Moore vote it used cannot establish that in one pass — its survivor is a
true majority only if one exists, and proving which case happened needs a second
pass the streaming build does not make. The javadoc even said so, two paragraphs
below the claim.

Caught before commit. What the vote *can* establish for free is the case where
its counter never dropped: every span in the bin was one category. That is a
weaker claim and a true one, so the accessor is now `uniformCategory` and a
mixed bin reports nothing rather than a ranking nobody computed.

Named for what it proves. Calling it "top" would have been the same defect the
Phase 3 audit found in the test-timing columns — a name claiming a provenance
the data does not have.

---

## From measuring

### 4. Live refresh would have rebuilt the pyramid per progress tick

The timeline attaches to a running capture and rebuilds as the build appends.
The first version called that from `captureProgress`, which arrives per event
batch — many times a second on a fast build — and each rebuild streams every
span in the session.

At Tier 2 that is 170 ms of work. Rebuilding per tick would have spent more time
indexing than capturing, and would have broken "Tier 2 remains interactive" in
the one situation where it matters most: while a build is running and someone is
watching it.

Coalesced to two seconds, one rebuild at a time. The consequence is recorded
rather than hidden: the timeline is up to two seconds behind a running build,
and every number on it comes from one consistent read — the same trade the
overview panel made in Phase 3, for the same reason.

---

## What the checks said was fine

- **No unwritten schema columns.** Phase 6 added none; the timeline is built
  from what Phases 3 to 5 already store.
- **No unbounded JDBC batches.** Phase 6 writes nothing.
- **Swing HTML paths.** Every label goes through `PlainText.disableHtml`,
  including the hover readout, which renders mnemonic names from someone's
  BUILD file.
- **No SQLite reachable from painting** — see below, where this stopped being a
  check and became a test.

---

## The check that became a test

Plan 24's fourth exit criterion is that no SQLite access occurs during painting.
Reviewing a paint method for that works once. `TimelinePaintIsolationTest`
inspects what the painting classes can *reach* — `TimelineView`,
`TimelineModel`, `TimelineLodIndex`, `SpanWindow`, `TimelineViewport` — and
fails if any of them gains a field typed as a connection, a reader, or anything
from the storage module.

It also asserts that `TimelineController` **does** hold one. Without that, the
first two assertions would keep passing if the split quietly collapsed and
nothing anywhere touched a database — a test passing for the wrong reason is
worse than no test, because it is evidence.

Reachability rather than behaviour, deliberately: a paint method that happens
not to query today but holds a `Connection` is one refactor away from doing so,
and the design's point is that the refactor should be impossible rather than
discouraged.
