# Vendored protobuf sources

All files under `src/main/proto/` are vendored verbatim from upstream at the
pinned revisions below. The directory layout mirrors each upstream repository
root so the repo-relative `import` statements inside the files resolve without
rewriting. Do not edit these files by hand; bump the pins in
`update-protos.sh` and re-run it to update.

Retrieval date: 2026-09-02

## Bazel (build event protocol and friends)

- Source repo: https://github.com/bazelbuild/bazel
- Pinned tag: `9.2.0` (latest stable release at retrieval date)
- License: Apache-2.0 (copy: `third_party-licenses/BAZEL_LICENSE`)

Files (paths relative to `src/main/proto/`, identical to Bazel repo root):

| File | Notes |
| --- | --- |
| `src/main/java/com/google/devtools/build/lib/buildeventstream/proto/build_event_stream.proto` | BEP event stream (required) |
| `src/main/java/com/google/devtools/build/lib/packages/metrics/package_load_metrics.proto` | required |
| `src/main/protobuf/command_line.proto` | required |
| `src/main/protobuf/option_filters.proto` | required |
| `src/main/protobuf/invocation_policy.proto` | required |
| `src/main/protobuf/failure_details.proto` | required |
| `src/main/protobuf/bazel_flags.proto` | `bazel help flags-as-proto` output; the capability detector's primary probe (Phase 2). No imports |
| `src/main/protobuf/action_cache.proto` | required |
| `src/main/protobuf/spawn.proto` | required |
| `src/main/protobuf/analysis_v2.proto` | required |
| `src/main/protobuf/build.proto` | required |
| `src/main/protobuf/strategy_policy.proto` | transitive: imported by `invocation_policy.proto` |
| `src/main/protobuf/stardoc_output.proto` | transitive: imported by `build.proto` (rule_class_info field); has no further imports, so the closure stops here |

## googleapis (Build Event Service gRPC API)

- Source repo: https://github.com/googleapis/googleapis
- Pinned commit: `c3e3d8a2031ec31f0f81fa42454ba55c7b40f284` (master at retrieval date)
- License: Apache-2.0 (copy: `third_party-licenses/GOOGLEAPIS_LICENSE`)

Files (paths relative to `src/main/proto/`, identical to googleapis repo root):

| File | Notes |
| --- | --- |
| `google/devtools/build/v1/publish_build_event.proto` | BES service (required) |
| `google/devtools/build/v1/build_events.proto` | required |
| `google/devtools/build/v1/build_status.proto` | required |
| `google/api/annotations.proto` | transitive: imported by `publish_build_event.proto` |
| `google/api/client.proto` | transitive: imported by `publish_build_event.proto` |
| `google/api/field_behavior.proto` | transitive: imported by `publish_build_event.proto` |
| `google/api/http.proto` | transitive: imported by `annotations.proto` |
| `google/api/launch_stage.proto` | transitive: imported by `client.proto` |

## google/pprof (Starlark CPU profiles)

- Source repo: https://github.com/google/pprof
- Pinned commit: `ca85771921e4d23ebb56030bf1e488f215f26d36`
- License: Apache-2.0 (copy: `third_party-licenses/PPROF_LICENSE`)

Files (paths relative to `src/main/proto/`, using the declared proto package):

| File | Notes |
| --- | --- |
| `perftools/profiles/profile.proto` | gzip pprof schema written by Bazel's `--starlark_cpu_profile` |

## Not vendored

`google/protobuf/*` well-known types (any, duration, timestamp, empty,
wrappers, descriptor) are provided by `protobuf-java` / protoc and are
deliberately not vendored.
