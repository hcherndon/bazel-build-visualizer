#!/bin/bash
set -euo pipefail

runfiles_root="${TEST_SRCDIR:?}/${TEST_WORKSPACE:?}"
readme="$runfiles_root/README.md"
changelog="$runfiles_root/CHANGELOG.md"
license="$runfiles_root/LICENSE"
security="$runfiles_root/SECURITY.md"
docs="$runfiles_root/docs"

fail() {
    echo "release docs test: $*" >&2
    exit 1
}

if command -v sha256sum >/dev/null 2>&1; then
    license_hash="$(sha256sum "$license" | awk '{print $1}')"
else
    license_hash="$(shasum -a 256 "$license" | awk '{print $1}')"
fi
[[ "$license_hash" == "c71d239df91726fc519c6eb72d318ec65820627232b2f796219e87dcf35d0ab4" ]] ||
    fail "LICENSE is not the exact Apache-2.0 text"

grep -Fq '## 0.1.0 — 2026-09-04' "$changelog" ||
    fail "CHANGELOG has no 0.1.0 release entry"
grep -Fq '## 0.1.0 support' "$readme" || fail "README has no 0.1.0 support section"
grep -Fq 'Three late hardening batches remain blocked' "$readme" ||
    fail "README does not disclose the unmerged hardening batches"
grep -Fq 'Saved Query-library' "$readme" ||
    fail "README does not disclose saved Query-library durability"
grep -Fq '## Report a vulnerability' "$security" ||
    fail "SECURITY has no reporting guidance"
grep -Fq '### Query' "$docs/user-guide.md" || fail "user guide has no Query section"
for blocker in \
    'Archive import and catalog recovery' \
    'Auxiliary enrichment and query ingestion' \
    'Query, Events, and Errors inspection'; do
    grep -Fq "$blocker" "$docs/implementation-status.md" ||
        fail "implementation status omits release blocker: $blocker"
done
grep -Fq 'Manual execution-log attachment (planned)' "$docs/capture-sources.md" ||
    fail "capture sources still imply manual execution-log attachment is available"
grep -Fq 'No current UI or service API' "$docs/capture-sources.md" ||
    fail "capture sources do not state the manual-attachment boundary"
grep -Fq 'Schema v10 — graph node-index integrity' "$docs/database-schema.md" ||
    fail "database schema docs do not describe schema v10"
grep -Fq 'GraphLayoutService.MAX_CACHE_ENTRIES' "$docs/limits.md" ||
    fail "limits docs omit the graph layout entry cap"
grep -Fq 'The shared page toolbar holds Graph' "$docs/user-guide.md" ||
    fail "user guide does not explain Graph toolbar ownership"
grep -Fq 'done is met in 33 of 37 items, not 37 of 37' "$docs/implementation-status.md" ||
    fail "implementation status miscounts the section 25 release criteria"
grep -Fq 'security checks have known open blockers' "$docs/implementation-status.md" ||
    fail "implementation status counts blocked security checks as complete"

[[ "$(grep -Ec '^!\[' "$readme")" -eq 3 ]] ||
    fail "README must contain exactly three screenshots"
for image in \
    workspace-overview.png \
    timeline.png \
    workspaces.png; do
    [[ "$(grep -Fc "docs/images/$image" "$readme")" -eq 1 ]] ||
        fail "README must reference $image exactly once"
    [[ -f "$docs/images/$image" ]] || fail "missing docs/images/$image"
done

for linked in \
    user-guide.md \
    implementation-status.md \
    architecture.md \
    performance.md \
    bazel-compatibility.md \
    privacy.md \
    security-review.md \
    troubleshooting.md; do
    [[ -f "$docs/$linked" ]] || fail "README link target docs/$linked is missing"
done

for anchor in c4-system-configuration contradiction-1 contradiction-3 contradiction-4; do
    grep -Fq "<a id=\"$anchor\"></a>" "$docs/bep-content.md" ||
        fail "BEP content anchor $anchor is missing"
    grep -Fq "(#$anchor)" "$docs/bep-content.md" ||
        fail "BEP content anchor $anchor is not linked"
done

if grep -Eq -- '--test_tag_filters=([[:space:]]|$)' "$docs/bazel-compatibility.md"; then
    fail "Bazel compatibility docs clear the safety filter"
fi
if grep -Fq '77,5xx' "$readme" "$docs/performance.md" "$docs/implementation-status.md"; then
    fail "non-measurement 77,5xx remains in release claims"
fi

grep -Fq 'BBV_MAC_SIGNING_IDENTITY' "$docs/packaging.md" ||
    fail "release checklist does not require a signing identity"
grep -Fq 'it does not sign it' "$docs/packaging.md" ||
    fail "release checklist does not distinguish notarization from signing"
for legal_contract in \
    '//app:app_runtime_maven_deps' \
    'exact 40 Maven coordinates' \
    'PPROF-APACHE-2.0.txt' \
    'JSVG-2.1.0-CORRESPONDING-SOURCE.tar.gz' \
    'complete gRPC, FlatLaf, JNA, Pty4J'; do
    grep -Fq "$legal_contract" "$docs/packaging.md" ||
        fail "packaging guide omits legal contract: $legal_contract"
done
grep -Fq '(troubleshooting.md#timeline-pinch-does-not-zoom-on-macos)' \
    "$docs/user-guide.md" || fail "user guide does not link the macOS pinch fix"
grep -Fq '### Timeline pinch does not zoom on macOS' "$docs/troubleshooting.md" ||
    fail "troubleshooting guide has no macOS pinch anchor"
grep -Fq 'select it and press Enter' "$docs/user-guide.md" ||
    fail "user guide does not document repository Enter-to-open"
grep -Fq 'Command+W on macOS' "$docs/user-guide.md" ||
    fail "user guide does not document native editor close"
for manual_gate in \
    'signed, notarized, and stapled' \
    'Gatekeeper and Finder' \
    'local and SSH Terminal' \
    'packaged Query and cancellation' \
    'artifact SHA-256' \
    'supervised real-Bazel' \
    'parser fuzz' \
    'dependency-advisory'; do
    grep -Fq "$manual_gate" "$docs/packaging.md" ||
        fail "packaging guide omits manual gate: $manual_gate"
done
grep -Fq 'pure-Java Swing icon adapter' "$docs/implementation-status.md" ||
    fail "implementation status does not qualify the pure-Java icon adapter"
grep -Fq 'FlatLaf core separately carries seven Windows, Linux and macOS native' \
    "$docs/implementation-status.md" ||
    fail "implementation status omits FlatLaf core native resources"
grep -Fq '> Historical audit record.' "$docs/phase10-audit.md" ||
    fail "Phase 10 audit is not marked historical"
if grep -Eq '84,000|shortfall is gRPC|gap is gRPC|80k/s against 86k/s|Coalescing acknowledgements would close' \
    "$docs/performance.md" \
    "$docs/implementation-status.md" \
    "$docs/phase2-contracts.md"; then
    fail "unsupported capture-throughput cause remains in release claims"
fi
