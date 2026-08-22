# Phase 4 audit

Unlike Phase 3's, this audit was done by hand rather than by a fleet of agents:
the machine could not take another 171 of them, and the session's instructions
ruled out spawning any. So it is a systematic self-review against the defect
classes Phase 3's audit actually found, each turned into a check that can be
run rather than a thing to be remembered.

**Six findings, all fixed.** Four came from the mechanical checks, two from
running the code against real Bazel.

---

## What the checks were, and what each found

### 1. A column declared, read, rendered, and never written

Phase 3's worst finding was `saw_last_message`: selected, rendered into a
sentence on every screen, and written by no statement, so every session claimed
its capture was truncated.

The check is now mechanical — extract every column from the v4 DDL, extract
every `INSERT INTO … (…)` column list and every `UPDATE … SET` assignment from
the whole main source, and subtract.

**Found three dead columns.** `enrichment_tasks.source_id`, a foreign key to
`capture_sources` that nothing set; `attempt_outputs.digest_hash` and
`attempt_outputs.size_bytes`, a per-attempt copy of a digest that already lives
on the artifact row. All three removed. Schema v4 is unreleased, so they were
deleted from its DDL rather than removed by a v5 that would exist only to drop
columns nobody ever wrote.

The check also produced five false positives, all from SQL split across Java
string concatenation, which is worth recording so the next person does not
"fix" them.

### 2. Something built and never wired

Phase 3 shipped a live overview that was never attached to a running capture.

The check: for every method added to `EntityReader`, count call sites outside
the interface.

**Found one.** `attemptsForLabel` had zero — and it is the only way to reach a
*test's* attempts, since a test spawn matches no action by output on any Bazel
version. So the whole `MATCHED_BY_TEST_LABEL` correlation path was being
computed, stored, and never shown, which meant "ambiguous correlations remain
visible" was half delivered.

Now wired into the tests view. The two spawns are shown side by side, numbered,
with a note saying that Bazel runs a test as more than one subprocess and that
the verdict above comes from `testSummary` rather than from these — because the
XML-writing spawn exits 0 even when the test failed, and a reader seeing "exit
0" next to a failed test deserves the explanation.

### 3. A number that is precise and unmeasured

`LogIdMap`'s javadoc said a `HashMap<Long, Long>` costs "roughly 48 bytes an
entry". Nothing in this project measured that; it came from memory of JVM
object layouts. Replaced with the argument that is actually true — a node and
two boxed longs against eight bytes of data — and an explicit note that the
multiplier is not written as a number because nobody measured it.

Small, and exactly the class of claim the Phase 3 audit spent its time on.

### 4. Unbounded accumulation that a small fixture hides

`ProfileWriter` added every span, counter and thread to a single JDBC batch and
executed it in `finish()`. Correct for the fifteen action spans a six-target
build produces; indefensible for a profile that grows with everything the build
did, and against plan 19.4's bounded-memory requirement.

Batches now flush every 5,000 rows. A test imports a synthetic profile of
12,000 spans and asserts every row lands, so a regression shows up as missing
rows rather than as memory nobody measures.

### 5. Swing HTML injection

Checked and clean. Every label in the new views is constructed through
`PlainText.disableHtml`, and the one tooltip goes through `PlainText.tooltip`.
This matters more in Phase 4 than in Phase 3: the coverage panel renders
runner names, error excerpts and Bazel's critical-path descriptions, all of
which are strings from a build, and one of them is a progress message chosen by
whoever wrote the BUILD file.

### 6. Does the panel ever refresh?

Checked: after a live capture finishes, `captureFinished` reopens the session
directory, which reinstalls every view including the coverage panel. So the
enrichment that runs at the end of a capture is on screen without the user
doing anything. Not a finding — but it is the same shape as Phase 3's live
overview defect, and it was worth confirming rather than assuming.

---

## The two the checks could not have found

Both came from running the code against real Bazel, and both were invisible in
review.

### The measurement that measured nothing

The actions table gained Runner and Cached columns as correlated subqueries
over `action_attempts`. Phase 3's whole performance story is that keyset paging
was broken twice in ways only measurement caught, so the columns were measured
immediately: 0.57–0.93 ms a page across all six sorts, flat from head to tail,
comfortably inside budget. Passed every threshold.

**The spike loads a million actions and no attempts.** The subqueries were
running against an empty table. The number was real, the conclusion was
worthless, and nothing about the output said so.

With 333,334 attempts loaded — one per three actions, the ratio a real build
produced — pages cost 0.65–1.14 ms. About double, still flat, still fine. The
spike now loads attempts, and the reason it must is a comment in the code.

### Capability detection probing a different Bazel than the build runs

`RealBazelEnrichmentTest` runs the whole path on Bazel 6.5.0, 7.6.1, 8.4.1 and
9.2.0. On the first run 6.5.0 skipped, because its build had failed.

`BazelExecutableResolver` ran `bazel --version` with an empty environment map,
so bazelisk's `USE_BAZEL_VERSION` never reached it. Capability detection
therefore probed the default Bazel — 9.2.0 — reported
`--execution_log_compact_file` as supported, and the planner injected it into a
6.5.0 build that rejects the flag outright. The build failed before analysis, so
neither the execution log nor the profile was written, and the only symptom was
two warnings saying files Bazel had been asked to write were missing.

The fix is one parameter: resolution now runs under the environment the build
will run with, so preflight, detection and execution agree on which Bazel is in
play. The failure mode is general — any user whose Bazel version comes from
somewhere the resolver could not see would hit it — and it was reachable only
by actually running four versions end to end.

---

## What this says about the process

Phase 3's audit cost 171 agents and found 28 things. This one cost an afternoon
of greps and found six. The difference is not that Phase 4 is better code; it
is that the greps encode what the agents found. A check that lists every column
never written is worth more than a lens that might notice one, because it runs
again next phase for free.

What the greps could not do is run Bazel 6.5.0. Both of the findings that
mattered most — a performance measurement that measured an empty table, and a
version-detection bug that failed a whole build — came from executing the thing
on real inputs. Neither would have survived a reviewer's attention, because
neither is visible in the code: one is a property of the fixture, the other a
property of the environment.

The rule that keeps earning its keep is the one from Phase 3: when Bazel's
behaviour is uncertain, make a real fixture before encoding the assumption. Its
corollary, learned here, is that when performance is uncertain, make the
fixture resemble the data — an empty table is fast, and a benchmark over one
will tell you so with great confidence.
