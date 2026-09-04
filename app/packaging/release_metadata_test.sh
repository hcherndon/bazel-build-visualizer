#!/bin/bash
set -euo pipefail

runfiles_root="${TEST_SRCDIR:?}/${TEST_WORKSPACE:?}"
packager="$runfiles_root/app/packaging/jpackage.sh"
plist="$runfiles_root/app/src/main/packaging/Info.plist"

fail() {
    echo "release metadata test: $*" >&2
    exit 1
}

grep -Fq 'PROJECT_VERSION="0.1.0"' "$packager" ||
    fail "jpackage product version is not 0.1.0"
grep -Fq 'MACOS_BUNDLE_VERSION="1"' "$packager" ||
    fail "macOS CFBundleVersion fallback is not 1"
grep -Fq 'JPACKAGE_VERSION="$("$JPACKAGE" --version 2>/dev/null)"' "$packager" ||
    fail "jpackage JDK version is not checked"
grep -Fq -- '--resource-dir "$RESOURCE_DIRECTORY"' "$packager" ||
    fail "custom Info.plist is not passed to jpackage"
grep -A1 '<key>CFBundleShortVersionString</key>' "$plist" |
    grep -Fq '<string>0.1.0</string>' ||
    fail "CFBundleShortVersionString is not 0.1.0"
grep -A1 '<key>CFBundleVersion</key>' "$plist" |
    grep -Fq '<string>DEPLOY_BUNDLE_CFBUNDLE_VERSION</string>' ||
    fail "CFBundleVersion is not kept separate"

if grep -q 'SNAPSHOT' "$packager" "$plist"; then
    fail "development version suffix remains in release metadata"
fi

fake_java_home="${TEST_TMPDIR:?}/jdk21"
mkdir -p "$fake_java_home/bin"
printf '#!/bin/bash\nprintf "21.0.8\\n"\n' > "$fake_java_home/bin/jpackage"
chmod +x "$fake_java_home/bin/jpackage"
if BUILD_WORKSPACE_DIRECTORY="$TEST_TMPDIR/workspace" JAVA_HOME="$fake_java_home" \
    "$packager" >"$TEST_TMPDIR/out" 2>"$TEST_TMPDIR/err"; then
    fail "jpackage accepted JDK 21"
fi
grep -Fq 'jpackage must come from JDK 25' "$TEST_TMPDIR/err" ||
    fail "wrong-JDK refusal does not explain the JDK 25 requirement"
