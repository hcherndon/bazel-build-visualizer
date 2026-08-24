# Database schema

Explicit SQL only (ADR-006). DDL lives in `storage-sqlite` as versioned
migrations; this page is the inventory and the record of where it departs from
the plan's original sketch (plan 10.7) and why. Two databases exist (ADR-005):
the app **catalog** and one **session** database per session.

Conventions: WAL mode, single writer connection, keyset pagination for all
UI-facing queries (no OFFSET), indexes created after bulk load rather than
maintained during it, covering indexes added only against measured query plans.

## Reading the schema of a session you have open

This page is the inventory and the reasoning. It is **not** generated, so the
authority on what a particular session file contains is the file itself: a
session written by an older build carries an older schema, and only the file
knows which.

The application reads it at run time. The **Query** card's schema tree is built
from `sqlite_master` and `PRAGMA table_info` on the open session — every table,
every view, every column with its declared type, its NOT NULL constraint and
its place in the primary key. The same information is reachable as data:

```sql
SELECT name, type FROM sqlite_master WHERE type IN ('table','view') ORDER BY 1;
SELECT * FROM pragma_table_info('actions');
```

The tree carries no row counts. Counting every table on a five-million-action
session is a scan per table, and a count shown before it had been taken would
be a zero standing in for "not known yet" (rule 11); `SELECT COUNT(*)` in the
editor beside it answers that in one query.

### Temporary views (the one thing a query can create)

The Query card admits exactly one non-read statement: `CREATE TEMP VIEW <name>
AS <select>`. A temp view lives in the connection's **temp schema**, which is a
different database from the session file — per-connection, gone when the
connection closes, incapable of holding rows — so it exists on the query tab
that created it and on no other, and the session file is never touched (the
main database stays open `SQLITE_OPEN_READONLY`, which is a guarantee rather
than a filter). The schema tree lists them as *"(temp view, this tab only)"*,
read from `temp.sqlite_master` with schema-qualified `table_info`, so a temp
view that shadows a main table's name is described as itself rather than as
the table it shadows.

Every other `CREATE` — TABLE, non-temp VIEW, INDEX, TRIGGER, VIRTUAL TABLE,
and the TEMP spellings of TABLE and TRIGGER — is refused by the statement
filter (`ReadOnlySql`), and would be refused by the open mode anyway for the
main schema.

**Saved views** persist these definitions across sessions: name plus SELECT
body as a `.sql` file under the settings directory
(`settings/views/<name>.sql` with an `index.json`), replayed onto each query
tab's connection when it opens and whenever the saved set changes. A shipped
pair of examples (`actions_with_labels`, `mnemonic_totals`) resolves the
`labels`/`mnemonics` interning tables, which is the join everyone writes
first.

## Catalog database (`catalog.db`)

| Table | Purpose |
|---|---|
| `sessions` | one row per known session: SessionId (text UUID — row ids never leak as identity), state, label, created/finished timestamps, directory name |
| `settings` | key/value app settings |
| `schema_metadata` | catalog migration bookkeeping |

## Session database (`derived/session.db`)

The session database is derived. The journal is the source of truth (ADR-004),
so every migration prefers re-indexing from raw bytes over an in-place `ALTER`,
and every normalized row carries a `bep_event_id` back to the event that
produced it.

### Schema v1 — raw events (Phase 1)

| Table | Purpose |
|---|---|
| `schema_metadata` | applied schema version |
| `session_info` | session uuid, state, created/finalized time, app version |
| `capture_sources` | every file or stream the session ingested, with sha256, size and completeness |
| `event_streams` | one row per BES/BEP stream: sequence bounds, contiguity, duplicate and gap counts |
| `bep_events` | one row per event: type, id hash, `last_message`, decode status, and the journal segment/offset/length holding its raw bytes |
| `bep_event_ids` | the distinct event identifiers, by hash |
| `bep_event_edges` | announced parent → child edges |
| `bep_announced_missing` | children announced but never delivered |
| `import_diagnostics` | severity, code, message, anchored to a journal offset where one applies |
| `strings` | interning table for repeated text in the raw layer |

### Schema v2 — normalized entities (Phase 3)

Shapes are measured, not assumed: `docs/bep-content.md` records what Bazel
6.5.0, 7.6.1, 8.4.1 and 9.2.0 actually emit, and `SchemaV2` cites the finding
behind each non-obvious column.

| Table | Purpose |
|---|---|
| `build_invocation` | single row: bazel version, command, directories, options description, start/finish, exit code, whether the stream reached `lastMessage` |
| `configurations`, `configuration_make_variables` | one row per configuration id, including ids that were referenced but never declared |
| `targets` | one row per (label, aspect), created on `TargetConfigured` |
| `configured_targets` | one row per (target, configuration), created on `TargetComplete` or an `aborted` |
| `target_tags` | tags, recording which event supplied each |
| `target_output_groups` | per output group, its root file set and the `incomplete` flag |
| `target_directory_outputs` | tree artifacts a target declared |
| `artifacts` | files and directories by exec-root-relative path |
| `depsets`, `depset_children`, `depset_files` | the `NamedSetOfFiles` DAG, stored as edges |
| `actions` | one row per observed action, keyed on its primary output |
| `tests`, `test_attempts`, `test_logs` | test verdicts, every attempt, and the log URIs |
| `build_metrics`, `mnemonic_metrics`, `runner_counts`, `cache_miss_details`, `garbage_metrics` | what `BuildMetrics` reported, all nullable |
| `aborted_events` | every `aborted`, across all four id kinds it rides |
| `progress_output` | an index into the raw progress events that carry stdout/stderr |
| `labels`, `mnemonics` | interning tables for the two values that repeat at action scale |

### Departures from the plan's sketch

The original inventory listed six tables that are not in v2. Each absence is a
decision, not an oversight.

- **`action_inputs` and `action_outputs`** — the BEP reports neither.
  `ActionExecuted` carries a `primary_output` File with a uri and nothing else,
  and no list of either kind; the primary output path on the action row is the
  whole of what the stream says. Both arrive in Phase 4 from the execution log,
  and the tables arrive with them. Empty ones now would read as "this build's
  actions had no inputs and produced nothing".
- **`problems`** — split into `aborted_events` (which needs its own table because
  abort volume scales with target count: 12,000 rows from a single interrupt),
  the `failure_category`/`failure_message` columns on `actions` and
  `configured_targets`, and v1's `import_diagnostics` for problems with the
  capture rather than with the build.
- **`test_results`** — became three tables, because a test verdict, an attempt
  and a log are three cardinalities and `FLAKY` exists only at the top one.
- **`enrichments`** — Phase 4, when there is an enricher to record.
- **`raw_journal_map`** — unnecessary at row-group granularity. v1's
  `bep_events` already carries segment, offset and length per event, and every
  v2 row carries `bep_event_id`, so the trace from any normalized row to its raw
  bytes is one join rather than a range lookup.
- **`invocation`** — present as `build_invocation`. Probed capabilities live in
  the session manifest rather than the database, because the capture path needs
  them before a database exists.

One table gained a column the sketch did not anticipate: `configurations.declared`.
Bazel references the configuration id `system` on every build without ever
publishing a `Configuration` event for it, so the normalizer inserts a row
marked undeclared rather than either dropping the referencing actions or giving
up integer foreign keys.

### Schema v3 — the test summary's timing columns renamed (Phase 3)

`tests.first_start_micros` and `last_stop_micros` became
`bazel_first_start_micros` and `bazel_last_stop_micros`, because the summary's
window excludes failed retries and the old names claimed a provenance the
values did not have.

### Schema v4 — attempts and the profile (Phase 4)

Eleven tables. Every one is written by the enrichment importers and by nothing
else, which is what makes plan 21.4's "a failed enrichment must not invalidate
BEP" structural rather than careful.

| Table | Holds |
|---|---|
| `enrichment_tasks` | one row per enrichment, with how it ended and what the user lost if it failed |
| `input_sets`, `input_set_children`, `input_set_files` | the spawn-input DAG, unflattened |
| `action_attempts` | one row per spawn — not per action |
| `attempt_outputs` | what each spawn produced, and what it declared and did not |
| `attempt_env_vars` | the environment, with secret-looking values withheld and marked |
| `profile_metadata` | the profile's absolute anchor, **with what the anchor means** |
| `profile_threads` | thread ids to names |
| `build_phases` | phases as the profile reported them, ends derived and flagged |
| `profile_spans` | attributable action spans, joined to actions by primary output |
| `profile_counters` | the ten resource series |
| `bazel_critical_path` | Bazel's own answer, deliberately unjoined |

Three shapes are worth knowing before reading a query:

**`action_attempts.action_id` is nullable and `correlation` says why.** Four
different facts produce a null and only two of them are problems. See
`docs/phase4-contracts.md` §2.

**`profile_metadata` stores the anchor's meaning and uncertainty, not just its
value.** On Bazel 6.5.0 and 7.6.1 the anchor is a start floored to the whole
second, published under a key named for the finish.

**There is no `action_coverage` table.** How many actions have attempt data is
a count over `action_attempts`. A stored copy is one more number that can
disagree with its rows.

### Schema v5 — the dependency graph (Phase 5)

Ten tables. `graph_sources` is the one to read first: it says which query
produced a graph, whether it succeeded, and — the load-bearing part — whether
its configurations were the build's.

| Table | Holds |
|---|---|
| `graph_sources` | one row per query, with its configuration match and failure reason |
| `declared_actions` | what `aquery` said analysis produced, linked to executions where they exist |
| `declared_action_inputs` / `_outputs` | an action's input sets and its outputs |
| `graph_depsets` + two edge tables | the declared input DAG, unflattened |
| `configured_target_nodes` / `_edges` | what `cquery` said, edges between labels |
| `action_edges` | derived producer-to-consumer, keyed by derivation |
| `graph_indexes` | the registry for the CSR files beside the database |

Four shapes worth knowing:

**`declared_actions.action_id` is nullable and usually set.** Declared and
executed are different populations and neither contains the other.

**`action_edges` is keyed `(producer, consumer, derivation)`.** The same pair
can be both `DECLARED` and `OBSERVED`, and the two must never be summed.

**`declared_actions.node_index` is not the row id.** Row ids grow across
re-imports; a CSR is two arrays indexed from zero with no room for gaps.

**`is_executable` holds 1 or NULL and never 0.** Bazel 6.5.0 emits the field
for no action, but it is a proto3 bool without presence, so nothing downstream
of the parser can tell "this version never says" from "not executable".

## Sensitive-field inventory

`docs/privacy.md` commits to tagging every column that can carry
user-identifying or secret data, maintained as the schema lands. This is the
schema-v2 half of that inventory. The raw journal stays faithful (ADR-004) and
is sensitive at rest; what changes per row below is what the UI masks by
default and what export must redact.

| Column | What it can carry | Treatment |
|---|---|---|
| `build_invocation.working_directory`, `workspace_directory` | absolute paths, which on macOS and Linux begin with the user's home directory and therefore their account name | redact on export; shown in the UI |
| `build_invocation.options_description` | the whole effective option string, including `--remote_header` values and any credential passed as a flag | mask by default; redact on export |
| `actions.command_line` | the action's argv verbatim — credentials appear here when a rule passes one as an argument | mask by default; redact on export |
| `actions.failure_message` | Bazel's own text, which routinely embeds the failing command line and a sandbox path | redact on export |
| `actions.stdout_uri`, `stderr_uri` | absolute paths into the output base | redact on export |
| `artifacts.uri` | absolute path into the output base or the source tree | redact on export |
| `artifacts.path`, `path_prefix`, `name` | workspace-relative paths, which carry internal project and directory names | redact on export when path redaction is enabled |
| `labels.value` | internal repository, package and target names | redact on export when path redaction is enabled |
| `test_logs.uri` | absolute paths into the output base | redact on export |
| `configuration_make_variables.value` | make variables, which frequently hold paths | redact on export |
| `target_tags.tag` | user-authored strings, so arbitrary | redact on export |
| `aborted_events.description` | Bazel's own text | redact on export |

Everything else in schema v2 is structural — counts, timestamps, outcomes,
opaque configuration ids, mnemonics — and carries nothing about the user.

Two columns look sensitive and are not. `configurations.mnemonic` and
`platform_name` are Bazel's own vocabulary. `actions.primary_output` is a
workspace-relative path and is covered by the path row above; it is also the
action's identity, so redacting it in place would leave the export unable to
join its own rows, and export replaces it with a stable pseudonym rather than
removing it.
