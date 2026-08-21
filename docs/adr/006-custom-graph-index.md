# ADR-006: No ORM; custom memory-mapped CSR graph indexes

Status: accepted (2026-08)

## Context

Tier 3 sessions hold ~5M actions and ~100M dependency edges. An ORM (or any
object-per-row mapping) would allocate one Java object per edge — hundreds
of bytes each with headers and references — putting the graph alone at tens
of gigabytes of heap. It would also hide the SQL, and query shape *is* the
performance model for this application.

## Decision

- **No ORM, ever.** All SQLite access is explicit SQL through JDBC, with
  results read into primitive arrays or consumed streaming. Row-mapped
  object graphs for bulk data are forbidden by project rule.
- **Graphs are stored as custom compressed-sparse-row (CSR) indexes in
  memory-mapped files** (format sketch in docs/graph-model.md): a node
  offset array plus a packed neighbor array, forward and reverse, written
  once at indexing time and mmap'd read-only afterwards.
- One-object-per-edge (and per-event) representations are forbidden for
  large data anywhere in the codebase — primitive arrays and flat buffers
  only. Objects are fine for the small stuff (the row a user clicked).

## Consequences

- Traversals (critical path, dominators, reachability) run over `int`/`long`
  arrays with no GC pressure and near-memory-bandwidth throughput; a Tier 3
  edge set costs ~1 GB mapped, not tens of GB of heap.
- We own an on-disk format: it needs a version header, an integrity check,
  and rebuild-from-journal as its only migration story (cheap, per ADR-004).
- SQL lives in the code as SQL — reviewable, EXPLAIN-able, no query
  generator surprises.
- More upfront implementation work than an off-the-shelf mapper; that work
  is the product.

## Revisit when

Never for the ORM half. The CSR format may evolve (compression, sharding)
behind the graph-core API if Tier 3 measurements demand it.
