# User guide

Bazel Build Visualizer captures what a Bazel build did and shows it. It runs
locally, on your machine, for one person; nothing it captures leaves the machine
unless you export it.

This guide is written for somebody who has a slow build and wants to know why.

---

## Getting a session

There are three ways in, and they all end at the same place: a **session** — a
directory holding everything captured, indexed for querying.

### Run a build through the tool

Fill in the launcher bar at the top: the workspace, the Bazel executable, and
the command you would have typed. Press **Run**.

Before anything starts you are shown the **instrumentation plan**: your original
command, the effective command the tool will actually run, every flag it added,
and why. Nothing is added silently. If a flag you set conflicts with one the tool
needs, the conflict is shown and you choose.

The tool adds flags to make the build *observable* — an event stream, an
execution log, a timing profile. It does not change what the build does.

### Import a file Bazel already wrote

**File ▸ Open BEP File…** takes a `--build_event_binary_file` or
`--build_event_json_file` output. The format is detected by reading the file,
not by its name.

### Open a session somebody sent you

**File ▸ Open Portable Archive…** takes a `.bviz` file. It is validated
completely before a byte is written to disk — see [Sharing](#sharing) for what
an archive does and does not contain.

**File ▸ Open Recent** lists sessions already on this machine. Each one can be
pinned, which protects it from cleanup, or removed from the list without
deleting it.

---

## Reading a build

The views on the left all describe the same session. Selecting an action in one
usually reveals it in the others.

### Overview

What the build did, as counts and cards. Every card navigates to the detailed
view behind it.

Two numbers on this screen are worth understanding before you use any other:

- **Actions executed** is not "actions in the build". Bazel publishes an event
  for a successful action only under `--build_event_publish_all_actions`; a
  cache hit publishes nothing. The card says which kind of capture this was.
- **Bazel's own counters** are shown separately from this session's counts, and
  never mixed. They legitimately disagree — `actionsExecuted` excludes cache
  hits, and on some versions `actionsCreated` is *smaller* than
  `actionsExecuted`. Any ratio built from them describes nothing, so none is
  shown.

### Timeline

Every action as a span, on a shared clock, grouped into lanes. Drag to select a
range and the Actions table narrows to it.

At a wide zoom the timeline draws aggregate bins rather than individual spans;
the counts in a bin are exact for that bin. Zoom in and individual spans return.

**Colour means duration, and grey means unknown.** Grey is deliberately off the
heat ramp rather than at its cold end: "took no time" and "nothing measured
this" look identical on a ramp and mean opposite things.

### Actions

Every executed action, paged and sortable. Filter by mnemonic, by target, or by
a time range dragged out on the timeline. Selecting a row fills the inspector:
timings, inputs, outputs, attempts, the command line, and where in the raw
capture the row came from.

The command line is shown one argument per numbered line, and secrets in it are
masked.

### Graph and Tree

The dependency graph, derived from which action produced the file another action
consumed — two cards, one graph. **Tree** browses dependencies and reverse
dependencies one level at a time and finds paths between nodes; it works at any
graph size. **Graph** draws bounded pieces of it on a canvas.

A graph too large to draw in detail **groups itself** by package, target or
mnemonic rather than showing you a blank canvas or a hairball. The exact totals
are always on screen, and you can raise the limit, narrow the query, or export
the whole thing. Nothing is silently sampled.

The Graph card's **weight selector** decides what node size, edge thickness and
colour mean: duration, immediate or transitive dependency counts, output size,
or inputs. Transitive counts are exact for what is drawn; the whole-graph count
for a selected node is budgeted, and shows "≥N (budget reached)" when the
search gave up rather than a number pretending to be a total. A node with no
recorded value is grey at base size — unknown is never shown as zero.

### Findings

Evidence-backed candidates, worst first: a long dependency chain, a stretch of
low parallelism, a group of actions that keep missing the cache, an action
everything waits for.

**A finding is a candidate, not a diagnosis.** Every one names the threshold it
used, the numbers it rests on, the records it came from, and the reasons it might
be wrong. None of them says a change will make your build faster, because one
build on one machine cannot support that claim.

Above the findings is the **coverage** banner. Read it first. A cache-miss
finding over a build whose cache state was 12% covered is a different claim from
the same finding at 99%, and the banner is what tells them apart.

### Tests, Errors, Events, Build

- **Tests** — one row per test target, with attempts, and the distinction
  between a test that failed and a test that never built.
- **Errors** — failed actions, failed targets, targets never built, and what
  Bazel printed to stderr. The first three are three separate counts and are
  never summed: one broken target in a large workspace would otherwise look
  like a catastrophe. The stderr rows are why the card is called Errors rather
  than Failures — for most broken builds the compiler's own text is the only
  diagnostic there is, and not all of it describes a failure.
- **Events** — the raw event stream, with the original bytes of any event.
- **Build** — one pane for the running build: the capture's phase, counters and
  stop buttons across the top, and what Bazel printed below them.

---

## When something is missing

The tool tries never to show you a number it does not have.

- **A value nothing reported reads "not reported"**, never 0 and never a blank
  cell.
- **A total that could not be completed says so** — "4.2 GiB known, 137 inputs
  of unknown size" rather than "4.2 GiB".
- **A source that did not run is named.** If the execution log was not captured,
  the views that depend on it say which enrichment is missing rather than
  showing an empty chart.

The clearest example: most actions run inside the Bazel server and never start a
subprocess, so most actions have no execution-log record and never will. Every
place that shows a rate over those records says what it was taken over.

---

## Sharing

**File ▸ Export** produces four kinds of thing.

### Redacted session archive

A `.bviz` archive of the whole session with secrets and absolute paths replaced.
You are shown **exactly what was redacted before anything is written** — counts
by rule and by field — and can cancel.

A redacted archive **does not contain the raw capture**, because the raw capture
is the unredacted bytes. That has a consequence worth knowing: the person you
send it to cannot re-run the enrichments or rebuild the database from source
bytes. The archive says so when they open it.

Redaction replaces each distinct secret with a stable pseudonym rather than a
mask, so the recipient can still see that ten thousand actions used *the same*
credential without seeing it.

Two further options, both off by default because each costs something:

- **Omit every environment value**, keeping only the names. Stronger than the
  pattern rules and the right answer if your environment holds something the
  patterns will not recognise.
- **Replace target labels with pseudonyms.** Hides your project's internal
  structure and makes the export hard to read.

### Complete session archive

Everything, raw capture included. **This is as sensitive as the machine it was
taken on** — every command line, every environment value, every absolute path,
exactly as captured. The tool asks before writing one.

### Binary BEP file

The captured stream written back out in Bazel's own format, so any other tool
that reads `--build_event_binary_file` output can read it. For a live capture
these are Bazel's own bytes, unchanged.

### CSV and JSON tables

Actions, targets, tests, attempts or artifacts, redacted, streamed straight to
disk. Large exports do not load the session into memory.

---

## Housekeeping

**File ▸ Clean Up Sessions…** removes old sessions. You choose the limits, see
the exact list of what would go and how much space it frees, and only then
confirm. Pinned sessions are never removed.

Sessions live in your application-support directory. Moving that directory is
safe: the tool matches sessions by identity, not by path, and reconciles the
library on the next launch.

---

## What v1 does not do

Stated so you do not go looking:

- **No comparison between builds.** One session at a time.
- **No settings screen.** The limits in `docs/limits.md` exist and are not
  editable from the UI.
- **No remote BES forwarding**, no historical storage, no team sharing.
- **macOS only.** The code is portable; nothing else has been tested.
- **The timeline does not draw the critical path.** The graph does.

---

## If something goes wrong

See [troubleshooting.md](troubleshooting.md). The short version: a session
directory is self-contained and the raw capture inside it is never modified, so
almost every problem is recoverable by re-importing the session's own bytes.
