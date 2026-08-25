#!/bin/bash
# Submits the built package to Apple's notary service, ported from the Gradle
# notarize task during the Bazel migration.
#
#   bazel run //app:jpackage -- --type=dmg
#   bazel run //app:notarize
#
# Unlike the Gradle task, this does not rebuild the package first: it
# notarizes what dist/jpackage already holds and says so plainly when there
# is nothing to notarize. Credentials never touch this repository: store them
# once with `xcrun notarytool store-credentials <profile> …` and export the
# profile name as BBV_MAC_NOTARY_PROFILE. This script never reads an Apple
# ID, a password or an app-specific password, and never writes one anywhere.
set -euo pipefail

if [[ -z "${BUILD_WORKSPACE_DIRECTORY:-}" ]]; then
    echo "error: run this via 'bazel run //app:notarize' from the workspace" >&2
    exit 1
fi

if [[ -z "${BBV_MAC_NOTARY_PROFILE:-}" ]]; then
    cat >&2 <<'MSG'
error: BBV_MAC_NOTARY_PROFILE is not set. Store credentials once with
  xcrun notarytool store-credentials <profile> --apple-id … --team-id …
and export the profile name. This build never reads an Apple ID, a
password or an app-specific password, and never writes one anywhere.
MSG
    exit 1
fi

OUT="$BUILD_WORKSPACE_DIRECTORY/dist/jpackage"
ARTIFACTS=()
while IFS= read -r -d '' artifact; do
    ARTIFACTS+=("$artifact")
done < <(find "$OUT" -type f \( -name '*.dmg' -o -name '*.pkg' \) -print0 2>/dev/null)

if [[ ${#ARTIFACTS[@]} -eq 0 ]]; then
    echo "error: no .dmg or .pkg under $OUT. Run" >&2
    echo "  bazel run //app:jpackage -- --type=dmg" >&2
    echo "first; an app-image cannot be notarized on its own." >&2
    exit 1
fi

for artifact in "${ARTIFACTS[@]}"; do
    echo "Notarizing $(basename "$artifact")" >&2
    xcrun notarytool submit "$artifact" --keychain-profile "$BBV_MAC_NOTARY_PROFILE" --wait
    xcrun stapler staple "$artifact"
done
