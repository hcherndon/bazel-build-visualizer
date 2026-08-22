# Phase 3 audit

Eight lenses over the Phase 3 surface, three independent refuters per candidate
finding, and a completeness critic whose job was to audit what no lens owned.
**171 agents, 62 distinct candidates, 20 confirmed by the refuters, 8 more from
the critic.** Every confirmed finding and every critic finding was fixed; the
commits are `3c4131b`, `07b6033`, `d87b94b`, `92e89a5`, `6eac68f` and
`111182a`.

The lenses were: truthfulness of displayed numbers, BEP semantics against the
measurements in `docs/bep-content.md`, SQL correctness, concurrency and the EDT,
scale to five million actions, security and privacy, behaviour on partial data,
and false claims in comments and documents.

## What made the tool say untrue things

**Every session claimed its capture was truncated.** The overview appends
"stream did not reach its end marker" whenever `saw_last_message` is false, and
nothing ever set it. The column was declared, selected and rendered; no
statement wrote it. Four lenses found this independently. Because aborted events
arrive after `buildFinished`, that message tells the user their failed-target
list may be incomplete — on every session, including complete ones.

**A test's "Elapsed across attempts" was Bazel's summary window,** which
excludes failed retries. Measurement TS2 found it understating real wall time by
13× on a six-attempt test, with its first start landing up to 747 ms after the
earliest attempt. The schema comment, the row's javadoc and the inspector label
all said the value was computed from the attempts. None of them was true.

**An aborted target existed only in the abort log.** From Bazel 7.6.1 an
analysis-failed target emits no `configured` payload at all — an abort riding
the `targetConfigured` id is the only event that names it — so every such target
was missing from the targets tree and from the target counts. That is the whole
of what a failed analysis produces.

**A test that failed to build reached the tests view not at all.** Bazel emits
no `testResult` and no `testSummary` for one, so a run where every test failed
to build reported that the session recorded no tests. The critic verified this
against a live Bazel: three test targets, one failing to build, and the BEP
carried summaries for the other two only.

**"N actions" was a total the source cannot support.** Bazel publishes an action
event only for an action that executed, so every cache hit is missing. Verified:
a warm rebuild of one target published one event where the build declared two.
The only qualification fired when the publish flag was off, which on this
application's own capture path it never is.

**Two metrics arrived empty and were stored as confident zeroes.**
`targetMetrics` and `packageMetrics` come through as `{}` on any loading or
analysis failure, and the per-mnemonic created count is absent on Bazel 6.5.0
and 7.6.1. proto3 makes all of those a zero, and the overview reported "Targets
configured: 0" for a build that configured thousands.

**An interrupted build was reported as failed,** on a screen whose next line
read "exit INTERRUPTED".

**The "Exit" column showed Bazel's constant 1 as the process's exit code.** A
genrule that produces no output fails with no spawn sub-message at all — the
command exited 0 — and the column said 1, contradicting its own javadoc and the
inspector on the same row.

## What was lost, leaked, or never built

**Live-captured sessions had no indexes.** `SchemaIndexes.createAll` and
`ANALYZE` were reachable only through the import path, so every query on a
captured session — the main way this tool is used — fell back to a scan, and
nothing about the session said so.

**Resuming an import never migrated the schema.** The resume path checked
compatibility, which rejects a database newer than this build and applies
nothing to an older one, so a session interrupted under an earlier build was
replayed into tables it did not have. The UI offers exactly that resume.

**The read path never checked the version either**, so a session indexed before
Phase 3 opened happily and answered every entity query with "no such table" —
five views each showing a different SQL error.

**Closing the window leaked five views and the session.** Taking ownership of
the `SessionSource` into `MainWindow` meant `EventsView` stopped closing it, and
`dispose()` was never given the matching close.

**The entity buffer was bounded in events, which bounds nothing.** One event
carries between zero and thousands of records — a `NamedSetOfFiles` holds a
whole file list — and the fallback-file path accumulated an entire file's worth
before its single drain.

**Truncation evidence was computed and discarded.** `conflictingActions()` and
`undefinedDepsets()` are documented as the tool's evidence of a broken identity
assumption and of a truncated capture; nothing outside the tests read either.

**Build output had no reader.** For most failures Bazel's console text is the
only diagnostic there is — a syntax error produces thirteen events and zero
structured messages — and the query that finds those events had no caller.

## The security finding

**Every label and table cell rendered build text as HTML.** Swing turns any
string beginning with `<html>` into a live document and fetches its
`<img src="http://…">` when painted, so a string in a session file could make
this application open a network connection — against plan 22.1, which says it
makes none — and render arbitrary markup where the user expects to read what
Bazel said. An imported session is untrusted outright (plan 22.4).

Verified before fixing, and again after: Swing installs an HTML renderer for
such a string, and installs none once the component carries `html.disable`.

## The two the audit could not have found

Both were found by re-reading the code while the audit ran, and both are here
because they are the same kind of mistake.

**The actions view closed the reader its own page source needed.** Every page
fetch afterwards failed and the table rendered the error placeholder. Its wiring
test passed anyway, because the assertion checked only that the cell was no
longer the *loading* placeholder — an error placeholder satisfied it. That is
the third time in this project a test has passed over the bug it was meant to
catch.

**Keyset paging cost the same as `OFFSET`, twice over.** Sorting on a null flag
cost 18.8 ms at the tail of a 200,000-row table; its obvious replacement cost
8.2 ms and planned three different ways at three depths. Neither was visible
from the code — both read as correct — and neither had a measurement.
`EntityScaleSpike` exists now because of it, and `docs/performance.md` records
what each mistake looked like.

## What the refuters killed

42 candidates did not survive. Most were correct readings of code that had
already been fixed while the audit ran — the refuters read the current tree, as
they should — but three classes were genuinely wrong:

- **Claims about SQLite that a measurement contradicted.** One lens argued a
  particular index would make the duration sort seekable; it does not, because
  SQLite does not seek a row-value comparison over an expression.
- **Rule violations that the code's own comment already justified.** The
  guidance excludes those, and several were raised anyway.
- **Consequences with no path to a user.** A finding that survives "is the code
  as described" and "is the evidence real" can still fail "would anyone ever see
  it", and three did.

## What this says about the process

The completeness critic found eight defects that eight lenses missed, for the
fourth phase running. Its advantage is not intelligence but scope: it is the
only agent told to ask what nobody looked at. Two of its findings — the tests
that fail to build, and the actions count — it verified by running Bazel itself
rather than by reading.

The duplicate rate is worth noting: `saw_last_message` was found by four lenses
and the test-elapsed defect by three. Overlap is not waste. A finding three
lenses reach by three routes is one the refuters cannot dismiss, and the
`saw_last_message` reports differed on severity in ways that sharpened the fix.
