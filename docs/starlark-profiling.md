# Starlark CPU profiling

The **Starlark CPU Profile** page identifies expensive rule and macro code from
Bazel's `--starlark_cpu_profile` output. It is complementary to the Timeline
and Critical Path pages: those explain elapsed build scheduling, while this
page explains sampled CPU consumed by Starlark evaluation.

## Capture

**Performance Diagnostics** and **Full Graph Diagnostics** request the profile.
After probing the selected local or SSH Bazel, the launch review adds:

```text
--starlark_cpu_profile=<managed raw directory>/starlark-cpu.pprof.gz
```

The flag remains disableable in launch review. An explicit user flag is never
replaced; the plan instead explains that its file can be imported separately
when manual attachment is supported. For SSH Workspaces, Bazel writes into the
private remote staging directory and the completed gzip file returns through
the same bounded SFTP path as the execution log and JSON trace profile. Capture
finalization still attempts that transfer after an interruption. If an existing
remote artifact cannot be copied, its private staging directory is retained and
the session warning gives the exact recovery path instead of deleting the only
copy.

The raw gzip file is retained before parsing. A failed Starlark profile import
does not invalidate BEP data or other enrichments.

## What the page shows

- **Summary** — a responsive, vertically scrolling set of sampled CPU, profile
  duration, sampling-period, record/function/file, capture-correlation, and
  attribution measurements. A valid profile goes straight to those results;
  status and failure detail appear only when profile data is unavailable.
- **Hot Functions** — searchable and sortable self and cumulative CPU with
  sample and call-context counts.
- **Files** — the same measurements aggregated by Starlark source file.
- **Call Graph** — a bounded top-down caller-to-callee graph plus exact paged
  caller and callee tables for the selected function. Each box shows source,
  self CPU, and cumulative CPU; cumulative CPU controls its heat. Box size is
  selectable between Self CPU (the default), Cumulative CPU, and Uniform.
  Unknown size measurements use a neutral size rather than looking like zero.
  Arrow width, colour, and label encode sampled CPU attributed to that
  relationship. Hovering a box immediately shows its function, self CPU, and
  cumulative CPU; source path and line stay in the selected details and source
  action instead of crowding the tooltip. Click a box for exact relationships,
  drag a box to reorganize the drawing, double-click to focus it, drag empty
  space to pan, scroll to zoom, or right-click to open its source. **Reset
  moved nodes** restores the deterministic placement.
- **Flame** — a root-to-leaf icicle view. Hover details appear immediately and
  keep to the function plus self and cumulative CPU; source stays in the
  selected details and source action. Double-click narrows to a context; reset
  returns to all roots. Its drawing budget is explicit and reports the exact
  omitted count; all rows remain in SQLite.

Double-clicking a function or file, or choosing **Open source file…**, uses the
Workspace file service. The line is the function's declared start line and is
only a navigation hint; the shared editor places the caret there after loading.

All reads and directed-graph layout run on a view-owned worker. Tables use
exact offset paging. The call graph asks SQLite for a configurable hot-function
projection and strongest relationships; it states the complete function total,
the exact arrows between visible functions, and every omission. Relationships
touching functions outside the projection are explicitly outside that arrow
total. Dependency ranks wrap into compact rows so a wide set of unrelated
roots does not force every box and label to an unreadable fit scale. The flame
view asks SQLite for a bounded hierarchy slice instead of
retaining the whole profile in Swing.

## Querying the profile

Schema v9 preserves the complete gzip source plus normalized sample and symbol
records with separate derived indexes. The Query page discovers all database
objects. The most useful stable views are:

```sql
SELECT function_name, filename, start_line,
       self_value, cumulative_value
FROM starlark_hot_functions
ORDER BY self_value DESC;
```

```sql
SELECT caller_function, callee_function, value, sample_count
FROM starlark_resolved_call_edges
ORDER BY value DESC;
```

`value`, `self_value`, and `cumulative_value` are microseconds for Bazel's
current Starlark profile. Raw sample values, stacks, labels, strings,
functions, locations, and mappings remain available in the
`starlark_profile_*` tables. `starlark_call_nodes`,
`starlark_function_metrics`, `starlark_file_metrics`, and
`starlark_call_edges` are rebuildable indexes over those raw normalized
records. Metadata partitions CPU and sample records into attributed and
unattributed amounts, so missing or ambiguous symbols are never hidden.

## Interpretation and limits

- This is statistical **CPU**, not wall time. It excludes time blocked on I/O
  and time a runnable Starlark thread did not receive a CPU.
- All Starlark threads contribute. Total sampled CPU can exceed the profile's
  wall duration, so the Summary also reports average sampled CPU cores.
- Samples contain no timestamps or stable thread ids, so they are not aligned
  onto the Timeline.
- Bazel's sampled location line is not reliable enough for a line heat map.
  Self CPU uses the first function on the leaf location. Cumulative flat
  metrics include every inline function and non-empty source file once per
  sample. Missing names/files remain explicit in the Summary attribution
  coverage; source navigation uses the function definition line.
- The physical call tree keeps one node per sampled location. A context counts
  as fully attributed only when every location resolves to exactly one named
  function; inline or missing symbols remain queryable but make that context's
  coverage partial.
- Function and location ids are process-local implementation ids. They are
  useful inside one session and must not be compared across builds.
- The sampling signal may arrive late or be dropped, and pprof carries no
  dropped-sample count. An empty valid profile means Bazel produced a profile
  below the sampler's resolution; it is distinct from no captured profile.

Real probes on Bazel 6.5.0, 7.6.1, 8.4.1, and 9.2.0 produced the same shape:
gzip-compressed pprof, one `CPU` / `microseconds` sample type, and a 10,000 µs
period. The importer nevertheless validates the sample type, unit,
cardinalities, and all references instead of assuming a version guarantees
them. It validates `period_type` separately and exposes a microsecond sampling
period only for CPU values convertible from ns, us, ms, or s; otherwise the
period is unavailable while the structurally valid profile remains usable.

## Other Starlark diagnostics

The JSON trace profile already supplies sparse wall-clock Starlark tasks such
as parsing, user/builtin calls, and repository work. Bazel normally omits short
events; automatically adding `--record_full_profiler_data` would materially
raise capture overhead and is intentionally avoided.

`--experimental_command_profile` records general JVM activity as Java Flight
Recorder data on newer Bazel releases, but does not directly attribute cost to
Starlark source. Starlark allocation tracking requires a startup Java agent,
server-wide state, and a later `bazel dump --skylark_memory` command. Both are
useful future enrichments, but neither is silently enabled because they change
the Bazel server or measure a different question.

Primary references:

- [Bazel rule performance](https://bazel.build/rules/performance)
- [pprof profile schema](https://github.com/google/pprof/blob/main/proto/profile.proto)
- [Bazel JSON trace profile](https://bazel.build/advanced/performance/json-trace-profile)
