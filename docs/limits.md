# Limits

Every bound this application applies, what happens when one is reached, and
whether a user can move it.

Plan 24's Phase 10 exit criterion is that **every limit is explicit**. Plan 20.4
says what that means: when a limit is reached, stop safely, preserve raw
capture, show the exact limit, show the total known entities, state whether
results are partial, and offer to raise the limit, aggregate, or export — and
never silently discard or sample.

The `Constant` column is checked by `LimitsDocTest`: every constant named here
must exist, and every numeric default must match. This page cannot drift from
the code without failing the build.

## Display limits

These bound what is drawn. All of them report themselves on screen when
reached, and the first two can be raised from the graph toolbar. One row —
the timeline's sub-row height — is a fixed size rather than a ceiling, and is
listed because it is the unit the sub-row budget above it is counted in: it
is never reached and never negotiated.

| Limit | Constant | Default | When reached |
|---|---|---:|---|
| Detailed graph nodes | `com.holtherndon.bazelviz.analysis.GraphExtract.DEFAULT_NODE_LIMIT` | 50000 | The extraction refuses and reports the exact totals. The canvas offers "Draw it anyway", "Narrow it", "Group instead" and "Export all of it". The default of the toolbar's node-limit spinner, which sets the budget directly. |
| Detailed graph edges | `com.holtherndon.bazelviz.analysis.GraphExtract.DEFAULT_EDGE_LIMIT` | 200000 | As above. |
| Node-limit spinner floor | `com.holtherndon.bazelviz.ui.graph.GraphCanvasPanel.MIN_NODE_LIMIT` | 1 | The spinner will not go lower: a limit of zero draws nothing and would read as "the graph is empty", which is a claim about the build rather than the setting. |
| Node-limit spinner ceiling | `com.holtherndon.bazelviz.ui.graph.GraphCanvasPanel.MAX_NODE_LIMIT` | 5000000 | The spinner will not go higher. Matches the largest planned graph (Tier 3, five million nodes); an unbounded control would read as "no limit", which is a claim plan 13.6 forbids. |
| Cluster boxes | `com.holtherndon.bazelviz.analysis.GraphClustering.DEFAULT_CLUSTER_LIMIT` | 2000 | Grouping refuses with the exact group count and suggests a coarser dimension. Nothing is truncated. |
| Find dropdown matches | `com.holtherndon.bazelviz.ui.graph.GraphExplorerView.FIND_LIMIT` | 12 | The Graph card's Find field lists at most this many as-you-type matches; when there are more, the dropdown's last row says only the first N are listed and typing narrows. Choosing an entry lands on that exact node — nothing is chosen silently. |
| Browse listing entries | `com.holtherndon.bazelviz.ui.graph.GraphExplorerView.BROWSE_LIMIT` | 500 | The Graph card's Browse panel lists at most this many nodes per filter. The summary states "only the first N matches are listed" and the tree root names the graph's total, so a truncated listing cannot read as a complete one. |
| Fit label reservation | `com.holtherndon.bazelviz.ui.graph.GraphCanvas.MAX_LABEL_FIT_FRACTION` | 0.5 | Fit reserves room for the label text visible at the resulting zoom, up to this fraction of the window width. Past the cap — a dense graph of long names — the reservation stops growing and the canvas states that some labels run past the right edge, rather than zooming the nodes to nothing or pretending the text fits. |
| Timeline spans in one viewport | `com.holtherndon.bazelviz.ui.timeline.SpanWindow.MAX_SPANS` | 20000 | The window reports that it is capped and the aggregate bins remain exact; zooming in returns individual spans. Also bounds how many in-flight targets the live band fetches per rebuild; the band's label states "drawing the earliest N of M" when it bites. |
| Timeline sub-rows per lane | `com.holtherndon.bazelviz.ui.timeline.SpanStacking.MAX_SUB_ROWS` | 6 | Overlapping spans in one lane stack top-to-bottom by start time into at most this many sub-rows. Beyond it, further overlapping spans draw into the last sub-row — never dropped — and the status line states the exact overflow count with a suggestion to zoom in. |
| Timeline sub-row height (pixels) | `com.holtherndon.bazelviz.ui.timeline.TimelineView.SUB_ROW_HEIGHT` | 18 | Not reached — it is fixed, which is the point. Every lane sub-row and every in-flight band row is exactly this tall, so a lane is its stacking depth times this and the plot's total height is what the timeline's vertical scrollbar absorbs. Height was previously divided out of the window (`canvas height / lanes`, then again by the lane's depth), which produced one- and two-pixel sub-rows on any real session: unreadable and unclickable exactly where the build had the most concurrency to show. |
| Console lines retained | `com.holtherndon.bazelviz.ui.capture.ConsoleModel.DEFAULT_MAX_LINES` | 20000 | The oldest lines are dropped from the *view*; the full text is on disk in `raw/stdout.log`, which the panel says. |
| Raw payload rendered as text | `com.holtherndon.bazelviz.ui.events.RawPayloadRenderer.MAX_TEXT_CHARS` | 200000 | The renderer states the truncation and the full byte length. |
| Raw payload rendered as hex | `com.holtherndon.bazelviz.ui.events.RawPayloadRenderer.MAX_HEX_BYTES` | 65536 | As above. |
| Event id text | `com.holtherndon.bazelviz.bepcodec.EventIdDisplay.MAX_DISPLAY_CHARS` | 240 | Elided with an ellipsis; the full id is in the raw payload. |

**Two graph budgets are transient rather than limits**, and are not in the table
for that reason: `GraphCanvas` stops drawing individual edges above 30,000 at
far zoom and above 20,000 while a drag is in progress. Both reverse on the next
frame, and `hiddenDetail()` names the omission on screen — a blank area that
looked edgeless would be a claim about the build, and a false one.

**Label decluttering is a rendering-density decision, not a limit**: within a
zoom band the canvas skips a label that would paint over one already painted,
under a deterministic priority (selected, then hovered, then higher weight,
then lower node index), so text never paints over text and the same labels
survive every frame. A selected node's label always paints — nothing outranks
it. The skipped count is `GraphCanvas.declutteredLabelCount()`, and zooming in
gives every label more room, exactly as with the semantic-zoom bands.

## Query and traversal limits

| Limit | Constant | Default | When reached |
|---|---|---:|---|
| Shortest-path search budget | `com.holtherndon.bazelviz.ui.graph.TreeView.PATH_BUDGET` | 200000 | `ShortestPath.Result.describe()` distinguishes "there is no path" from "the search gave up", which are different answers and only one is a fact about the build. |
| Whole-graph transitive count budget | `com.holtherndon.bazelviz.analysis.GraphWeights.GLOBAL_TRANSITIVE_NODE_BUDGET` | 200000 | The Graph card's per-selected-node transitive count over the full CSR index stops here and reports "≥N (budget reached)" — a lower bound stated as one, never passed off as a total. Plan 13.3 forbids a transitive closure, so this traversal must be able to give up visibly. |
| On-screen transitive count work budget | `com.holtherndon.bazelviz.analysis.GraphWeights.SUBGRAPH_TRANSITIVE_WORK_BUDGET` | 20000000 | The exact per-node transitive counts over the drawn subgraph share this traversal-step budget. Nodes past it are shown as unknown (grey, base size) — never zero — and the legend states that the budget was reached. For real neighbourhood drawings it never bites; it exists so a whole-build extract at the node ceiling cannot stall the weight worker for half a minute. |
| Aggregate groups returned | `com.holtherndon.bazelviz.storage.metrics.MetricQueries.DEFAULT_GROUP_LIMIT` | 40 | The table states the total group count and how many actions the unlisted groups hold. |
| Finding candidates per criterion | `com.holtherndon.bazelviz.storage.metrics.MetricQueries.DEFAULT_CANDIDATE_LIMIT` | 25 | Not a truncation of results but of *inputs*: the rules examine the extremes. Bounded so the rules cost the same on a five-million-action build as on a small one. |
| Table page size | `com.holtherndon.bazelviz.ui.actions.ActionRowSource.DEFAULT_PAGE_SIZE` | 200 | Paging, not truncation: every row is reachable by scrolling, and the total is always shown. |
| Ad hoc query rows | `com.holtherndon.bazelviz.storage.query.AdHocQueries.DEFAULT_ROW_LIMIT` | 1000000 | The Query card states the exact cap **and** the exact number of matching rows, and offers a button that raises the cap to the full match and runs again. Nothing is removed from the database or from the statement — this bounds only how much of the result the grid claims to be a view of. Also the default of the card's row-cap spinner. |
| Ad hoc query deadline, seconds | `com.holtherndon.bazelviz.storage.query.AdHocQueries.DEFAULT_TIMEOUT_SECONDS` | 60 | The statement is interrupted (`sqlite3_interrupt`) and the card says it was stopped rather than that it failed — a cancelled query is not a broken one. The backstop for a window nobody is watching; the Cancel button is the one a person uses. |
| Row-cap spinner floor | `com.holtherndon.bazelviz.ui.query.QueryView.MIN_ROW_LIMIT` | 1 | The spinner will not go lower, for the reason the graph's node-limit floor is 1: a cap of zero shows an empty grid, and an empty grid reads as "the query matched nothing", which is a claim about the build rather than about the setting. |
| Row-cap spinner ceiling | `com.holtherndon.bazelviz.ui.query.QueryView.MAX_ROW_LIMIT` | 20000000 | The spinner will not go higher. Four times the largest planned session (Tier 3, five million actions), so a join that multiplies rows still fits, and far below `Integer.MAX_VALUE`, where `JTable`'s int-based row geometry stops working. An unbounded control would read as "no limit", which plan 13.6 forbids. |
| Query tabs | `com.holtherndon.bazelviz.ui.query.QueryView.MAX_TABS` | 16 | The card refuses to open another tab and says so on screen, with the number and the way out (close one). Nothing open is closed for you. Every tab is a SQLite connection and a thread, which is also what makes tabs genuinely concurrent. |

## Ingest and storage limits

| Limit | Constant | Default | When reached |
|---|---|---:|---|
| Journal payload | `com.holtherndon.bazelviz.core.journal.JournalFormat.DEFAULT_MAX_PAYLOAD_BYTES` | 67108864 | The frame is refused rather than truncated, and the capture is marked incomplete. A partial payload on disk would be worse than a missing one. |
| Journal segment | `com.holtherndon.bazelviz.core.journal.JournalFormat.DEFAULT_SEGMENT_BYTES` | 268435456 | Rotation at a frame boundary. Never mid-frame, so every segment is independently readable. |
| Normalization batch | `com.holtherndon.bazelviz.storage.events.EventWriter.DEFAULT_BATCH_SIZE` | 5000 | Not a limit on data, a commit interval. |
| String dictionary cache | `com.holtherndon.bazelviz.storage.events.StringDictionary.DEFAULT_CACHE_ENTRIES` | 65536 | An LRU over interning, not over content: a cache miss costs a query, never a lost string. |
| Diagnostics per code | `com.holtherndon.bazelviz.capture.file.importer.ImportOptions.DEFAULT_MAX_DIAGNOSTICS_PER_CODE` | 1000 | The count keeps rising after the messages stop, so "12,000 of these" is still reported. |
| Executable hashing | `com.holtherndon.bazelviz.runner.exec.BazelExecutableResolver.MAX_HASH_BYTES` | 67108864 | The identity is recorded as unhashed rather than as a hash of part of the file. |

## Untrusted-input limits

These bound what an archive or a file somebody else produced may do. They are
refusals, not truncations: an input that exceeds one is rejected whole.

| Limit | Constant | Default | When reached |
|---|---|---:|---|
| Archive entries | `com.holtherndon.bazelviz.format.portable.BvizLimits` — `maxEntries` | 50000 | The archive is refused. |
| Archive expanded bytes | `com.holtherndon.bazelviz.format.portable.BvizLimits` — `maxExpandedBytes` | 68719476736 | Counted from bytes the decompressor produced, never from the size an entry declares. |
| Single entry expanded bytes | `com.holtherndon.bazelviz.format.portable.BvizLimits` — `maxEntryBytes` | 34359738368 | As above. |
| Entry expansion ratio | `com.holtherndon.bazelviz.format.portable.BvizLimits` — `maxCompressionRatio` | 1000 | A legitimate journal or database compresses about 10:1; 1000:1 is a file of zeroes. |
| Manifest JSON depth | `com.holtherndon.bazelviz.format.session.json.JsonReader.DEFAULT_MAX_DEPTH` | 64 | Parsing stops; a manifest nested deeper than this is hostile, not deep. |
| Manifest JSON size | `com.holtherndon.bazelviz.format.session.json.JsonReader.DEFAULT_MAX_CHARS` | 8388608 | As above. |
| BEP message size | `com.holtherndon.bazelviz.bepcodec.BepEventDecoder.DEFAULT_MAX_MESSAGE_BYTES` | 67108864 | The event is recorded as undecodable with its raw location, so the bytes stay reachable. |

## Timing

| Limit | Constant | Default |
|---|---|---|
| Overview refresh | `com.holtherndon.bazelviz.ui.overview.OverviewPanel.REFRESH_INTERVAL` | 2 seconds |
| Import progress reporting | `com.holtherndon.bazelviz.capture.file.importer.ImportOptions.DEFAULT_PROGRESS_INTERVAL_MILLIS` | 100 ms |

The metric collection is deliberately **not** on a timer: it scans every action
(898 ms at 250,000) and runs once per session. See `docs/performance.md`.

## What plan 20.3 asks for and this does not yet have

Plan 20.3 lists nineteen limits that "settings must include". Fifteen of them
exist as the constants above and can be changed by a caller; **none of them has
a settings screen**, because v1 has no settings screen. That is the single
largest gap between this table and plan 20.3, and it is stated here rather than
implied by a page that lists only what exists.

Four have no constant at all and are not implemented:

- Maximum application heap guidance — the JVM's own flag does this today.
- Parser concurrency — import is single-threaded per source by design.
- Maximum graph-layout time — layout is cancellable, which is the property that
  mattered; no wall-clock ceiling is applied.
- Full-text command indexing and filesystem stat enrichment — both are
  behaviours rather than limits, and neither is switchable.

The exact transitive-count budget, long the fifth entry here, shipped with the
Graph card's weight selector: `GraphWeights.GLOBAL_TRANSITIVE_NODE_BUDGET` and
`GraphWeights.SUBGRAPH_TRANSITIVE_WORK_BUDGET` in the traversal table above. A
complete transitive closure remains forbidden (plan 13.3); what shipped is the
bounded version that gives up out loud.
