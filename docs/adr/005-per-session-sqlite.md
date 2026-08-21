# ADR-005: One SQLite database per session, plus a catalog database

Status: accepted (2026-08)

## Context

Sessions are independent artifacts: a Tier 3 build produces tens of millions
of rows, sessions are created and deleted whole, and users will accumulate
hundreds. A single shared database would grow without bound, make deleting a
session a slow DELETE storm, entangle unrelated sessions in one WAL, and
turn schema migration into an all-or-nothing event across every session ever
captured.

## Decision

- **One SQLite database per session** holding that session's normalized,
  queryable data. Deleting a session is deleting its directory.
- **One small catalog database** for the app: the session list, states,
  labels, and settings — nothing per-event.
- **Journals and memory-mapped indexes live outside SQLite** as flat files
  in the session directory (see docs/session-format.md): the raw journal
  (ADR-004) because it is an append-only byte log, and the CSR graph /
  temporal indexes (ADR-006) because graph traversal and timeline scans need
  mmap-speed sequential and random access that a B-tree cannot give at
  Tier 3 scale.

SQLite is the query engine for filter/sort/aggregate workloads; flat mmap
files are the engine for graph and time-ordered scans. Neither is asked to
do the other's job.

## Consequences

- Schema versioning is per-session: an old session opens with the schema it
  was written with, and upgrades are an explicit re-index from the journal.
- Cross-session comparison queries must ATTACH the involved databases or go
  through application-level merge — accepted, comparison involves few
  sessions at a time.
- The session directory, not any database row, is the unit of backup, copy,
  and delete.

## Revisit when

Cross-session analytics become a first-class feature and per-session ATTACH
demonstrably cannot serve them.
