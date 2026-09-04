# Phase 7 audit

> Historical audit record. Gradle commands and build references below are
> nonauthoritative evidence from before ADR-009. The repository is Bazel-only;
> use README.md and docs/implementation-status.md for current instructions.

The mechanical checks from Phases 4, 5 and 6, run again. **Sixteen findings,
all fixed.** Eleven from counting call sites, two from measuring instead of
assuming, one from writing the exit-criteria test, and two in the documentation
itself.

Phase 7 added no schema, so the check that found dead database columns in
earlier phases had nothing to look at. The call-site count did nearly all the
work this time, and it found a worse class of thing than in Phase 6: not merely
unread values, but three promises the code had made and not kept.

---

## From counting call sites

The check is: for every `public` method added this phase, count references in
main source that are not its own declaration. Eleven came back at zero.

### 1–4. Four methods nothing called, anywhere

`GraphExtract.nodeArray` and `GraphExtract.nodeRange` were written "for the
layout stage" and the layout stage never used them — it builds its own position
index, which is what makes it linear. `LimitEstimate.isUpperBound` was `!exact()`
spelled longer. `GraphExport.formats` was `Format.values()` spelled longer.

All four deleted. Two of them had a test, which is how they survived being
written: a test is not a caller, and treating it as one is how an API grows a
wing nobody lives in.

### 5. A method pointing at a method that did not exist

`GraphLayoutService.submit` refuses the two path modes, because a path's nodes
come from a search and cannot be recomputed from a request. Its error message
told the caller to use `submitPath` instead.

There was no `submitPath`. Anyone who followed the instruction would have found
nothing, and the only reason nobody had is that nothing called it either — see
the next finding.

`submitPath` exists now. It is deliberately not cached: a path is tens of nodes,
laying one out is microseconds, and a cache key would have to include the node
list, which grows without bound as a user tries pairs.

### 6. Paths could be extracted but never drawn

`GraphExtract.path` had five tests and no main-source caller. Plan 13.5 lists
"path between two actions" and "critical path" as display modes; the trees could
state a path in words and nothing could show its shape.

The mode list excluded both, the service threw for both, and the panel had no
way to supply endpoints. A complete vertical slice that stopped one connection
short of being reachable — the same shape as Phase 6's finding 2, and worth
naming as a pattern: **a deliverable whose last step is in a different class
tends to stop at the class boundary.**

`GraphView.findPath` now draws the path it finds as well as describing it. Both
path modes are in the mode list so a found path can be the current mode, but
they cannot be selected: a mode a user could pick and never satisfy would be a
dead control.

### 7. The join that closed Phase 6's gap was wired to nothing

`GraphQueries.actionIdsByNodeIndex` was added in Phase 7's first commit and that
commit's message said it "closes Phase 6's deferred gap" — the missing
node-index-to-action-id join that stopped the critical path being drawn on the
timeline.

Call sites: zero. The claim was true about the query and false about the
application.

Fixed by wiring it, not by re-wording the claim. Selecting a node on the canvas
now points the timeline at the same action — quietly, without switching cards,
because a view that jumped away on every click would make the graph unusable.
Nodes with no executed action (every test's `TestRunner` in a `build`
invocation) fire nothing rather than firing a zero that would open an unrelated
row.

### 8. Two halves of one view could disagree

Related, and found while fixing the above: picking a node on the canvas left the
dependency trees rooted at whatever they had been showing. Two panes of one view
displaying two different actions is a disagreement a user reads as a bug in the
data rather than in the window.

The canvas now moves the trees. Deliberately through a separate path from
`showNode`, because telling the canvas to redraw would clear the very selection
that triggered it.

### 9. Arithmetic exposed for checking that nothing checked

`GraphClustering.clusteredNodes` and `clusteredEdges` were documented as
"exposed rather than merely asserted in a test: a caller that draws these
numbers should be able to check the arithmetic it is drawing". No caller drew
them. The cluster legend used the graph's totals instead, which are the numbers
the clustering is *supposed* to reproduce rather than the ones it actually
produced.

The legend now sums the boxes on screen. If a clustering ever dropped a node the
legend would disagree with the drawing, which is the point.

`Cluster.isUnknown` was also uncalled; the legend now names the unnamed group
rather than folding it into the count as though it were a package.

### 10. A record whose `equals` could not do its job

`GraphLayout.Result` was a record with two `double[]` components. Two
consequences, neither intended.

Its generated `equals` compares arrays by reference, so two identical layouts
compare unequal — exactly the claim the determinism test exists to make, and it
would have quietly passed for the wrong reason had it been written that way.

And a record mandates public accessors for its components. Handing out a
`double[]` either copies fifty thousand doubles for a caller who wanted one
coordinate, or lets that caller move a node. The overrides copied; nothing in
main source called them.

`Result` is a final class now, with `xAt` and `yAt` and no bulk accessor at all.
A test asserts by reflection that no public method returns `double[]`, so the
accessor cannot come back by accident.

### 11. Public where package-private was the honest visibility

`GraphCanvasPanel.canvas()` was public and called only by tests. Nothing outside
`ui.graph` should reach past the panel to the component it manages. Demoted.

`GraphCanvasPanel.onExport` was a listener nobody registered; the panel's own
status line already reports what an export wrote. Deleted, and its tests read
that line instead.

---

## From measuring

### 12. A fitted frame at the plan's own limits took 366ms

Plan 13.6 sets the default detailed-layout limit at 50,000 nodes and 200,000
edges, and the sixth exit criterion is that panning stays responsive. Both
numbers were in the code before either was measured.

A synthetic 50,000-node, 200,000-edge DAG fitted to a 1600×1000 window painted a
frame in **366ms**. Not responsive — roughly three frames a second while
dragging.

Two causes, both in the edge loop. `setColor` was called once per edge, and at
two hundred thousand edges the state changes cost more than the lines. And every
edge was drawn at far zoom, where two hundred thousand hairlines resolve to a
grey smear that shows nothing.

Batching the colour into one bulk pass and one highlight pass, and applying plan
13.6's far-band rule (aggregate edge thickness, not individual edges) above a
30,000-edge budget, brings the same frame to **35ms**. A near-zoom frame, where
every visible edge *is* drawn along with labels, is **4ms** — culling means the
detailed view is the cheap one.

The budget is reported through `hiddenDetail()` rather than applied silently. A
blank area that looked edgeless would be a claim about the build, and a false
one.

Other measurements, for the record: extract, layout, index and label 50,000
nodes takes **14ms**; 20,000 hit tests over those 50,000 nodes take **2ms**.

### 13. Layered layout was quadratic in the worst case

The first layered implementation assigned layers by relaxing every edge
repeatedly until nothing changed. That settles in one pass on a chain, because
the edges happen to arrive in order, which is exactly why it looked fine. On an
adversarial ordering it is O(V·E), and at the 50,000-node limit that is not a
layout, it is a hang.

Replaced with Kahn's algorithm over a locally-built adjacency index: one pass
over nodes, one over edges. The radial layout had the same shape of problem —
it rescanned the whole edge list per node — and got the same treatment.

Caught before it shipped rather than by the audit, but recorded here because the
first version compiled, passed every test, and was wrong about the only thing
that mattered at scale.

---

## From writing the exit-criteria test

### 14. Every graph's first view was fitted to a one-pixel window

Criterion 1 is "small subgraphs render in full detail", and the test asserted the
zoom lands in the band that draws labels. It landed in the far band instead.

`setModel` fits the new graph to the window. On the first session opened the
window has not been laid out yet, so `getWidth()` is zero — and `fitToView`
clamped that to one pixel and fitted to it. Every graph's first appearance was at
an absurd zoom until the user pressed Fit.

The test found it because a headless test never lays anything out, which is the
same state as the real first paint. `fitToView` now defers when there is nothing
to fit into and retries on the first paint that has real bounds, which is always
before anything reaches the screen.

---

## From reading the status document

### 15. The file that says what exists said three finished phases did not

`docs/implementation-status.md` opens with "This file states what exists in the
tree, not what is planned to exist. Update it in the same change that lands the
work." Its summary table listed Phases 4, 5 and 6 as **Not started**.

The same file contains a Phase 4 checklist, a Phase 5 checklist, a Phase 6
checklist, all three sets of exit criteria and all three audit summaries. The
table and the body of the document disagreed about the same repository.

Nothing downstream depends on the table, which is presumably why three phases
went past without anyone noticing — a summary that nothing reads is a summary
nothing checks. It is also the first thing a reader sees, so it was the most
load-bearing wrong sentence in the docs.

Corrected, and Phase 7 added in the same edit. Worth recording as its own class
of finding: **the checks so far all read code. This one needed reading the
prose, and the prose was the part that was wrong.**

### 16. The module map described a dependency that has never existed

Found by looking for the same class of thing once finding 15 had named it.
`docs/architecture.md` said `analysis-core` "Consumes `graph-core` and
`storage-sqlite` read APIs".

It does not, and never has. `analysis-core/build.gradle.kts` declares
`core-model` and `graph-core`; no file in the module names anything under
`storage`. The absence is deliberate and load-bearing — it is what lets the
extraction and layout code be tested without a database, and what let Phase 7
put `GraphExtract` and `GraphLayout` there while the queries that feed them
stayed in `storage-sqlite`. A reader who believed the map would have concluded
the opposite about where new graph code belongs.

Corrected, and the reason for the boundary written down so the next edit does
not quietly cross it.

---

## What the checks said was fine

- **No schema changes**, so no dead columns to find.
- **No skipped or disabled tests.** Zero `assumeTrue`, `@Disabled` or `@Ignore`
  in any Phase 7 test. Phase 7 needs no Bazel fixture: nothing in it depends on
  Bazel's behaviour, so rule 18 does not apply and the user's instruction to keep
  validation cheap costs nothing here.
- **No unknown-as-zero.** No `return 0`, `: 0` or `orElse(0)` anywhere in the new
  code. Unknown duration is `-1` and surfaces as `OptionalLong.empty`; an untimed
  action reads "not timed in this session" in the panel and `(not timed)` in an
  exported file.
- **Painting cannot reach SQLite.** `GraphCanvas`, `GraphModel`,
  `GraphSpatialIndex`, `GraphTransform` and `GraphColours` hold no field of any
  `java.sql` or `storage` type — checked by reflection in
  `GraphPaintIsolationTest`, which also asserts the complement, that
  `GraphLayoutService` *does*, so the split is a design rather than a
  coincidence. The same test asserts the canvas holds no `GraphLayoutService`
  and no `ExecutorService`, so plan 17.7's other prohibition — never lay out on
  the EDT — is structural too.
- **Nothing silently truncated.** Every bound reports itself: `GraphExtract`
  carries `hitLimit` and the graph totals, `GraphExtract.whole` refuses rather
  than truncating, `GraphClustering` refuses above its cluster limit rather than
  dropping groups, `GraphLayout` returns nothing rather than a half-placement,
  and the canvas's one transient omission is named by `hiddenDetail()`.

## The check that became a test

The call-site sweep is a twenty-line script that lists every `public` method in
the phase's files and counts non-declaration references in main source. It found
eleven things here and four in Phase 6.

Two of its findings are now permanent tests rather than a script's output:
`GraphLayoutTest` asserts by reflection that no public method of
`GraphLayout.Result` returns `double[]`, and `GraphPaintIsolationTest` asserts
what the painting classes can and cannot reach. The rest still needs the script,
because "nobody calls this" is not a property a unit test can state about itself.
