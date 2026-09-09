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

These bound what is drawn, how small an interactive mark may become, or when a
changed viewport starts its next detail read. Data budgets report themselves on
screen when reached, and the graph extraction and grouping budgets can be
changed in the Graph card. The timeline geometry and refresh rows are fixed
visual and interaction affordances rather than data ceilings. They never
truncate or alter the measured interval; the timeline states when a short span
uses a marker instead of proportional width.

| Limit | Constant | Default | When reached |
|---|---|---:|---|
| Detailed graph nodes | `com.holtherndon.bazelviz.analysis.GraphExtract.DEFAULT_NODE_LIMIT` | 50000 | The extraction refuses and reports the exact totals. The canvas offers "Draw it anyway", "Narrow it", "Group instead" and "Export all of it". The default of the toolbar's node-limit spinner, which sets the budget directly. |
| Detailed graph edges | `com.holtherndon.bazelviz.analysis.GraphExtract.DEFAULT_EDGE_LIMIT` | 200000 | As above. |
| Node-limit spinner floor | `com.holtherndon.bazelviz.ui.graph.GraphCanvasPanel.MIN_NODE_LIMIT` | 1 | The spinner will not go lower: a limit of zero draws nothing and would read as "the graph is empty", which is a claim about the build rather than the setting. |
| Node-limit spinner ceiling | `com.holtherndon.bazelviz.ui.graph.GraphCanvasPanel.MAX_NODE_LIMIT` | 5000000 | The spinner will not go higher. Matches the largest planned graph (Tier 3, five million nodes); an unbounded control would read as "no limit", which is a claim plan 13.6 forbids. |
| Cluster boxes | `com.holtherndon.bazelviz.analysis.GraphClustering.DEFAULT_CLUSTER_LIMIT` | 2000 | The Graph card exposes this as its Group budget. Grouping stops at the first proven excess and reports the truthful lower bound `limit + 1`, without retaining every distinct key merely to count a refusal. It offers either a larger budget or another explicit grouping dimension. Nothing is truncated; package and mnemonic are not falsely ordered as universally coarser than one another. |
| Far-hierarchy branches per horizontal pixel | `com.holtherndon.bazelviz.ui.graph.GraphCanvas.FAR_HIERARCHY_BRANCHES_PER_PIXEL` | 2 | At overview scale, the hierarchy draws at most this many evenly distributed primary branches per horizontal pixel. The same line budget bounds cross-links revealed for one selected node when the rest are decluttered. The canvas states exactly how many branches or selected links are simplified; zooming in restores each one. The graph model and export remain complete. |
| Find dropdown matches | `com.holtherndon.bazelviz.ui.graph.GraphExplorerView.FIND_LIMIT` | 12 | The Graph card's Find field lists at most this many as-you-type matches; when there are more, the dropdown's last row says only the first N are listed and typing narrows. Choosing an entry lands on that exact node — nothing is chosen silently. |
| Browse listing entries | `com.holtherndon.bazelviz.ui.graph.GraphExplorerView.BROWSE_LIMIT` | 500 | The Graph card's Browse panel lists at most this many nodes per filter. The summary states "only the first N matches are listed" and the tree root names the graph's total, so a truncated listing cannot read as a complete one. |
| Fit label reservation | `com.holtherndon.bazelviz.ui.graph.GraphCanvas.MAX_LABEL_FIT_FRACTION` | 0.5 | Fit reserves room for the label text visible at the resulting zoom, up to this fraction of the window width. Past the cap — a dense graph of long names — the reservation stops growing and the canvas states that some labels run past the right edge, rather than zooming the nodes to nothing or pretending the text fits. |
| Timeline spans in one viewport | `com.holtherndon.bazelviz.ui.timeline.SpanWindow.MAX_SPANS` | 20000 | The window reports that it is capped and the aggregate bins remain exact; zooming in returns individual spans. Also bounds how many in-flight targets the live band fetches per rebuild; the band's label states "drawing the earliest N of M" when it bites. |
| Timeline sub-rows per lane | `com.holtherndon.bazelviz.ui.timeline.SpanStacking.MAX_SUB_ROWS` | 6 | Overlapping spans in one lane stack top-to-bottom by start time into at most this many sub-rows. Beyond it, further overlapping spans draw into the last sub-row — never dropped — and the status line states the exact overflow count with a suggestion to zoom in. |
| Timeline sub-row height (pixels) | `com.holtherndon.bazelviz.ui.timeline.TimelineView.SUB_ROW_HEIGHT` | 18 | Not reached — it is fixed, which is the point. Every lane sub-row and every in-flight band row is exactly this tall, so a lane is its stacking depth times this and the plot's total height is what the timeline's vertical scrollbar absorbs. Height was previously divided out of the window (`canvas height / lanes`, then again by the lane's depth), which produced one- and two-pixel sub-rows on any real session: unreadable and unclickable exactly where the build had the most concurrency to show. |
| Timeline span vertical inset (pixels per side) | `com.holtherndon.bazelviz.ui.timeline.TimelineView.SPAN_VERTICAL_INSET` | 2 | Each exact span leaves this much space above and below its bar within the 18-pixel sub-row, so adjacent events have a visible boundary. Hit testing still uses the full row. This changes paint geometry only, not the recorded interval. |
| Timeline short-span marker width (pixels) | `com.holtherndon.bazelviz.ui.timeline.TimelineView.SHORT_SPAN_MARKER_WIDTH` | 3 | A span whose proportional width is smaller draws as this distinct needle. The status line gives the exact number of needles and says to zoom in for proportional width; hover and the inspector retain the real duration. No span or source data is lengthened. |
| Timeline minimum span hit width (pixels) | `com.holtherndon.bazelviz.ui.timeline.TimelineView.MINIMUM_SPAN_HIT_WIDTH` | 7 | Hit testing widens a short span to this target while keeping it centred on the drawn interval. This affects only pointer selection: painting remains the three-pixel needle above, and the stored start and end remain unchanged. |
| Timeline viewport refresh quiet period (milliseconds) | `com.holtherndon.bazelviz.ui.timeline.TimelineView.VIEWPORT_REFRESH_DELAY_MILLIS` | 60 | Each pan or zoom restarts this one-shot delay before requesting exact spans. Aggregate density remains visible and is labelled while waiting. Continuous navigation therefore coalesces reads; the controller admits only one active and one replaceable pending request, so no source data is discarded and stale requests cannot grow without bound. |
| Console lines retained | `com.holtherndon.bazelviz.ui.capture.ConsoleModel.DEFAULT_MAX_LINES` | 20000 | The oldest lines are dropped from the *view*; the full text is on disk in `raw/stdout.log`, which the panel says. |
| Console ANSI sequence characters | `com.holtherndon.bazelviz.ui.capture.ConsoleModel.MAX_ANSI_SEQUENCE_CHARACTERS` | 1024 | At most this many parameter characters from one unterminated CSI sequence are retained. An overlong sequence is ignored for display through its terminator; its exact bytes remain in the raw stdout/stderr log. |
| Errors console lines retained per event stream | `com.holtherndon.bazelviz.ui.errors.ErrorConsolePanel.MAX_LINES` | 400 | The Errors detail pane renders the last 400 interpreted lines from each selected event's stderr and stdout. It states the exact number of earlier lines omitted from the view; the complete event remains in the raw journal. |
| Launcher command history | `com.holtherndon.bazelviz.ui.capture.LauncherHistory.MAX_ENTRIES` | 50 | The oldest command leaves launcher history when a newer unique command is added. This removes only a recall shortcut, never a captured command or session, and the command field states the bound while remaining fully editable. Reusing a command promotes it instead of consuming another entry. |
| Text viewer/editor file size | `com.holtherndon.bazelviz.ui.files.TextFileDocument.MAX_FILE_BYTES` | 16777216 | A larger local or remote test log, action output or BUILD file is refused with its byte count and this exact 16 MiB limit. The viewer never truncates a file and the editor never writes an oversized replacement. |
| Files retained from one selected event | `com.holtherndon.bazelviz.ui.events.EventFileLoader.MAX_FILES` | 10000 | The Files tab states the event's exact direct-file total and that only the first N metadata rows are displayed. The complete raw event remains preserved and visible in Decoded/Raw bytes. |
| Raw payload rendered as text | `com.holtherndon.bazelviz.ui.events.RawPayloadRenderer.MAX_TEXT_CHARS` | 200000 | The renderer states the truncation and the full byte length. |
| Raw payload rendered as hex | `com.holtherndon.bazelviz.ui.events.RawPayloadRenderer.MAX_HEX_BYTES` | 65536 | As above. |
| Event id text | `com.holtherndon.bazelviz.bepcodec.EventIdDisplay.MAX_DISPLAY_CHARS` | 240 | Elided with an ellipsis; the full id is in the raw payload. |
| Starlark flame contexts | `com.holtherndon.bazelviz.ui.starlark.ProfileView.DEFAULT_FLAME_NODE_LIMIT` | 5000 | The Flame tab draws this bounded root-to-leaf prefix and states the exact total and omitted context count. Double-clicking a context narrows the query; every context remains in SQLite and on the Query page. |
| Standalone pprof source file | `com.holtherndon.bazelviz.ui.session.PprofSource.MAX_SOURCE_BYTES` | 1073741824 | The original gzip or protobuf file is copied into private temporary storage before parsing. Larger files, including files that grow beyond this limit during copying, are refused explicitly. The decompressed profile and graph limits apply separately. |
| Standalone pprof metric labels | `com.holtherndon.bazelviz.ui.session.StarlarkProfileReader.MAX_METRIC_TEXT_CHARACTERS` | 256 | Sample type and unit strings longer than this many Java characters are refused before rendering; repeated graph labels must not multiply an unbounded imported unit string. Original data is not changed or truncated. |
| Initial Starlark call-graph functions | `com.holtherndon.bazelviz.ui.starlark.ProfileView.DEFAULT_CALL_GRAPH_NODE_LIMIT` | 80 | The Call Graph tab initially projects the hottest N functions by cumulative sampled CPU and pins a function selected in Hot Functions. It states the complete function count and the exact omitted count; the toolbar can raise this budget. |
| Maximum Starlark call-graph functions | `com.holtherndon.bazelviz.ui.starlark.ProfileView.MAX_CALL_GRAPH_NODE_LIMIT` | 250 | The toolbar will not retain or paint more function boxes. All functions remain queryable in the paged table and SQLite; narrowing through selection or using those exact views avoids presenting a dense, unreadable picture as a complete graph. |
| Initial Starlark call-graph arrows | `com.holtherndon.bazelviz.ui.starlark.ProfileView.DEFAULT_CALL_GRAPH_EDGE_LIMIT` | 600 | The Call Graph initially draws the strongest N caller-to-callee relationships whose endpoints are both visible. It reports the exact visible-endpoint arrow total and omitted count; the toolbar can raise this budget. Relationships touching omitted functions are explicitly outside that total. |
| Maximum Starlark call-graph arrows | `com.holtherndon.bazelviz.ui.starlark.ProfileView.MAX_CALL_GRAPH_EDGE_LIMIT` | 5000 | The toolbar will not retain or paint more arrows. The selected function's exact paged caller/callee tables remain available beside the graph, and the Query page retains every relationship. |
| Starlark call-graph nodes per rank row | `com.holtherndon.bazelviz.ui.starlark.StarlarkCallGraphLayout.NODES_PER_ROW` | 8 | A dependency rank wraps after this many boxes. This is only compact placement: it neither drops nodes nor changes caller-to-callee rank order, and dragged nodes remain freely movable. The wrap prevents a large set of disconnected functions from shrinking all labels to fit one extremely wide row. |

**Two older-layout budgets are transient rather than limits**, and are not in
the table for that reason: outside the hierarchy layout, `GraphCanvas` stops
drawing individual edges above 30,000 at far zoom and above 20,000 while a
drag is in progress. The hierarchy instead retains the screen-bounded backbone
described above. Every temporary omission reverses on zoom or at the end of the
drag, and `hiddenDetail()` names it on screen — a blank area that looked
edgeless would be a claim about the build, and a false one.

**Label decluttering is a rendering-density decision, not a limit**: within a
zoom band the canvas skips a label that would paint over one already painted,
under a deterministic priority (selected, then hovered, then higher weight,
then lower node index), so text never paints over text and the same labels
survive every frame. A selected node's label always paints — nothing outranks
it. The skipped count is `GraphCanvas.declutteredLabelCount()`, is printed in
the canvas detail message, and zooming in gives every label more room, exactly
as with the semantic-zoom bands.

**Hierarchy cross-link decluttering is also a rendering decision, not a
limit**: the default Dependency hierarchy keeps every primary branch and every
shared, cyclic, or other non-tree dependency in the model. Medium and near zoom
draw every primary branch; the far band follows the explicit density row above.
Decluttered edges omit cross-links until one incident node is selected, state
the exact hidden count, and can be changed to All dependencies at any time. A
multi-node marquee never bypasses the overview budget by revealing the union of
every selected node's links.

## Graph resource limits

These bounds protect one open session as a whole. The aggregate budget includes
mapped index files, retained graph renderings and models, extraction-aligned
metadata, and charged traversal/layout scratch. A component-specific cap below
is part of that aggregate allowance, not memory in addition to it. This is not
a whole-process native-memory cap: each independently open graph reader keeps a
fixed 1 MiB SQLite page cache outside the graph budget, while its temporary
b-trees are forced to files.

| Limit | Constant | Default | When reached |
|---|---|---:|---|
| Aggregate graph resources per open session, bytes | `com.holtherndon.bazelviz.graph.GraphResourceBudget.DEFAULT_SESSION_BYTES` | 1073741824 | An exact-boundary request is admitted. A larger request is refused before allocation or mapping; the visible error names the requested bytes, the session limit, total retained bytes, and retained purposes. Closing or evicting a retained model or layout, returning from a scoped traversal callback, or closing the session releases the corresponding charge. |
| Native SQLite page cache per graph reader, KiB | `com.holtherndon.bazelviz.storage.SessionDatabase.GRAPH_READER_PAGE_CACHE_KIB` | 1024 | Each graph-only JDBC reader keeps this fixed native cache outside the aggregate Java/mapping budget; graph temporary b-trees are file-backed. |
| Mapped graph indexes retained per open session | `com.holtherndon.bazelviz.graph.GraphIndexCache.MAX_CACHED_INDEXES` | 2 | The cache evicts the least-recently-used idle mapping. A leased mapping is never closed underneath a traversal. Acquiring a forward/reverse pair is atomic; if neither enough budget nor an idle entry is available, both are refused and no one-sided lease escapes. |
| Retained graph-layout cache, bytes | `com.holtherndon.bazelviz.ui.graph.GraphLayoutService.MAX_CACHE_BYTES` | 134217728 | A rendering larger than this is delivered but not cached. Otherwise least-recently-used renderings are released until the cache is within the cap. Every cached byte is also charged to the aggregate session budget above. |
| Retained graph-layout cache entries | `com.holtherndon.bazelviz.ui.graph.GraphLayoutService.MAX_CACHE_ENTRIES` | 12 | Least-recently-used request keys are released above this count even when their rendering is tiny, empty, or unavailable. The count cap and byte cap apply together, so zero-byte results cannot grow the cache without bound. |
| Interactive marquee selected nodes | `com.holtherndon.bazelviz.ui.graph.GraphCanvas.MAX_MARQUEE_SELECTION` | 10000 | A drag rectangle retains at most this many node positions. If more matches may remain, the selection summary states that only the bounded subset is selected. |
| Interactive marquee examined candidates | `com.holtherndon.bazelviz.ui.graph.GraphCanvas.MAX_MARQUEE_CANDIDATES` | 50000 | A drag rectangle examines at most this many spatial-index candidates on the EDT. Reaching either marquee bound stops the query and reports the partial selection; it never presents the subset as complete. |
| Retained computed critical-path estimate, bytes per action | `com.holtherndon.bazelviz.analysis.CriticalPath.RETAINED_BYTES_PER_NODE` | 32 | One result slot per duration source remains charged for the open session. An unexpected generation change is refused until the immutable session is reopened, so an older delivered result is never uncharged while its arrays remain reachable. This is a conservative primitive-array estimate, not a result-data limit. |
| Peak computed critical-path estimate, bytes per action | `com.holtherndon.bazelviz.analysis.CriticalPath.PEAK_BYTES_PER_NODE` | 64 | Critical-path computation reserves this conservative peak before loading durations or allocating traversal and schedule arrays. Refusal is reported as a resource-budget refusal, not index corruption. |
| Fixed critical-path estimate overhead, bytes | `com.holtherndon.bazelviz.analysis.CriticalPath.ESTIMATE_OVERHEAD_BYTES` | 1024 | Covers array headers and rounding beyond the conservative per-action estimate. It is part of the same aggregate session reservation. |
| CSR descriptor header, bytes | `com.holtherndon.bazelviz.graph.CsrFile.HEADER_BYTES` | 40 | The complete fixed header is read and validated before body mapping or allocation. This is an on-disk format size rather than an extra memory allowance. |
| One CSR read-only mapping segment, bytes | `com.holtherndon.bazelviz.graph.CsrFile.MAP_SEGMENT_BYTES` | 268435456 | A larger CSR body is mapped as several read-only `MemorySegment` regions. It is not copied into graph-sized heap arrays. The fixed header and exact file length are validated before any body region is mapped. |
| CSR construction/checksum buffer, bytes | `com.holtherndon.bazelviz.graph.CsrFile.WRITE_BUFFER_BYTES` | 1048576 | Offsets and targets stream through fixed positional buffers, and checksum calculation during construction uses the same fixed buffer size. A completed file is forced and atomically renamed; a failed build never publishes its temporary prefix. |

## Query and traversal limits

| Limit | Constant | Default | When reached |
|---|---|---:|---|
| Visual filter components | `com.holtherndon.bazelviz.core.filter.FilterExpression.MAX_COMPONENTS` | 64 | Counts conditions and groups, including the root group. Adding beyond the bound is refused with an inline explanation; the existing filter is unchanged. |
| Visual filter nesting | `com.holtherndon.bazelviz.core.filter.FilterExpression.MAX_DEPTH` | 8 | Counts all expression levels, including the root and leaf conditions. Deeper nesting is refused visibly. |
| Values in one filter condition | `com.holtherndon.bazelviz.core.filter.FilterExpression.MAX_VALUES` | 256 | Applies to the “is one of” / “is not one of” choices; excess values are rejected, never dropped. Single-value and presence operators enforce their own arity. |
| Characters in one filter value or field identifier | `com.holtherndon.bazelviz.core.filter.FilterExpression.MAX_VALUE_CHARACTERS` | 4096 | An overlong value is rejected with an inline error. Chip labels may shorten for display, but editing and the tooltip retain the complete value. |
| Cached regex patterns | `com.holtherndon.bazelviz.core.filter.RegexFilter.MAX_CACHED_PATTERNS` | 64 | Process-wide compiled-pattern cache shared by Events and Graph; oldest entries are evicted. Pattern length uses the filter-value bound. |
| Regex character reads per value | `com.holtherndon.bazelviz.core.filter.RegexFilter.MAX_CHARACTER_READS` | 1,000,000 | Bounds Java regex matching work through a checked character sequence. Exhaustion, interruption, or regex stack exhaustion fails visibly instead of returning a partial result. This is not a wall-clock deadline. |
| Shortest-path search budget | `com.holtherndon.bazelviz.ui.graph.TreeView.PATH_BUDGET` | 200000 | `ShortestPath.Result.describe()` distinguishes "there is no path" from "the search gave up", which are different answers and only one is a fact about the build. |
| Whole-graph transitive count budget | `com.holtherndon.bazelviz.analysis.GraphWeights.GLOBAL_TRANSITIVE_NODE_BUDGET` | 200000 | The Graph card's per-selected-node transitive count over the full CSR index stops here and reports "≥N (budget reached)" — a lower bound stated as one, never passed off as a total. Plan 13.3 forbids a transitive closure, so this traversal must be able to give up visibly. |
| On-screen transitive count work budget | `com.holtherndon.bazelviz.analysis.GraphWeights.SUBGRAPH_TRANSITIVE_WORK_BUDGET` | 20000000 | The exact per-node transitive counts over the drawn subgraph share this traversal-step budget. Nodes past it are shown as unknown (grey, base size) — never zero — and the legend states that the budget was reached. For real neighbourhood drawings it never bites; it exists so a whole-build extract at the node ceiling cannot stall the weight worker for half a minute. |
| Aggregate groups returned | `com.holtherndon.bazelviz.storage.metrics.MetricQueries.DEFAULT_GROUP_LIMIT` | 40 | The table states the total group count and how many actions the unlisted groups hold. |
| Finding candidates and preloaded critical-path execution details | `com.holtherndon.bazelviz.storage.metrics.MetricQueries.DEFAULT_CANDIDATE_LIMIT` | 25 | Not a truncation of either result set. Finding rules examine the extremes, while the Critical Path page lists every dependency-path step and preloads the richer execution-log breakdown only for this many largest matched contributors. **Reveal action** opens the complete Actions detail for another executed step, and the page states the bound. |
| Critical-path row page size | `com.holtherndon.bazelviz.ui.criticalpath.CriticalPathRowSource.DEFAULT_PAGE_SIZE` | 200 | Paging, not truncation: every Bazel-reported component and every node on the computed dependency path remains reachable in exact path order. Profile descriptions and graph-node identities load this many at a time as their tables scroll. Each exact total and the page size are shown. |
| Critical-path cached pages | `com.holtherndon.bazelviz.ui.criticalpath.CriticalPathRowSource.DEFAULT_CACHE_PAGES` | 8 | Each path table retains only this many recently visited detail pages. Evicted pages load again when revisited; path totals, schedules, and database rows are not discarded. |
| Starlark profile table page size | `com.holtherndon.bazelviz.ui.starlark.ProfileView.PAGE_SIZE` | 200 | Paging, not truncation: hot functions, source files, callers, and callees remain reachable while visible pages load on a worker. Exact matching totals are shown. |
| Starlark profile cached pages | `com.holtherndon.bazelviz.ui.starlark.ProfileView.PAGE_CACHE_SIZE` | 8 | Each Starlark table retains only this many recently visited pages. Evicted pages load again and no profile row is discarded. |
| Table page size | `com.holtherndon.bazelviz.ui.actions.ActionRowSource.DEFAULT_PAGE_SIZE` | 200 | Paging, not truncation: every row is reachable by scrolling, and the total is always shown. |
| Action execution-attempt page size | `com.holtherndon.bazelviz.ui.actions.ActionsView.ATTEMPT_PAGE_SIZE` | 100 | The inspector retains one execution-log page for the selected action. It states the exact recorded total and current range; **Load next attempts** reaches every later row in stable log order. |
| Top Level Targets package page size | `com.holtherndon.bazelviz.ui.targets.TargetsView.PACKAGE_PAGE_SIZE` | 200 | Package nodes load in stable path order. A synthetic **Load next packages** node states that more recorded packages remain and fetches the next page; it is navigation, not a target. |
| Top Level Targets package-child page size | `com.holtherndon.bazelviz.ui.targets.TargetsView.PACKAGE_TARGET_PAGE_SIZE` | 200 | Expanding a package appends one bounded page of its target/configuration rows per request. A synthetic **Load next targets** node reaches the next page and cannot dispatch entity actions. |
| Top Level Targets flat-label page size | `com.holtherndon.bazelviz.ui.targets.TargetsView.FLAT_LABEL_PAGE_SIZE` | 200 | Paging, not truncation: the All Targets choice inside Top Level Targets appends this many BEP top-level labels on request and states loaded versus total labels. The Packages choice remains lazy by package. |
| Target tag page size | `com.holtherndon.bazelviz.ui.targets.TargetsView.TARGET_TAG_PAGE_SIZE` | 100 | The target inspector retains one source/tag-ordered page, states the exact recorded total and rows outside that page, and offers the next page while one remains. |
| Target output-group page size | `com.holtherndon.bazelviz.ui.targets.TargetsView.OUTPUT_GROUP_PAGE_SIZE` | 100 | The target inspector retains one ordinal-ordered output-group page, states its exact recorded total and current range, and offers every later page. An incomplete group remains labelled incomplete. |
| All Targets label page size | `com.holtherndon.bazelviz.ui.targets.AllTargetsView.LABEL_PAGE_SIZE` | 200 | Paging, not truncation: the explorer appends this many distinct cquery labels on request, states loaded versus total labels, and reads configuration rows only when their label is selected or expanded. |
| All Targets configuration-group page size | `com.holtherndon.bazelviz.ui.targets.AllTargetsView.CONFIGURATION_GROUP_PAGE_SIZE` | 200 | Expanding a label loads checksum groups in stable nullable-key order and exposes a synthetic next node when more remain. A missing checksum stays distinct from both an empty checksum and the word “unavailable.” |
| All Targets configuration-variant page size | `com.holtherndon.bazelviz.ui.targets.AllTargetsView.CONFIGURATION_VARIANT_PAGE_SIZE` | 200 | Expanding a checksum group loads configured-target variants by stable database id and exposes a synthetic next node until its exact recorded count is reached. |
| Test attempt page size | `com.holtherndon.bazelviz.ui.tests.TestsView.TEST_ATTEMPT_PAGE_SIZE` | 100 | The selected test's BEP attempts are retained one stable run/shard/attempt/id page at a time. The inspector states the exact recorded total and offers later pages. |
| Test log page size | `com.holtherndon.bazelviz.ui.tests.TestsView.TEST_LOG_PAGE_SIZE` | 100 | Recorded test-log references load independently by stable id. Exact range/total text and a next-page control keep a long log set reachable without retaining it whole. |
| Test subprocess page size | `com.holtherndon.bazelviz.ui.tests.TestsView.TEST_SPAWN_PAGE_SIZE` | 100 | Execution-log subprocesses for a test load independently in stable log order. The inspector never pairs them speculatively with BEP attempts and states its exact recorded-row total. |
| Configuration explorer page size | `com.holtherndon.bazelviz.ui.configurations.ConfigurationRowSource.PAGE_SIZE` | 200 | Paging, not truncation: configuration summaries, values and two-way option differences all remain fully scrollable while only nearby pages stay in memory. Exact totals are shown before pages load. |
| Ad hoc query rows | `com.holtherndon.bazelviz.storage.query.AdHocQueries.DEFAULT_ROW_LIMIT` | 1000000 | The Query card states the exact cap **and** the exact number of matching rows, and offers a button that raises the cap to the full match and runs again. Nothing is removed from the database or from the statement — this bounds only how much of the result the grid claims to be a view of. Also the default of the card's row-cap spinner. |
| Ad hoc query deadline, seconds | `com.holtherndon.bazelviz.storage.query.AdHocQueries.DEFAULT_TIMEOUT_SECONDS` | 60 | The statement is interrupted (`sqlite3_interrupt`) and the card says it was stopped rather than that it failed — a cancelled query is not a broken one. The backstop for a window nobody is watching; the Cancel button is the one a person uses. |
| Row-cap spinner floor | `com.holtherndon.bazelviz.ui.query.QueryView.MIN_ROW_LIMIT` | 1 | The spinner will not go lower, for the reason the graph's node-limit floor is 1: a cap of zero shows an empty grid, and an empty grid reads as "the query matched nothing", which is a claim about the build rather than about the setting. |
| Row-cap spinner ceiling | `com.holtherndon.bazelviz.ui.query.QueryView.MAX_ROW_LIMIT` | 20000000 | The spinner will not go higher. Four times the largest planned session (Tier 3, five million actions), so a join that multiplies rows still fits, and far below `Integer.MAX_VALUE`, where `JTable`'s int-based row geometry stops working. An unbounded control would read as "no limit", which plan 13.6 forbids. |
| Query tabs | `com.holtherndon.bazelviz.ui.query.QueryView.MAX_TABS` | 16 | The card refuses to open another tab and says so on screen, with the number and the way out (close one). Nothing open is closed for you. Every tab is a SQLite connection and a thread, which is also what makes tabs genuinely concurrent. |
| Legacy saved SSH launcher connections | `com.holtherndon.bazelviz.ui.capture.SshConnectionProfile.MAX_SAVED_PROFILES` | 20 | Bounds the old launcher format and its one-time Workspace migration input. The migration keeps distinct repositories on one host and does not silently accept more legacy entries. New local and SSH profiles use the Workspace limit below. |
| Repository browser visible entries | `com.holtherndon.bazelviz.ui.repository.RepositoryBrowserView.DEFAULT_VISIBLE_ENTRY_LIMIT` | 5000 | The repository tree refuses further expansion with an explicit limit message and does not claim the underlying repository was truncated. The Console's typed Bazel Executable field does not browse a directory and therefore does not use this bound. |
| Terminal scrollback lines | `com.holtherndon.bazelviz.ui.terminal.SshTerminalView.DEFAULT_TRANSCRIPT_LINE_LIMIT` | 20000 | JediTerm discards the oldest scrollback lines while keeping the live screen for either a local or SSH Workspace. This bounds only the terminal display model; it does not alter build console logs or command output files. The historical class name remains for source compatibility. |
| Terminal CSI characters | `com.holtherndon.bazelviz.ui.terminal.SshTerminalView.MAX_CSI_CHARACTERS` | 1000 | A 7-bit `ESC [` or C1 CSI sequence that has not reached a final byte by this bound closes the Terminal and displays the safety-limit error. Normal output is not limited. This prevents malformed shell output from growing JediTerm's sequence accumulator without bound and leaves headroom below JediTerm's fixed 1,024-character rewrite buffer. |
| Terminal OSC/DCS characters | `com.holtherndon.bazelviz.ui.terminal.SshTerminalView.MAX_CONTROL_STRING_CHARACTERS` | 65536 | An OSC or DCS string that has not reached BEL or ST by this bound closes the Terminal and displays the safety-limit error. Normal output is not limited. This prevents malformed shell output from growing JediTerm's system-command accumulator without bound. |
| SSH Terminal close grace | `com.holtherndon.bazelviz.runner.ssh.SshCommandExecutor.TERMINAL_CLOSE_GRACE` | 1 second | Closing first asks the local OpenSSH PTY process to terminate. A process still alive after this grace is killed forcibly, then given the same bounded reap wait, so Terminal shutdown cannot leave its waiter alive indefinitely. |

Local terminal teardown is bounded too, but does not introduce a named public
constant: it waits at most one second after graceful termination, then at most
one second after forcible termination (and makes the same bounded reap attempt
if interrupted). This affects only shell shutdown; it is not a command timeout.

## Workspace settings limits

These bounds apply to non-secret local execution settings, not repositories or
captured sessions. A refusal never deletes a repository, session, or previous
valid settings file.

| Limit | Constant | Default | When reached |
|---|---|---:|---|
| Saved Workspaces | `com.holtherndon.bazelviz.ui.workspace.WorkspaceStore.MAX_SAVED_WORKSPACES` | 100 | The UI refuses a new profile and explains that one must be removed first. The store refuses an oversized snapshot or file as a whole rather than keeping an arbitrary first 100. Existing in-memory and on-disk lists remain unchanged. |
| Restorable Workspace-window slots | `com.holtherndon.bazelviz.ui.workspace.WorkspaceWindowState.MAX_OPEN_WORKSPACES` | 8 | Live windows and unavailable discovered restore IDs share this exact bound; an unavailable ID keeps its slot until the user forgets it. Constructing or loading a larger snapshot is refused as a whole rather than keeping an arbitrary first eight entries. The existing saved layout remains unchanged after a failed save. Window bounds are validated without assuming that the same screens are still attached. |
| Session mutation lock stripes | `com.holtherndon.bazelviz.ui.session.SessionMutationCoordinator.LOCK_STRIPES` | 64 | This bounds process-coordinator memory rather than session count. More active or mutating session UUIDs remain supported; hash collisions only serialize otherwise unrelated archive, cleanup, or activation work and never drop or refuse a session. |
| Workspace text field characters | `com.holtherndon.bazelviz.ui.workspace.WorkspaceProfile.MAX_TEXT_CHARACTERS` | 16384 | A stable ID, user label, SSH destination, working directory, or Bazel executable above this length is rejected with the exact bound. No truncated profile is constructed or persisted. |
| Workspace settings file bytes, per file | `com.holtherndon.bazelviz.ui.workspace.WorkspaceStore.MAX_SETTINGS_FILE_BYTES` | 1048576 | Loading refuses a larger Workspace-profile or window-restore settings file with a safe diagnostic. Saving writes a sibling temporary file, measures it, and refuses replacement if it exceeds this bound, leaving the previous live file intact. |
| Workspace-discovery script bytes | `com.holtherndon.bazelviz.ui.workspace.WorkspaceDiscoveryScriptStore.MAX_SCRIPT_BYTES` | 65536 | Loading refuses a larger script. Saving measures its UTF-8 bytes before creating the sibling temporary file and leaves the previous executable script unchanged when the replacement is too large. |
| Workspace-discovery execution time | `com.holtherndon.bazelviz.ui.workspace.WorkspaceDiscovery.DEFAULT_TIMEOUT` | 10 seconds | The local script process tree is stopped. Complete rows already received remain available with an explicit timeout diagnostic; they are not presented as a complete discovery result. |
| Workspace-discovery stdout bytes | `com.holtherndon.bazelviz.ui.workspace.WorkspaceDiscovery.MAX_STDOUT_BYTES` | 1048576 | The collector continues draining but retains only this prefix. Only complete UTF-8 rows within it are parsed, and the result states that later output was omitted. |
| Workspace-discovery stderr bytes | `com.holtherndon.bazelviz.ui.workspace.WorkspaceDiscovery.MAX_STDERR_BYTES` | 65536 | The collector continues draining but exposes only this bounded diagnostic prefix and states that it was truncated. Stderr never becomes a workspace row. |
| Workspaces accepted from one discovery invocation | `com.holtherndon.bazelviz.ui.workspace.WorkspaceDiscovery.MAX_ACCEPTED_ROWS` | 100 | Further valid, distinct rows are refused individually with their line numbers. A new invocation returns a fresh complete replacement list rather than appending to an older discovered set. |
| Workspace-discovery row diagnostics | `com.holtherndon.bazelviz.ui.workspace.WorkspaceDiscovery.MAX_ROW_DIAGNOSTICS` | 1000 | The first 999 invalid, duplicate, or over-row-limit diagnostics retain their line numbers. The last entry reports the exact number of additional diagnostics omitted, so pathological tiny lines cannot create an unbounded object list. |
| Workspace-discovery cleanup grace | `com.holtherndon.bazelviz.ui.workspace.WorkspaceDiscovery.CLEANUP_GRACE` | 1 second | Timeout first allows termination for this long, then force-kills and gives the process the same bounded reap wait. Output drains share one further grace before their local streams are closed, so inherited pipes cannot hold the discovery worker forever. |

## Ingest and storage limits

| Limit | Constant | Default | When reached |
|---|---|---:|---|
| Journal payload | `com.holtherndon.bazelviz.core.journal.JournalFormat.DEFAULT_MAX_PAYLOAD_BYTES` | 67108864 | The frame is refused rather than truncated, and the capture is marked incomplete. A partial payload on disk would be worse than a missing one. |
| Journal segment | `com.holtherndon.bazelviz.core.journal.JournalFormat.DEFAULT_SEGMENT_BYTES` | 268435456 | Rotation at a frame boundary. Never mid-frame, so every segment is independently readable. |
| Normalization batch | `com.holtherndon.bazelviz.storage.events.EventWriter.DEFAULT_BATCH_SIZE` | 5000 | Not a limit on data, a commit interval. |
| String dictionary cache | `com.holtherndon.bazelviz.storage.events.StringDictionary.DEFAULT_CACHE_ENTRIES` | 65536 | An LRU over interning, not over content: a cache miss costs a query, never a lost string. |
| Diagnostics per code | `com.holtherndon.bazelviz.capture.file.importer.ImportOptions.DEFAULT_MAX_DIAGNOSTICS_PER_CODE` | 1000 | The count keeps rising after the messages stop, so "12,000 of these" is still reported. |
| Executable hashing | `com.holtherndon.bazelviz.runner.exec.BazelExecutableResolver.MAX_HASH_BYTES` | 67108864 | The identity is recorded as unhashed rather than as a hash of part of the file. |
| Managed-command stdout | `com.holtherndon.bazelviz.runner.proc.Subprocess.MAX_STDOUT_BYTES` | 16777216 | The default bounded collector retains this prefix, continues draining the pipe to avoid deadlock, and records the exact received byte count. A local or SSH probe with truncated output is not successful and its diagnostic states that the text is incomplete. |
| Managed-command stderr | `com.holtherndon.bazelviz.runner.proc.Subprocess.MAX_STDERR_BYTES` | 16777216 | As above, independently from stdout so binary or structured stdout is never corrupted by merged diagnostics. OpenSSH helpers and the control master select smaller private diagnostic caps while reusing the same collector. |
| Managed-command tracked descendants | `com.holtherndon.bazelviz.runner.proc.Subprocess.MAX_TRACKED_DESCENDANTS` | 4096 | A managed process that produces more live descendants is stopped and refused. Each sampling pass short-circuits after this limit plus one observation, so both retained history and traversal work are bounded. On macOS shell job control and on Linux the standard `/usr/bin/setsid` or `/bin/setsid` helper establish a private process group before the command runs; this fixed table is the best-effort fallback where grouping is unavailable. |
| Managed-command output-drain grace | `com.holtherndon.bazelviz.runner.proc.Subprocess.OUTPUT_DRAIN_GRACE_MILLIS` | 1000 | After the root exits, each local, OpenSSH, SSH-command, or control-master pipe gets this long to reach EOF. A pipe still inherited by a descendant is closed, reported as incomplete, and the tracked process tree is forcibly reaped instead of leaving a background child alive. |
| One remote capture file | `com.holtherndon.bazelviz.capture.live.CaptureCoordinator.MAX_REMOTE_CAPTURE_FILE_BYTES` | 34359738368 | An execution log, profile, BEP fallback or cquery scope transfer above 32 GiB is refused whole and named in session warnings. Event bytes already journaled through the BES remain usable; the missing auxiliary source is unavailable, never treated as empty. |
| Starlark profile JDBC batch | `com.holtherndon.bazelviz.enrich.starlark.StarlarkProfileWriter.JDBC_BATCH_SIZE` | 5000 | A memory/transaction work bound, not a row limit. The streaming importer flushes pending normalized and derived statements, then continues with every record. |

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
| Starlark profile expanded bytes | `com.holtherndon.bazelviz.enrich.starlark.StarlarkCpuProfileParser.MAX_DECOMPRESSED_BYTES` | 1073741824 | Gzip output above 1 GiB is refused during streaming. The raw gzip and prior complete imported profile remain intact; no prefix is presented as a complete profile. |
| One Starlark pprof embedded record or string | `com.holtherndon.bazelviz.enrich.starlark.StarlarkCpuProfileParser.MAX_RECORD_BYTES` | 16777216 | The profile transaction is refused before allocating or decoding a larger length-delimited value. |
| One Starlark sampled stack | `com.holtherndon.bazelviz.enrich.starlark.StarlarkCpuProfileParser.MAX_STACK_DEPTH` | 4096 | A deeper sample is refused whole. The importer never drops lower frames and calls the remaining prefix a stack. |
| Values in one Starlark sample | `com.holtherndon.bazelviz.enrich.starlark.StarlarkCpuProfileParser.MAX_SAMPLE_VALUES` | 1024 | A compact hostile sample cannot expand into an unbounded primitive array. The whole import is refused rather than losing sample types. |
| Labels in one Starlark sample | `com.holtherndon.bazelviz.enrich.starlark.StarlarkCpuProfileParser.MAX_SAMPLE_LABELS` | 4096 | A compact sequence of empty labels cannot grow an unbounded object list. The whole import is refused. |
| Inline lines in one Starlark location | `com.holtherndon.bazelviz.enrich.starlark.StarlarkCpuProfileParser.MAX_LOCATION_LINES` | 4096 | A location with more inline frames is refused rather than partially symbolized. |
| Starlark pprof top-level records | `com.holtherndon.bazelviz.enrich.starlark.StarlarkCpuProfileParser.MAX_TOP_LEVEL_RECORDS` | 25000000 | More samples, strings, functions, locations, mappings, or sample types than this combined bound refuse the transaction. Streaming and JDBC batching keep accepted rows off heap. |
| Starlark pprof normalized child records | `com.holtherndon.bazelviz.enrich.starlark.StarlarkCpuProfileParser.MAX_CHILD_RECORDS` | 100000000 | More sample values/frames/labels or location lines than this combined bound refuse the transaction. The raw file and exact prior imported state remain available. |
| Expanded symbols in one Starlark sample | `com.holtherndon.bazelviz.enrich.starlark.StarlarkProfileWriter.MAX_EXPANDED_SYMBOLS_PER_SAMPLE` | 65536 | A stack whose physical locations expand to more line/symbol records is refused whole. This bounds derived-index memory even when many locations each contain many inline frames. |

## Application logging limits

These bounds apply only to diagnostic log records. They never truncate a raw
capture, session database, build console file or terminal stream.

| Limit | Constant | Default | When reached |
|---|---|---:|---|
| Active application log file | `com.holtherndon.bazelviz.app.logging.ApplicationLogging.MAX_LOG_FILE_BYTES` | 8388608 | Logback closes and compresses the active file at the next record boundary, then continues in a new `application.log`; no caller waits for rotation. |
| Application log history age | `com.holtherndon.bazelviz.app.logging.ApplicationLogging.MAX_HISTORY_DAYS` | 7 | Older rolled files are removed by the rolling policy and a final writer-side cleanup. The current active file remains available from the Diagnostics menu. |
| Rolled application log history bytes | `com.holtherndon.bazelviz.app.logging.ApplicationLogging.TOTAL_LOG_BYTES` | 67108864 | The logging backend removes the oldest compressed history until the archive is within the cap, including after the final rollover. The active file has its separate bound above. |
| Application log queue records | `com.holtherndon.bazelviz.app.logging.ApplicationLogging.LOG_QUEUE_CAPACITY` | 8192 | The caller never blocks. Each record that cannot enter the queue increments an exact counter shown in Diagnostics; the writer emits an overflow warning when it catches up. Session and capture data are unaffected. |
| Application log shutdown flush | `com.holtherndon.bazelviz.app.logging.ApplicationLogging.MAX_FLUSH_TIME` | 5 seconds | Close waits up to this long. Records still queued at the deadline are added to the exact dropped total. A record already inside the OS/file-appender write is no longer queued and may finish asynchronously after close returns. |

## Timing

| Limit | Constant | Default |
|---|---|---|
| Overview refresh | `com.holtherndon.bazelviz.ui.overview.OverviewPanel.REFRESH_INTERVAL` | 2 seconds |
| Import progress reporting | `com.holtherndon.bazelviz.capture.file.importer.ImportOptions.DEFAULT_PROGRESS_INTERVAL_MILLIS` | 100 ms |
| Window background-worker shutdown grace | `com.holtherndon.bazelviz.ui.MainWindow.WORKER_SHUTDOWN_GRACE` | 10 seconds |
| Window background-worker forced reap grace | `com.holtherndon.bazelviz.ui.MainWindow.WORKER_FORCED_SHUTDOWN_GRACE` | 2 seconds |
| UI-owned ordinary worker drain grace | `com.holtherndon.bazelviz.ui.lifecycle.ExecutorClose.ORDINARY_DRAIN_GRACE` | 10 seconds |
| UI-owned worker forced reap grace | `com.holtherndon.bazelviz.ui.lifecycle.ExecutorClose.FORCED_REAP_GRACE` | 2 seconds |
| Accepted file-editor save drain grace | `com.holtherndon.bazelviz.ui.lifecycle.ExecutorClose.FILE_SAVE_DRAIN_GRACE` | 15 minutes |

The metric collection is deliberately **not** on a timer: it scans every action
(898 ms at 250,000) and runs once per session. See `docs/performance.md`.

## What plan 20.3 asks for and this does not yet have

Plan 20.3 lists nineteen limits that "settings must include". Fifteen of them
exist as constants and can be changed by a caller; **none of those limits is editable in Preferences**.
Preferences currently has **Theme** and **Discovery** tabs; neither exposes a
plan 20.3 limit. That is the single largest gap between this table and plan
20.3, and it is stated here rather than implied by a page that lists only what
exists.

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
