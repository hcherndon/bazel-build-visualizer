# Phase 5 audit

The same mechanical checks Phase 4 established, plus the ones running the code
turned up. **Six findings, all fixed.** Three from the greps, three from
execution.

The greps cost nothing and run again next phase; that is the whole point of
writing them down rather than remembering them.

---

## From the checks

### 1. A whole table declared and never written

`graph_completeness` — kind, completeness, node and edge counts, a detail
sentence — existed in schema v5 and had **zero references anywhere in the
source**. It was going to be where rule 13's "never claim a graph is complete
unless its source supports it" lived.

It is not needed. `graph_sources` already carries the configuration match, the
declared and correlated counts and the failure reason, and the UI reads those.
A second place to record completeness is a second place for it to disagree with
the first. Dropped.

Found by extracting every column from the DDL and subtracting every `INSERT`
column list and `UPDATE` assignment in the main source — the same grep that
found three dead columns in v4.

### 2. A column only ever set to NULL

`graph_sources.exit_code` was written exactly once, as `exit_code = NULL` in the
statement that resets a row before a re-import. The importer is handed a file
and never sees an exit code; the runner that does already puts its stderr in
`error_excerpt`, which is the part a person reads. Dropped.

### 3. A column plan 12.4 needs, never written

`graph_sources.raw_output_path` was declared and never set. Plan 12.4 requires
that the user be able to inspect raw query output, and this is where the UI
finds it. The importer has the path in hand. Now written.

The same grep produced five false positives, all from SQL split across Java
string concatenation — worth recording so the next person does not "fix" them.

---

## From running it

### 4. The configuration check made its own success unreachable

The most interesting one. `ConfigurationMatch` compares the configurations a
query reported against the ones the build declared, and the end-to-end test
came back `PARTIAL` for a query that had just analysed the very build that ran.

Bazel publishes a `none` configuration: a placeholder with no mnemonic, for
targets that have no configuration at all (finding C3). It appears in the event
stream as a configuration and **no query can report it**, because it is not a
configuration analysis produced. Comparing against it meant `EXACT` could never
happen — the one state that permits a graph to be called the build's.

The set is now the configurations at least one configured target was actually
built in, which is what "the configurations the build ran under" means.
Measured: two, and `aquery` reports exactly those two.

This was invisible in review. The rule reads correctly, the code implements the
rule correctly, and the rule was about the wrong set.

### 5. Binary output through a UTF-8 String

`AuxiliaryQueryRunner`'s first version captured the query's stdout with
`Subprocess.run`, which decodes as UTF-8. That is right for `bazel help` and
destroys a protobuf: every byte sequence that is not valid UTF-8 becomes a
replacement character, and the damage is not recoverable. `aquery` output is
binary.

Caught by reading `Subprocess`'s decoding before wiring it, rather than by a
failure — the failure would have been a corrupt graph file and a parse error
some distance from its cause. `Subprocess.runRedirectingStdout` now moves the
bytes without the JVM looking at them.

### 6. Two bugs the CSR work surfaced

Re-importing a graph would have violated the unique constraint rather than
refreshing, because `graph_sources` reuses its row per kind while
`declared_actions` did not. And CSR nodes need dense indices from zero while
row ids keep growing across re-imports, so `declared_actions` gained a
`node_index` assigned at import. Both found while writing the index builder,
which is the first thing that cared.

---

## What the checks said was fine

- **Swing HTML injection.** Every label in the graph view is constructed
  through `PlainText.disableHtml` and the tooltips through `PlainText.tooltip`.
  This matters here: the trees render target labels and mnemonics, which come
  from someone's BUILD file.
- **Unbounded JDBC batches.** `StagingVisitor` flushes every 5,000 rows. The
  cquery importer did not, and now does — its node and edge batches grow with
  the workspace.
- **Unwired methods.** One: `GraphQueries.nodeForAction`, the bridge from a
  selected action to its neighbourhood — which is a plan 24 deliverable in its
  own right. It is now behind a Dependencies button on the actions table.
  `hasIndex` had no callers and no purpose and was dropped.

---

## Validation cost, and what was cut

Phase 5's end-to-end test originally swept Bazel 6.5.0, 7.6.1, 8.4.1 and 9.2.0
through a real capture, as earlier phases do. It no longer does, at the user's
instruction and for a good reason: each version needs its own output base and
therefore its own Bazel server, Bazel sizes a server's heap from the machine's
RAM, and Gradle runs modules in parallel. The result was a development machine
repeatedly taken past 120 GB of resident memory, to the point of crashing.

The sweep was not where the version coverage came from. `ActionGraphImporterTest`
pins the version-specific behaviour — forward references, the path fragment
tree, `is_executable`, the configuration checksums — against checked-in `aquery`
output from all four versions, at no Bazel server at all. The end-to-end test
exists for the one thing a fixture cannot show: that the coordinator really does
plan, run and import the queries without being asked. One version proves that.
33 s to 6.6 s.

The underlying cost is fixed too, and it was never the number of tests. The
fixture rc now caps the Bazel server at `-Xmx1g` and sets `max_idle_secs=15`, so
a server does not linger for hours after the test that started it. Four genrules
do not need more. Every existing sweep still passes.
