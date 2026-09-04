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
grep -Fq '## Report a vulnerability' "$security" ||
    fail "SECURITY has no reporting guidance"
grep -Fq '### Query' "$docs/user-guide.md" || fail "user guide has no Query section"

[[ "$(grep -Ec '^!\[' "$readme")" -eq 3 ]] ||
    fail "README must contain exactly three screenshot placeholders"
for image in \
    readme-overview-placeholder.svg \
    readme-analysis-placeholder.svg \
    readme-workspaces-placeholder.svg; do
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
