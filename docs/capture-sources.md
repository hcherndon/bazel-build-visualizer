# Capture sources

How build data gets into the tool. Every source converges on the same
raw-first journal (ADR-004) so the rest of the pipeline is
source-agnostic. The Phase 2 implementation lives in `capture-bes`,
`capture-file` and `runner`; this page fixes the vocabulary so UI and docs
never blur the sources together.

## Source matrix (plan 4.1)

| Source | How it arrives | Liveness | Fidelity | User effort | Notes |
|---|---|---|---|---|---|
| Local launched build | Tool runs `bazel` itself (`runner`) with a transparent instrumentation plan (ADR-007), streaming BEP to the built-in BES endpoint | Live | Highest — BES stream + execution log + timing profile + sampled Starlark CPU profile, all capture-correlated | Lowest — click Run | The flagship path; local workspace and capability checks run first (`PREFLIGHT` state) |
| SSH launched build | Tool borrows the selected SSH Workspace's system-OpenSSH session; a capture-scoped reverse loopback forward carries BES traffic to the desktop endpoint (ADR-011) | Live | Same planned sources as a local launch; auxiliary files are staged remotely and copied into local `raw/` before import | Choose a saved or new SSH Workspace, then click Run | Workspace connection and Linux/OpenSSH/SFTP capture preflight must succeed before review; the forced TTY merges the primary command's stdout/stderr |
| Attached live capture | User adds `--bes_backend=grpc://127.0.0.1:<port>` to their own invocation; tool listens (`capture-bes`) | Live | High — full BES stream; current UI does not attach separately written auxiliary files | Low — copy one flag | Loopback only (docs/privacy.md) |
| BEP binary file import | User ran `bazel --build_event_binary_file=…`; tool ingests the file (`capture-file`) | Post hoc | High — complete event stream, no liveness | Low | Preferred file format |
| BEP JSON file import | `--build_event_json_file=…` ingested by `capture-file` | Post hoc | Medium-high — same events, lossier types, larger files | Low | Accepted for convenience |
| Manual execution-log attachment (planned) | A user-selected spawn log would be attached to an existing session | Not implemented | Would add per-spawn runner/cache/timing detail BEP lacks | Medium | No current UI or service API; managed capture is the supported source |
| Manual JSON trace-profile attachment (planned) | A user-selected Chrome-trace profile would be attached to an existing session | Not implemented | Would add internal Bazel phase/thread timing | Medium | No current UI or service API; managed capture is the supported source |
| Managed Starlark CPU profile | `--starlark_cpu_profile=…` gzip pprof written by a Performance or Full launch | Finalized after the command | Adds sampled Starlark function, file, stack and caller/callee CPU data | None beyond choosing the preset | Raw-first enrichment; CPU is not wall time and carries no per-sample timestamp |

A *session* records which sources fed it; metrics report completeness per
source (docs/metric-definitions.md) rather than pretending absent sources
were empty.

Execution logs and profiles are currently imported only when the application
planned and captured their exact managed files for that invocation. Manual
post-hoc attachment is future work. Managed capture chooses the compact
execution log where supported and the binary form otherwise; Bazel's JSON
execution-log flag may exist, but JSON execution logs are not a supported
analysis source. The current importer also does not yet explicitly reject that
format before parsing. The bounded-ingestion t3 branch that closes this and
other auxiliary limits is blocked and unmerged.

Remote execution changes transport, not ownership of the capture. Selecting an
SSH Workspace explicitly opens and owns its connection; capture borrows that
connection without taking over its lifetime. The BES listener and managed
session stay on the desktop. Bazel sees only an allocated port on remote
`127.0.0.1`; the execution log, both profiles and any BEP fallback are written to a
private remote staging directory, transferred through SFTP under the documented
per-file bound, and only then decoded locally. Aquery and cquery use non-TTY SSH
channels so protobuf bytes are not altered. A missing or refused transfer
becomes an explicit unavailable source while already-journaled BES events remain
intact.

The manifest records whether the invocation ran locally or through SSH,
including a display destination and optional port but no credentials. That is
provenance only: importing or reopening the session cannot reconnect, launch a
terminal or execute the recorded command. A user must choose a live Workspace
separately.
