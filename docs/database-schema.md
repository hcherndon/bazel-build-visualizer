# Database schema

Explicit SQL only (ADR-006); DDL will live in `storage-sqlite` as versioned
migration scripts, and this page documents intent and inventory. Two
databases exist (ADR-005): the app **catalog** and one **session** database
per session. Schema arrives in Phase 3 (session) and Phase 1 (catalog);
until then this is the planned table inventory (plan 10.7).

## Catalog database (`catalog.db`)

| Table | Purpose |
|---|---|
| `sessions` | one row per known session: SessionId (text UUID — row ids never leak as identity), state, label, created/finished timestamps, directory name |
| `settings` | key/value app settings |
| `schema_version` | catalog migration bookkeeping |

## Session database (`derived/session.db`)

| Table | Purpose |
|---|---|
| `invocation` | single-row build metadata: commands (original + effective), bazel version string, probed capabilities, exit code |
| `targets` | configured targets seen in the stream (label, configuration, kind, outcome) |
| `actions` | one row per observed action: mnemonic, owner target, timing, runner, cache state, counts/bytes (unknown values are NULL, never 0) |
| `artifacts` | files referenced by actions/outputs (path-interned) |
| `action_inputs` / `action_outputs` | action-artifact membership at SQL granularity (bulk edge traversal uses CSR files instead — ADR-006) |
| `problems` | errors, warnings, aborted events, with source event references |
| `test_results` | per-test-run status, timing, attempts |
| `strings` | interning table for paths/labels/mnemonics referenced by integer id |
| `enrichments` | which enrichers ran, versions, completeness (docs/metric-definitions.md) |
| `raw_journal_map` | journal offset ranges backing each normalized row group, so any row can be traced to raw bytes |
| `schema_version` | session migration bookkeeping — migrations prefer re-index from journal over in-place ALTER |

Conventions: WAL mode, single writer connection, keyset pagination for all
UI-facing queries (no OFFSET), covering indexes added only against measured
query plans.
