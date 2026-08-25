# The build conventions, in exactly one place — the successor of ADR-003's
# build-logic convention plugins (bbv.java-common, bbv.java-library,
# bbv.java-application), per ADR-009 §4.4. A module's BUILD file stays a few
# lines; policy edits happen here.

load("@contrib_rules_jvm//java:defs.bzl", "java_junit5_test")
load("@rules_java//java:defs.bzl", "java_binary", "java_library")

# JEP 472 direction: from Java 24 onward, calling a restricted method such as
# System::load from code that has not been granted native access warns, and a
# future release makes it an error. Two runtime dependencies load native code
# this way — sqlite-jdbc (SQLiteJDBCLoader, exercised by storage-sqlite tests)
# and FlatLaf (NativeLibrary, exercised by ui-swing tests) — so every JVM this
# build forks needs the grant.
#
# ALL-UNNAMED is the only workable target here, not a shortcut: the project
# builds no module-info.java and every dependency is placed on the classpath,
# so all of it lands in the unnamed module. A narrower
# `--enable-native-access=<module>` form has no module name to name until the
# application is modularized.
#
# Single written occurrence, replacing Gradle's extra["bbvNativeAccessArg"].
# This grant is load-bearing, not cosmetic: run without it under
# `--illegal-native-access=deny` and sqlite-jdbc does not merely warn, it
# throws `SQLException: Error opening connection` and the whole storage layer
# fails. That is the behavior a future JDK makes the default.
NATIVE_ACCESS_FLAG = "--enable-native-access=ALL-UNNAMED"

# bbv.java-common parity: JUnit Jupiter + AssertJ on every suite's compile
# classpath, the Platform launcher/reporting (and the engine) at runtime.
# The jupiter aggregator jar is empty — the types live in -api and -params —
# and strict deps insist the jar a type comes from is named directly, so the
# pieces are listed rather than the aggregator alone.
_DEFAULT_TEST_DEPS = [
    "@maven//:org_assertj_assertj_core",
    "@maven//:org_junit_jupiter_junit_jupiter",
    "@maven//:org_junit_jupiter_junit_jupiter_api",
    "@maven//:org_junit_jupiter_junit_jupiter_params",
]

_DEFAULT_TEST_RUNTIME_DEPS = [
    "@maven//:org_junit_jupiter_junit_jupiter_engine",
    "@maven//:org_junit_platform_junit_platform_launcher",
    "@maven//:org_junit_platform_junit_platform_reporting",
]

# bbv.java-common parity: 2 GiB per test JVM, native access everywhere.
_TEST_JVM_FLAGS = [
    "-Xmx2g",
    NATIVE_ACCESS_FLAG,
]

def _add_missing(base, additions):
    """base plus whichever additions it does not already name."""
    return base + [extra for extra in additions if extra not in base]

def bbv_java_library(
        name,
        srcs = None,
        deps = [],
        exports = [],
        resources = None,
        visibility = ["//visibility:public"],
        **kwargs):
    """A module's main library: Maven layout, slf4j-api, shared javacopts.

    Gradle's api/implementation split maps to exports/deps — a dep that is
    also exported is `api`, a plain dep is `implementation` — and Bazel's
    strict deps enforce the split harder than Gradle could.
    """
    java_library(
        name = name,
        srcs = srcs if srcs != None else native.glob(["src/main/java/**/*.java"]),
        resources = resources if resources != None else native.glob(
            ["src/main/resources/**"],
            allow_empty = True,
        ),
        deps = _add_missing(deps, ["@maven//:org_slf4j_slf4j_api"]),
        exports = exports,
        visibility = visibility,
        **kwargs
    )

def _class_name_for(src):
    """src/test/java/com/example/FooTest.java -> com.example.FooTest."""
    path = src.removesuffix(".java")
    marker = "src/test/java/"
    idx = path.find(marker)
    if idx == -1:
        fail("test source %s is not under src/test/java" % src)
    return path[idx + len(marker):].replace("/", ".")

def bbv_java_test_suite(
        name,
        srcs = None,
        compile_srcs = None,
        exclude = [],
        deps = [],
        runtime_deps = [],
        resources = None,
        jvm_flags = [],
        headless = False,
        data = [],
        size = None,
        timeout_overrides = {},
        **kwargs):
    """A module's test suite: one java_test per class, JUnit Platform runner.

    All test sources compile once into a shared testonly library (test
    classes in this codebase legitimately use each other's helpers, e.g.
    BfsTest builds graphs with CsrGraphTest.stream, so per-class compilation
    would not build); each *Test class then runs as its own java_test over
    that library. Invalidation is Gradle-equivalent — a test edit reruns the
    module's classes — while execution, reporting and flake isolation stay
    per class, in parallel.

    `headless = True` pins -Djava.awt.headless=true on every test JVM in the
    suite (app and ui-swing). As a JVM flag it is set before any code can
    touch GraphicsEnvironment, which retires the test-ordering hazard the
    Gradle build files documented.

    `exclude` removes sources from the suite — the hazard-tagged real-Bazel
    classes, which get dedicated bbv_real_bazel_test targets instead.

    `compile_srcs` substitutes different labels for the library compilation
    while `srcs` still names the per-class test structure — the bazel-runner
    module needs it because its sources must be relocated out of a
    "bazel-"-prefixed directory before any action can read them (see
    tools/relocate.bzl).
    """
    all_srcs = srcs if srcs != None else native.glob(
        ["src/test/java/**/*.java"],
        exclude = exclude,
    )
    lib_name = name + "-lib"
    java_library(
        name = lib_name,
        testonly = True,
        srcs = compile_srcs if compile_srcs != None else all_srcs,
        resources = resources if resources != None else native.glob(
            ["src/test/resources/**"],
            allow_empty = True,
        ),
        deps = _add_missing(deps, _DEFAULT_TEST_DEPS),
        visibility = ["//visibility:private"],
    )

    flags = _TEST_JVM_FLAGS + \
            (["-Djava.awt.headless=true"] if headless else []) + \
            jvm_flags

    tests = []
    for src in all_srcs:
        if not src.endswith("Test.java"):
            continue
        clazz = _class_name_for(src)
        test_name = clazz.rpartition(".")[2]
        if native.existing_rule(test_name):
            fail("duplicate test class simple name %s in //%s" %
                 (test_name, native.package_name()))
        java_junit5_test(
            name = test_name,
            size = size,
            # Gradle test tasks had no per-test time limit; Bazel's default
            # (moderate) one is right for almost every class here, and the
            # exceptions are named individually rather than raising the whole
            # suite's ceiling.
            timeout = timeout_overrides.get(test_name),
            data = data,
            jvm_flags = flags,
            runtime_deps = _add_missing(
                [":" + lib_name] + runtime_deps,
                _DEFAULT_TEST_RUNTIME_DEPS,
            ),
            test_class = clazz,
            **kwargs
        )
        tests.append(":" + test_name)

    native.test_suite(
        name = name,
        tests = tests,
    )

def bbv_real_bazel_test(
        name,
        test_class,
        srcs,
        deps = [],
        runtime_deps = [],
        jvm_flags = [],
        tags = None,
        **kwargs):
    """A dedicated target for one test class that drives a real host Bazel.

    These tests find a Bazel via BazelBinary.find() (BBV_TEST_BAZEL, a dev
    bazelisk, PATH) and spawn child Bazel servers that are designed to escape
    the process tree, so (ADR-009 §4.3):

    - `no-sandbox` lets the child server and bazelisk's download cache live;
    - `env_inherit` lets discovery see the host (on a bare machine the test
      still skips with BazelBinary.whyUnavailable()'s message — skipped,
      never silently weakened);
    - `external` disables result caching, which is correct because the result
      depends on host state Bazel cannot fingerprint;
    - the child servers are capped by the fixture rc that
      BazelWorkspaceFixture writes (-Xmx1g, 15s idle) — the reason this suite
      can exist at all on a machine that has been crashed at 120 GB by
      uncapped servers.

    The four-version sweep target overrides `tags` with
    ["bazel-sweep", "no-sandbox", "external", "manual"]: `manual` keeps it
    out of every //... wildcard and the rc's default test_tag_filters is the
    second fence. Do not run it casually.
    """
    java_junit5_test(
        name = name,
        size = "large",
        srcs = srcs,
        test_class = test_class,
        deps = _add_missing(deps, _DEFAULT_TEST_DEPS),
        env_inherit = [
            "PATH",
            "HOME",
            "BBV_TEST_BAZEL",
            "USE_BAZEL_VERSION",
        ],
        jvm_flags = _TEST_JVM_FLAGS + jvm_flags,
        runtime_deps = _add_missing(runtime_deps, _DEFAULT_TEST_RUNTIME_DEPS),
        tags = tags if tags != None else [
            "real-bazel",
            "no-sandbox",
            "external",
            "requires-network",
        ],
        **kwargs
    )

def bbv_java_binary(
        name,
        main_class,
        jvm_flags = [],
        **kwargs):
    """A runnable JVM: native access always granted, flags stated once."""
    java_binary(
        name = name,
        main_class = main_class,
        jvm_flags = [NATIVE_ACCESS_FLAG] + jvm_flags,
        **kwargs
    )
