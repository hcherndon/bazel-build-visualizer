# Capture sources

How build data gets into the tool. Every source converges on the same
raw-first journal (ADR-004) so the rest of the pipeline is
source-agnostic. Implementation arrives in Phase 2 (`capture-bes`,
`capture-file`, `bazel-runner`); this page fixes the vocabulary and the
matrix now so UI and docs never blur the sources together.

## Source matrix (plan 4.1)

| Source | How it arrives | Liveness | Fidelity | User effort | Notes |
|---|---|---|---|---|---|
| Launched build | Tool runs `bazel` itself (`bazel-runner`) with a transparent instrumentation plan (ADR-007), streaming BEP to the built-in BES endpoint | Live | Highest — BES stream + execution log + timing profile, all correlated | Lowest — click Run | The flagship path; preflight checks run first (`PREFLIGHT` state) |
| Attached live capture | User adds `--bes_backend=grpc://127.0.0.1:<port>` to their own invocation; tool listens (`capture-bes`) | Live | High — full BES stream; auxiliary files only if user also enables them | Low — copy one flag | Loopback only (docs/privacy.md) |
| BEP binary file import | User ran `bazel --build_event_binary_file=…`; tool ingests the file (`capture-file`) | Post hoc | High — complete event stream, no liveness | Low | Preferred file format |
| BEP JSON file import | `--build_event_json_file=…` ingested by `capture-file` | Post hoc | Medium-high — same events, lossier types, larger files | Low | Accepted for convenience |
| Execution log import | `--execution_log_binary_file=…` (spawn log) attached to an existing session | Post hoc | Adds per-spawn runner/cache/timing detail BEP lacks | Medium | Enrichment source, not standalone (Phase 7) |
| JSON trace profile import | `--profile=…` Chrome-trace file attached to an existing session | Post hoc | Adds internal Bazel phase/thread timing | Medium | Enrichment source, not standalone (Phase 7) |

A *session* records which sources fed it; metrics report completeness per
source (docs/metric-definitions.md) rather than pretending absent sources
were empty.
