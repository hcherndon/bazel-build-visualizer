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

## What the graph-tab rework added (2026-08-23)

### The indexes are now actually built

Phase 5 built the machinery and Phase 7 drew from it, but nothing in
production ever *ran* it: `CaptureCoordinator` imported the query output and
stopped, so `graph_indexes` stayed empty on every real session and the canvas
reported "no action graph". The capture's finalization now derives the action
edges and builds the CSR indexes — declared, observed, and the
configured-target label graph — into the session's `indexes/` directory,
quietly: a failure costs the graph view and is named in the capture warnings,
never the capture. Sessions imported from a BEP file alone have no graph to
index, and say so; that is a property of the source, not a failure.

### The configured-target label graph has an index

The third indexed graph, registered in `graph_indexes` under kind
`CONFIGURED_TARGETS`. Its node is a **label**, not a `(label, configuration)`
pair: a label analysed in several configurations is one node, and identical
dependencies seen through several configurations collapse to one edge. This is
the graph closest to `bazel query 'deps(//foo)'`, and the source selector says
exactly that when it is shown.

**Node numbering is derived, not stored.** A label's dense node id is its
position in the sorted list of distinct `label_id`s in
`configured_target_nodes` — a pure function of the imported rows, computed
identically by `GraphIndexBuilder.configuredLabelUniverse` at build time and by
`GraphQueries` at query time, so there is no persisted mapping to go stale and
no schema change. Labels are interned append-only, so an id never moves under
a session.

**Edges that cannot land are counted, not dropped.** `rule_input` names source
files and labels outside the analysed universe; those have no node, exactly as
the action graph excludes source artifacts with no producer. The build result
carries the excluded-edge count, and the selector's description states the
exclusion.

Forward remains producer-to-consumer for all three indexes: the forward
neighbours of a node are the things that need it, in both graphs, so every
traversal answers the same question the same way round.

### Direction, corrected

"Dependencies" now walks the reverse index and "reverse dependencies" the
forward one. The previous binding was inverted end to end — the "Depends on"
tree and the Dependencies canvas mode listed *dependents* — and is pinned
against regression by `GraphExtractTest`, `GraphQueriesTest` and
`TreeViewSourceTest` (`GraphViewSourceTest` until the Graph/Tree split).

## What the Graph/Tree split added (2026-08-24)

The one Graph card is two: **Tree** (`NavEntry.TREE`, the Phase 5 trees,
search and path-between-nodes — `TreeView`, formerly `GraphView`) and
**Graph** (`NavEntry.GRAPH`, the Phase 7 canvas — `GraphExplorerView`
hosting the unchanged `GraphCanvasPanel` machinery). Each card keeps its own
graph-source selector, because each is a separate statement about which graph
is on screen. No schema change, no new graph kinds.

### Node weights (`GraphWeight`, computed by `GraphWeights`)

The Graph card's weight selector drives node radius, edge thickness and the
colour ramp — visual encoding only. Positions come from the deterministic
layouts (still no force-directed layout at any size), so the layout cache key
is unchanged and re-selecting a weight re-renders without re-layout.

- **Immediate deps / rdeps** — CSR degree off the offsets array, O(1) per
  node.
- **Transitive deps / rdeps** — two bounded shapes, because a transitive
  closure remains forbidden (plan 13.3). *Exact over the drawn subgraph*:
  per-node reachability over the extracted nodes and edges, bounded by the
  extraction's own limits plus `SUBGRAPH_TRANSITIVE_WORK_BUDGET`; nodes past
  the budget are unknown and the legend says so. *Budgeted over the whole
  graph*, for the selected node only: a BFS over the full CSR index under
  `GLOBAL_TRANSITIVE_NODE_BUDGET`, reported as "≥N (budget reached)" when it
  gives up — a lower bound stated as one.
- **Output size** — `declared_actions.primary_output_id` joined to
  `artifacts.size_bytes` (`GraphQueries.outputSizesByNodeIndex`). An unsized
  output is unknown, never zero: "empty file" and "never measured" are
  different facts.
- **Inputs** — presented as the immediate dependency count and labelled as
  exactly that, because the session stores no distinct raw-input count per
  node and a fabricated one with a truthful name would be worse than the
  honest proxy.

Unknown weights draw grey at base size — never the smallest, coldest node —
and every scale sentence under the drawing names what the colours mean and
how many nodes have no value (`GraphWeightEncodingTest`,
`GraphCanvasPanelTest`).

## What the Graph-card canvas polish added (2026-08-24)

Six rendering and navigation refinements; no schema change, no new graph
kinds, and the deterministic layouts are untouched.

**Per-action display labels.** The canvas used to name an action-graph node by
its owning target's label, so every action under one target read as the same
string. `GraphQueries.displayLabelsByNodeIndex()` composes "Mnemonic — output
basename" (`mnemonics` joined through `declared_actions.mnemonic_id`,
`artifacts.path` through `primary_output_id`), degrading honestly: no output
leaves the mnemonic alone; no mnemonic falls back to the target label, then
the basename; nothing at all stays null so the canvas says "(name not
recorded)". Label-graph nodes keep their target labels — a label *is* the node
there — and the complete export keeps target labels too, because its `label`
column must keep meaning the target.

**Label declutter and label-aware Fit.** Within a zoom band, a label that
would paint over an already-painted label is skipped under a deterministic
priority (selected, hovered, heavier, lower node index); the count is
inspectable and a selected label always paints. Fit reserves room for the
label text visible at the resulting zoom — all labels in the near band, the
selection's in the medium band — capped at `MAX_LABEL_FIT_FRACTION` of the
window and clamped so the reservation can never drop the view into a coarser
band where the reserved-for labels would not paint; both caps are stated on
screen when hit (see `docs/limits.md`).

**Node dragging is a view-layer overlay.** Dragging a node moves a world-space
offset held by the canvas alone; `GraphLayout.Result`, `GraphSpatialIndex` and
the cached `Rendered` are shared with the layout cache and are never written.
Hit testing and marquee selection consult the overlay, edges follow their
nodes, offsets survive a weight restyle (same layout, by design), reset on any
new layout, and "Reset positions" — toolbar and context menu — clears them
explicitly. A press on empty canvas still pans.

**Direction arrowheads.** Edges are stored producer→consumer, and at the near
band — where an edge is individually distinguishable — each drawn edge gets an
arrowhead at its consumer end, pulled back to the node's rim. Coarser bands
and mid-drag frames draw none, exactly like the other detail the canvas
suspends, so the 50,000-node paint budget holds.

**Find and Browse.** The Graph card's Find field lists up to `FIND_LIMIT`
as-you-type matches (queried off the EDT), each named exactly as the canvas
draws it, and choosing one lands on that exact node — replacing the old silent
first-substring-match jump. A Browse toggle opens a filter-plus-tree listing
(the schema-browser pattern) of up to `BROWSE_LIMIT` nodes grouped by package,
its root naming the graph's total so a truncated listing cannot read as
complete.
