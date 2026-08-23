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

## What Phase 5 built (2026-08-22)

Two of the six graph kinds now have data behind them.

**`DECLARED_ACTIONS`**, from `aquery --output=proto`. Nodes are
`declared_actions` rows; edges are derived producer-to-consumer pairs with
`derivation = DECLARED`. Complete with respect to what analysis knew, and an
over-estimate of what execution needed.

**`CONFIGURED_TARGETS`**, from `cquery --output=proto`. Nodes are
`configured_target_nodes`; edges point at labels rather than at configured
targets, because Bazel does not fill the field that would say which
configuration a dependency resolved to.

**`OBSERVED_EXECUTION`** gains edges too, with `derivation = OBSERVED`, derived
from what the execution log says spawns actually read. Its endpoints are still
`declared_actions` rows, so an execution the graph does not declare contributes
no edge — recorded as coverage rather than papered over with a synthetic node.

The three remaining kinds — `BEP_EVENTS`, `TARGETS`, `TEMPORAL` — are unchanged
from Phase 3.

### What may be claimed

A graph may be described as *this build's* only when `ConfigurationMatch` is
`EXACT`, which means the query reported exactly the configurations at least one
configured target was built in. Every other state is shown, labelled, and
warned about.

### Traversals

Forward and reverse BFS, depth- and node-budgeted; bidirectional shortest path,
also budgeted. Running out of budget is reported as its own outcome and never
as "there is no path" — plan 13.3 forbids a transitive closure, so a search has
to be able to give up, and giving up is not an answer.

## What Phase 7 added (2026-08-22)

Phase 5 built the graph; Phase 7 draws bounded pieces of it. No new graph kinds,
no schema change — two lookups and three layers on top.

### Two lookups over the same node index

`GraphQueries.durationsByNodeIndex` weights nodes by time, from either the build
event stream's action window or the execution log's spawn total, with the source
travelling alongside because plan 13.4 requires it. `actionIdsByNodeIndex` maps
a node back to the action that ran, where one did. Together they are the join
Phase 6 deferred: `declared_actions.node_index` on one side, `actions.id` on the
other.

Nodes with no executed action — every test's `TestRunner` in a `build`
invocation — are absent from the map rather than mapped to zero.

### Extraction is always bounded, and always says so

`GraphExtract` returns a subgraph plus the totals it came from. Plan 13.3
forbids a transitive closure, so every traversal takes a node budget; hitting it
is reported as its own fact and never as having finished. `whole()` refuses a
graph that will not fit rather than truncating it, because a "whole graph"
silently showing the first fifty thousand nodes would be the most misleading
view in the application.

### Clustering is an aggregation, not a sample

`GraphClustering` groups by package, target or mnemonic. Every node lands in
exactly one group and every edge is counted, inside a group or between two, so
the group counts sum to the graph's node count and the edge weights sum to its
edge count. Both sums are exposed and both are asserted, because an aggregation
whose parts do not add up to the whole is the kind of wrong that looks right.

A node whose name the import never learned gets a group that says so, and no
such group appears when there is nothing unknown.

### Layout is linear, deterministic and cancellable

Layered by longest path (Kahn's algorithm, so every dependency arrow points
forward), radial by graph distance, linear for paths, grid for cluster
summaries. No force-directed layout at any size. The same subgraph laid out
twice lands in the same place; a cancelled layout places nothing rather than
half a graph, which would look like an answer.

### Drawing limits are rendering decisions, never data ones

The default ceiling is plan 13.6's 50,000 nodes and 200,000 edges. Above it the
view groups itself and offers to raise the limit, narrow the query, or export.
The export is streamed straight from the CSR index and has no ceiling at all —
which is what makes the drawing limit acceptable.
