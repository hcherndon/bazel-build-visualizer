#!/usr/bin/env bash
# Re-downloads every vendored proto file from the pinned revisions below so
# that an upstream bump is a single controlled diff: edit the pins, run this
# script from anywhere, review `git diff`, and update PROTO_SOURCES.md.
set -euo pipefail

BAZEL_TAG="9.2.0"
GOOGLEAPIS_COMMIT="c3e3d8a2031ec31f0f81fa42454ba55c7b40f284"
PPROF_COMMIT="ca85771921e4d23ebb56030bf1e488f215f26d36"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROTO_ROOT="${SCRIPT_DIR}/src/main/proto"
LICENSE_DIR="${SCRIPT_DIR}/third_party-licenses"

# Paths are repo-relative in the upstream repository and must stay that way
# locally, because the .proto files import each other by these exact paths.
BAZEL_FILES=(
    src/main/java/com/google/devtools/build/lib/buildeventstream/proto/build_event_stream.proto
    src/main/java/com/google/devtools/build/lib/packages/metrics/package_load_metrics.proto
    src/main/protobuf/action_cache.proto
    src/main/protobuf/analysis_v2.proto
    src/main/protobuf/bazel_flags.proto
    src/main/protobuf/build.proto
    src/main/protobuf/command_line.proto
    src/main/protobuf/failure_details.proto
    src/main/protobuf/invocation_policy.proto
    src/main/protobuf/option_filters.proto
    src/main/protobuf/spawn.proto
    src/main/protobuf/stardoc_output.proto
    src/main/protobuf/strategy_policy.proto
)

GOOGLEAPIS_FILES=(
    google/api/annotations.proto
    google/api/client.proto
    google/api/field_behavior.proto
    google/api/http.proto
    google/api/launch_stage.proto
    google/devtools/build/v1/build_events.proto
    google/devtools/build/v1/build_status.proto
    google/devtools/build/v1/publish_build_event.proto
)

fetch() {
    local base_url="$1" rel="$2"
    local dest="${PROTO_ROOT}/${rel}"
    mkdir -p "$(dirname "${dest}")"
    echo "fetching ${rel}"
    curl -sSfL -o "${dest}" "${base_url}/${rel}"
}

for f in "${BAZEL_FILES[@]}"; do
    fetch "https://raw.githubusercontent.com/bazelbuild/bazel/${BAZEL_TAG}" "$f"
done

for f in "${GOOGLEAPIS_FILES[@]}"; do
    fetch "https://raw.githubusercontent.com/googleapis/googleapis/${GOOGLEAPIS_COMMIT}" "$f"
done

fetch "https://raw.githubusercontent.com/google/pprof/${PPROF_COMMIT}" \
    "perftools/profiles/profile.proto"

mkdir -p "${LICENSE_DIR}"
echo "fetching LICENSE files"
curl -sSfL -o "${LICENSE_DIR}/BAZEL_LICENSE" \
    "https://raw.githubusercontent.com/bazelbuild/bazel/${BAZEL_TAG}/LICENSE"
curl -sSfL -o "${LICENSE_DIR}/GOOGLEAPIS_LICENSE" \
    "https://raw.githubusercontent.com/googleapis/googleapis/${GOOGLEAPIS_COMMIT}/LICENSE"
curl -sSfL -o "${LICENSE_DIR}/PPROF_LICENSE" \
    "https://raw.githubusercontent.com/google/pprof/${PPROF_COMMIT}/LICENSE"

# Guard against the import closure growing: everything imported must either be
# vendored here or be a google/protobuf/* well-known type shipped with protoc.
echo "verifying import closure"
missing=0
while IFS= read -r imp; do
    case "${imp}" in
        google/protobuf/*) continue ;;
    esac
    if [ ! -f "${PROTO_ROOT}/${imp}" ]; then
        echo "MISSING transitive import: ${imp} (add it to this script)" >&2
        missing=1
    fi
done < <(find "${PROTO_ROOT}" -name '*.proto' -exec grep -h '^import' {} + \
         | sed -E 's/^import (public )?"([^"]+)";.*/\2/' | sort -u)

if [ "${missing}" -ne 0 ]; then
    exit 1
fi
echo "done: $((${#BAZEL_FILES[@]} + ${#GOOGLEAPIS_FILES[@]} + 1)) proto files refreshed"
