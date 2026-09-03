#!/bin/bash
# Builds a macOS application image or installer with jpackage (plan 24,
# Phase 9), ported from the Gradle jpackage task during the Bazel migration.
#
#   bazel run //app:jpackage                       # app-image (unsigned)
#   bazel run //app:jpackage -- --type=dmg         # dmg, for notarization
#   bazel run //app:jpackage -- --app-version=2.0.0
#
# The input is the deploy jar (//app:app_deploy.jar) rather than Gradle's
# installDist layout: a single fat jar is exactly what a desktop app that is
# never on anybody else's classpath wants, and it is the artifact Bazel
# already builds deterministically.
#
# jpackage itself comes from the LOCAL JDK (JAVA_HOME, or PATH) — the remote
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

# The version a user sees is AppInfo.VERSION (0.1.0-SNAPSHOT), shown in the
# About dialog. macOS refuses a CFBundleVersion whose first component is
# zero, and CFBundleVersion is a build-ordering number rather than the
# product's identity — so a placeholder is a platform requirement and not a
# claim about the release. It is announced when it happens, and
# --app-version=<v> overrides it.
PROJECT_VERSION="0.1.0"
PACKAGE_TYPE="app-image"
APP_VERSION=""

for arg in "$@"; do
    case "$arg" in
        --type=*) PACKAGE_TYPE="${arg#--type=}" ;;
        --app-version=*) APP_VERSION="${arg#--app-version=}" ;;
        *)
            echo "error: unknown argument '$arg' (supported: --type=<app-image|dmg|pkg>, --app-version=<v>)" >&2
            exit 1
            ;;
    esac
done

if [[ -z "$APP_VERSION" ]]; then
    if [[ "$PROJECT_VERSION" == 0.* ]]; then
        APP_VERSION="1.0.0"
        echo "jpackage: macOS will not accept an app-version starting with zero, so this" >&2
        echo " package is stamped $APP_VERSION while the application reports $PROJECT_VERSION." >&2
        echo " Pass --app-version=<v> to choose another." >&2
    else
        APP_VERSION="$PROJECT_VERSION"
    fi
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

# Runfiles-relative inputs ('bazel run' starts in the runfiles root).
DEPLOY_JAR="app/app_deploy.jar"
ASSOCIATION="app/src/main/packaging/bviz.properties"
[[ -f "$DEPLOY_JAR" ]] || { echo "error: missing $DEPLOY_JAR in runfiles" >&2; exit 1; }
[[ -f "$ASSOCIATION" ]] || { echo "error: missing $ASSOCIATION in runfiles" >&2; exit 1; }

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
    --app-version "$APP_VERSION"
    --vendor "holtherndon"
    --input "$STAGING"
    --main-jar "bbv_deploy.jar"
    --main-class "com.holtherndon.bazelviz.app.Main"
    --dest "$OUT"
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
