# Database schema

Explicit SQL only (ADR-006). DDL lives in `storage-sqlite` as versioned
migrations; this page is the inventory and the record of where it departs from
the plan's original sketch (plan 10.7) and why. Two databases exist (ADR-005):
the app **catalog** and one **session** database per session.

Conventions: WAL mode, single writer connection, keyset pagination for all
UI-facing queries (no OFFSET), indexes created after bulk load rather than
maintained during it, covering indexes added only against measured query plans.

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
