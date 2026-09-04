#!/bin/bash
# Builds a macOS application image or installer with jpackage (plan 24,
# Phase 9), ported from the Gradle jpackage task during the Bazel migration.
#
#   bazel run //app:jpackage                       # app-image (unsigned)
#   bazel run //app:jpackage -- --type=dmg         # dmg, for notarization
#
# The input is the deploy jar (//app:app_deploy.jar) rather than Gradle's
# installDist layout: a single fat jar is exactly what a desktop app that is
# never on anybody else's classpath wants, and it is the artifact Bazel
# already builds deterministically.
#
# jpackage itself comes from the LOCAL JDK 25 (JAVA_HOME, or PATH) — the remote
# JDK Bazel compiles with is a build-time toolchain, and packaging for the
# host is the one step that is honestly host-specific.
#
# Signing is opt-in through the environment, exactly as under Gradle: an
# unsigned build is a perfectly good local build; it is only distribution
# that needs a Developer ID, and that is where BBV_MAC_SIGNING_IDENTITY
# lives. This script never reads gradle.properties, an Apple ID, or a
# password, and never writes credentials anywhere.
set -euo pipefail

if [[ -z "${BUILD_WORKSPACE_DIRECTORY:-}" ]]; then
    echo "error: run this via 'bazel run //app:jpackage' from the workspace" >&2
    exit 1
fi

# AppInfo.VERSION and PROJECT_VERSION are the 0.1.0 product version. macOS
# refuses a CFBundleVersion whose first component is zero, so jpackage receives
# a separate build version there. The custom Info.plist template keeps
# CFBundleShortVersionString at the product version users see.
PROJECT_VERSION="0.1.0"
MACOS_BUNDLE_VERSION="1"
PACKAGE_TYPE="app-image"

for arg in "$@"; do
    case "$arg" in
        --type=*) PACKAGE_TYPE="${arg#--type=}" ;;
        *)
            echo "error: unknown argument '$arg' (supported: --type=<app-image|dmg|pkg>)" >&2
            exit 1
            ;;
    esac
done

PACKAGE_VERSION="$PROJECT_VERSION"
if [[ "$(uname -s)" == "Darwin" && "$PROJECT_VERSION" == 0.* ]]; then
    PACKAGE_VERSION="$MACOS_BUNDLE_VERSION"
    echo "jpackage: macOS requires a positive CFBundleVersion, so this bundle uses" >&2
    echo " build version $PACKAGE_VERSION; its product version remains $PROJECT_VERSION." >&2
fi

JPACKAGE="jpackage"
if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/jpackage" ]]; then
    JPACKAGE="$JAVA_HOME/bin/jpackage"
fi
if ! command -v "$JPACKAGE" > /dev/null; then
    echo "error: no jpackage found. Install a local JDK 25 (jpackage ships with it)" >&2
    echo " and expose it via JAVA_HOME or PATH; the hermetic build JDK is not used here." >&2
    exit 1
fi
JPACKAGE_VERSION="$("$JPACKAGE" --version 2>/dev/null)"
if [[ "$JPACKAGE_VERSION" != "25" && "$JPACKAGE_VERSION" != 25.* ]]; then
    echo "error: jpackage must come from JDK 25; found $JPACKAGE_VERSION at $JPACKAGE" >&2
    echo " Set JAVA_HOME to a JDK 25 installation or put its bin directory on PATH." >&2
    exit 1
fi

# Runfiles-relative inputs ('bazel run' starts in the runfiles root).
DEPLOY_JAR="app/app_deploy.jar"
ASSOCIATION="app/src/main/packaging/bviz.properties"
RESOURCE_DIRECTORY="app/src/main/packaging"
[[ -f "$DEPLOY_JAR" ]] || { echo "error: missing $DEPLOY_JAR in runfiles" >&2; exit 1; }
[[ -f "$ASSOCIATION" ]] || { echo "error: missing $ASSOCIATION in runfiles" >&2; exit 1; }
[[ -f "$RESOURCE_DIRECTORY/Info.plist" ]] || {
    echo "error: missing $RESOURCE_DIRECTORY/Info.plist in runfiles" >&2
    exit 1
}

# jpackage wants an input DIRECTORY whose contents become the app's lib dir.
STAGING="$(mktemp -d)"
trap 'rm -rf "$STAGING"' EXIT
cp "$DEPLOY_JAR" "$STAGING/bbv_deploy.jar"

OUT="$BUILD_WORKSPACE_DIRECTORY/dist/jpackage"
rm -rf "$OUT"
mkdir -p "$OUT"

COMMAND=(
    "$JPACKAGE"
    --type "$PACKAGE_TYPE"
    --name "Bazel Build Visualizer"
    --app-version "$PACKAGE_VERSION"
    --vendor "holtherndon"
    --input "$STAGING"
    --main-jar "bbv_deploy.jar"
    --main-class "com.holtherndon.bazelviz.app.Main"
    --dest "$OUT"
    --resource-dir "$RESOURCE_DIRECTORY"
    # See tools/java_test_settings.bzl for why ALL-UNNAMED is the only
    # available target:
    # everything, FlatLaf included, is on the classpath.
    --java-options "--enable-native-access=ALL-UNNAMED"
    --java-options "-Dapple.laf.useScreenMenuBar=true"
    --java-options "-Dapple.awt.application.appearance=system"
    --file-associations "$ASSOCIATION"
)

if [[ "$(uname -s)" == "Darwin" ]]; then
    # Optional native trackpad pinch events. Timeline zoom still works through
    # Control/Command-wheel and its buttons when this API is unavailable.
    COMMAND+=(--java-options "--add-exports=java.desktop/com.apple.eawt.event=ALL-UNNAMED")
    COMMAND+=(--mac-package-identifier "com.holtherndon.bazelviz")
    if [[ -n "${BBV_MAC_SIGNING_IDENTITY:-}" ]]; then
        COMMAND+=(--mac-sign --mac-signing-key-user-name "$BBV_MAC_SIGNING_IDENTITY")
        echo "jpackage: signing with the identity in BBV_MAC_SIGNING_IDENTITY" >&2
    else
        echo "jpackage: BBV_MAC_SIGNING_IDENTITY is not set, so the package is unsigned." >&2
        echo " Gatekeeper will refuse it on another machine." >&2
    fi
fi

"${COMMAND[@]}"
echo "jpackage: wrote $(ls "$OUT")" >&2
echo "jpackage: output in $OUT" >&2
