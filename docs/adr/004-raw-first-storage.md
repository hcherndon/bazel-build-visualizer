# ADR-004: Raw-first capture — the journal is the source of truth

Status: accepted (2026-08)

## Context

BEP streams arrive while a build is running, can be cut off mid-build
(cancelled builds, crashed Bazel servers, killed viewers), and are produced
by multiple Bazel versions whose message shapes drift. Any normalization,
enrichment, or indexing step can have bugs. If normalization happens inline
during capture, a normalizer bug or an unknown message silently destroys
data that can never be re-fetched — the build already happened.

## Decision

Capture writes every received event to an append-only raw journal *before*
any decoding beyond framing, and the journal — not the SQLite database, not
any index — is the session's source of truth.

- The journal records bytes as received (plus arrival metadata), so unknown
  or malformed messages are preserved, not dropped.
- Normalization, enrichment, and indexing are separate, re-runnable stages
  that read the journal. Re-running them after a code fix upgrades old
  sessions in place; nothing is lost to a v1 bug.
- A session whose capture died mid-stream (`INCOMPLETE`, `CORRUPT_PARTIAL`
  in `SessionState`) is still openable: whatever reached the journal is
  inspectable.

## Consequences

- Disk cost: raw journal plus derived stores coexist per session. Accepted;
  disk is cheap, builds are not repeatable.
- Every derived store (SQLite DBs, CSR indexes) must be rebuildable from the
  journal alone; a derived store may be deleted at any time without data
  loss.
- Capture-path code has one job — durable append — and must stay boring:
  no parsing decisions, no schema knowledge, no filtering.

## Revisit when

Journal storage overhead is measured to dominate session size in practice
and a lossless compaction format is designed — never in favor of
normalize-on-ingest.
