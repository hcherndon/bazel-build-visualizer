"""Shared Java and test policy values.

BUILD files use rules_java and contrib_rules_jvm rules directly.  This file
contains values only; it does not wrap rules or generate targets.
"""

# JEP 472 is moving restricted native calls toward explicit grants.
# sqlite-jdbc and FlatLaf both load native code, and this repository has no
# named Java modules, making ALL-UNNAMED the narrowest usable target.
NATIVE_ACCESS_FLAG = "--enable-native-access=ALL-UNNAMED"

# Direct compile dependencies used by every JUnit 5 test library. The Jupiter
# aggregate jar is empty, so its API and parameterized-test jars remain direct
# dependencies for Bazel strict-deps checking.
JUNIT_DEPS = [
    "@maven//:org_assertj_assertj_core",
    "@maven//:org_junit_jupiter_junit_jupiter",
    "@maven//:org_junit_jupiter_junit_jupiter_api",
    "@maven//:org_junit_jupiter_junit_jupiter_params",
]

# The contrib_rules_jvm runner supplies its runner class. These are the
# project's selected JUnit engine and reporting implementation at runtime.
JUNIT_RUNTIME_DEPS = [
    "@maven//:org_junit_jupiter_junit_jupiter_engine",
    "@maven//:org_junit_platform_junit_platform_launcher",
    "@maven//:org_junit_platform_junit_platform_reporting",
]

TEST_JVM_FLAGS = [
    "-Xmx2g",
    NATIVE_ACCESS_FLAG,
]

HEADLESS_TEST_JVM_FLAGS = TEST_JVM_FLAGS + [
    "-Djava.awt.headless=true",
]

# Real-Bazel tests depend on host state Bazel cannot fingerprint. They need to
# see the host executable/cache and must neither be sandboxed nor cached.
REAL_BAZEL_ENV = [
    "PATH",
    "HOME",
    "BBV_TEST_BAZEL",
    "USE_BAZEL_VERSION",
]

REAL_BAZEL_TAGS = [
    "real-bazel",
    "no-sandbox",
    "external",
    "requires-network",
]
