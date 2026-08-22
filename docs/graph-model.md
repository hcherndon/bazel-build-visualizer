# Graph model

How dependency graphs are stored and traversed. The six distinct graph
representations and their vocabulary are defined in
[architecture.md](architecture.md); this page covers the physical CSR index
format owned by `graph-core` (ADR-006). Implementation arrives in **Phase 5**,
whose task list includes "Build forward/reverse CSR indexes"; the visualization
that reads it is Phase 7. The Phase 0 graph spike exercises a prototype of this
layout at Tier 2/3 scale.

## CSR file format sketch (plan 13.2)

One file per direction (forward = producer→consumers, reverse =
consumer→producers), written once at indexing time, memory-mapped read-only
afterwards. All integers little-endian, fixed-width.

```
header (64 bytes)
  magic        u64   'BBVCSR1\0'
  formatVersion u32
  flags        u32   (bit 0: reverse direction)
  nodeCount    u64   N
  edgeCount    u64   E
  checksum     u64   xxhash-style digest of the two arrays
  reserved     u64x2

offsets array
  (N + 1) x u64      offsets[i]..offsets[i+1] index the neighbor slice of
                     node i; offsets[N] == E. Monotone non-decreasing.

neighbors array
  E x u32 or u64     node ids; width chosen by nodeCount at write time and
                     recorded in flags. Sorted within each slice.
```

Node ids are dense indexes assigned at indexing time; the mapping from node
id to domain identity (action, artifact) lives in the session SQLite
database — since Phase 3 that is the `actions`, `artifacts` and `depsets`
tables of schema v2, not the `strings` table, which holds only the raw layer's
interned text. Degree of node
`i` is `offsets[i+1] - offsets[i]` — degrees are never stored separately.

Construction streams edges (two passes: count, then fill) so peak memory is
the offsets array plus a bounded write buffer — never one object per edge.
Corrupt or version-mismatched files are discarded and rebuilt from the
journal (ADR-004); there is no in-place migration.

The temporal index (`temporal.idx`) is a sibling flat format — time-sorted
span records for timeline queries — and will be specified here alongside
**Phase 6**, which is where the timeline and its LOD index are built.
