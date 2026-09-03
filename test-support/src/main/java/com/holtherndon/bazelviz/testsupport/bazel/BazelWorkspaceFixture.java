package com.holtherndon.bazelviz.testsupport.bazel;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes a small, real Bazel workspace into a directory.
 *
 * <h2>Why the workspace is generated rather than checked in</h2>
 *
 * <p>Bazel writes into its workspace — {@code bazel-out} symlinks, an output
 * base, lock files — so a checked-in workspace becomes a source tree that tests
 * mutate. Generating it per test into a temporary directory keeps each test
 * hermetic and lets it choose its own shape: a build that finishes instantly, a
 * build slow enough to cancel, a build that fails.
 *
 * <h2>Covering Bazel 6 through 9 in one directory</h2>
 *
 * <p>Both {@code MODULE.bazel} and {@code WORKSPACE} are written. Bazel 9 has
 * dropped {@code WORKSPACE} entirely and Bazel 6 does not read
 * {@code MODULE.bazel} unless asked, so a workspace with only one of them works
 * on part of the supported range. An empty {@code WORKSPACE} beside a minimal
 * {@code MODULE.bazel} is understood by every version from 6 to 9.
 *
 * <p>Only {@code genrule} is used, with {@code /bin/sh} commands. No toolchain,
 * no rules_* dependency, nothing to download: a fixture that needed the network
 * would make every test that uses it flaky for reasons unrelated to what it is
 * testing.
 */
public final class BazelWorkspaceFixture {

    private final Path root;

    private BazelWorkspaceFixture(Path root) {
        this.root = root;
    }

    /**
     * The startup options a test needs so that its result is about the code and
     * not about the developer's machine.
     *
     * <p>{@code ~/.bazelrc} and {@code /etc/bazel.bazelrc} apply to every
     * invocation, and either can set {@code --bes_backend}, a remote cache or a
     * {@code --config} that changes what the build does. Writing an empty
     * workspace rc does not suppress them — only these do. Demonstrated: with a
     * home rc containing one unrecognized flag, a fixture build fails with
     * "Unrecognized option" before it starts.
     *
     * <p>These are startup options, so they go before the Bazel command.
     */
    public static List<String> hermeticStartupOptions() {
        return List.of("--nohome_rc", "--nosystem_rc");
    }

    /** The workspace root, which is also the working directory to launch from. */
    public Path root() {
        return root;
    }

    /**
     * A workspace with {@code targetCount} genrules in a chain, each depending
     * on the one before it.
     *
     * <p>Chained rather than independent so the build has real dependency
     * structure: parallel-only targets produce an event stream with no ordering
     * to get wrong, which is not what the capture path needs to be tested
     * against.
     */
    public static BazelWorkspaceFixture simple(Path directory, int targetCount) throws IOException {
        if (targetCount < 1) {
            throw new IllegalArgumentException("targetCount must be positive, got " + targetCount);
        }
        BazelWorkspaceFixture fixture = create(directory);
        List<String> rules = new ArrayList<>();
        for (int i = 0; i < targetCount; i++) {
            rules.add(genrule("t" + i, i == 0 ? null : "t" + (i - 1), "echo " + i));
        }
        fixture.writeBuildFile(String.join("\n", rules));
        return fixture;
    }

    /**
     * A workspace whose build takes roughly {@code seconds} to run, for testing
     * cancellation.
     *
     * <p>The sleeps are chained so the elapsed time is predictable. Independent
     * sleeping targets would run in parallel and finish in the time of the
     * longest one, which makes "cancel it halfway" a race rather than a test.
     */
    public static BazelWorkspaceFixture slow(Path directory, int steps, int secondsPerStep)
            throws IOException {
        BazelWorkspaceFixture fixture = create(directory);
        List<String> rules = new ArrayList<>();
        for (int i = 0; i < steps; i++) {
            rules.add(genrule("s" + i, i == 0 ? null : "s" + (i - 1), "sleep " + secondsPerStep + " && echo " + i));
        }
        fixture.writeBuildFile(String.join("\n", rules));
        return fixture;
    }

    /**
     * A workspace with one target that fails, plus {@code okCount} that do not.
     * The failing target is named {@code //:broken}.
     */
    public static BazelWorkspaceFixture withFailure(Path directory, int okCount) throws IOException {
        BazelWorkspaceFixture fixture = create(directory);
        List<String> rules = new ArrayList<>();
        for (int i = 0; i < okCount; i++) {
            rules.add(genrule("t" + i, null, "echo " + i));
        }
        rules.add(genrule("broken", null, "echo 'this genrule fails on purpose' >&2 && exit 1"));
        fixture.writeBuildFile(String.join("\n", rules));
        return fixture;
    }

    /**
     * A wildcard build with one valid target and one deliberately invalid manual target.
     *
     * <p>{@code bazel build //...} excludes the manual rule and succeeds, while a query command's
     * wildcard includes it and fails during analysis. This pins the reason graph enrichment must
     * use the exact target labels the primary command reported instead of re-expanding its text.
     */
    public static BazelWorkspaceFixture withBrokenManualTarget(Path directory)
            throws IOException {
        BazelWorkspaceFixture fixture = create(directory);
        fixture.writeBuildFile(genrule("good", null, "echo good") + "\n" + """
                genrule(
                    name = "manual_broken",
                    srcs = [":missing_target"],
                    outs = ["manual_broken.txt"],
                    cmd = "echo broken > $@",
                    tags = ["manual"],
                )
                """);
        return fixture;
    }

    /**
     * A workspace with {@code passing} tests that succeed and {@code failing}
     * tests that do not, plus one genrule so the build has a non-test action.
     *
     * <p>Tests rather than genrules because a test target is the only thing
     * that produces {@code testResult} and {@code testSummary} events, and those
     * carry facts nothing else does: per-attempt status, the effective timeout,
     * and an {@code overallStatus} that disagrees with the target's own
     * {@code success} flag when a test fails.
     *
     * <p>The test rule is written in Starlark rather than using {@code sh_test},
     * which Bazel 9 no longer provides natively — it lives in {@code rules_shell}
     * now, and depending on that would make this fixture need the network. A
     * local rule works identically on 6.5 through 9.2.
     */
    public static BazelWorkspaceFixture withTests(Path directory, int passing, int failing)
            throws IOException {
        BazelWorkspaceFixture fixture = create(directory);
        write(fixture.root.resolve("test.bzl"), SIMPLE_TEST_RULE);
        List<String> rules = new ArrayList<>();
        rules.add("load(\":test.bzl\", \"simple_test\")\n");
        rules.add(genrule("gen", null, "echo generated"));
        for (int i = 0; i < passing; i++) {
            rules.add(simpleTest("pass" + i, "exit 0"));
        }
        for (int i = 0; i < failing; i++) {
            rules.add(simpleTest(
                    "fail" + i, "echo 'the test failed on purpose' >&2\\nexit 3"));
        }
        fixture.writeBuildFile(String.join("\n", rules));
        return fixture;
    }

    /**
     * A test rule that needs no toolchain and no external repository: it writes
     * its own shell script and declares it as the executable.
     */
    private static final String SIMPLE_TEST_RULE = """
            def _simple_test_impl(ctx):
                script = ctx.actions.declare_file(ctx.label.name + "_runner.sh")
                ctx.actions.write(
                    output = script,
                    content = "#!/bin/sh\\n" + ctx.attr.body + "\\n",
                    is_executable = True,
                )
                return [DefaultInfo(executable = script)]

            simple_test = rule(
                implementation = _simple_test_impl,
                test = True,
                attrs = {"body": attr.string(default = "exit 0")},
            )
            """;

    private static String simpleTest(String name, String body) {
        return """
                simple_test(
                    name = "%s",
                    body = "%s",
                    size = "small",
                    tags = ["fixture-tag"],
                )
                """.formatted(name, body);
    }

    /**
     * The rc every fixture workspace carries.
     *
     * <h2>Why a Bazel server's heap is the test suite's problem</h2>
     *
     * <p>Bazel sizes its server JVM from the machine's RAM, so on a large
     * developer machine one server reserves many gigabytes before it has done
     * anything. The suite runs several versions of Bazel — each version needs
     * its own server — and Gradle runs modules in parallel, so those servers
     * exist at the same time as each other and as the test JVMs. Measured: a
     * development machine taken past 120 GB of resident memory, repeatedly, to
     * the point of crashing.
     *
     * <p>These fixtures build four genrules. A gigabyte is more than enough,
     * and capping it costs the suite nothing it was using.
     *
     * <p>{@code max_idle_secs} matters as much as the cap: without it a server
     * lingers for hours after the test that started it, so the peak is every
     * server the suite ever started rather than the ones it is using.
     */
    private static final String FIXTURE_RC = """
            # See BazelWorkspaceFixture.FIXTURE_RC for why these exist.
            startup --host_jvm_args=-Xmx1g
            startup --max_idle_secs=15
            """;

    private static BazelWorkspaceFixture create(Path directory) throws IOException {
        Files.createDirectories(directory);
        write(directory.resolve("WORKSPACE"), "");
        write(directory.resolve("MODULE.bazel"), "module(name = \"bbv_fixture\", version = \"0.0.1\")\n");
        // Keeps a test run from inheriting the developer's ~/.bazelrc, which can
        // set --bes_backend, --config or a remote cache and turn a hermetic test
        // into a measurement of someone's laptop.
        // Empty, so the workspace itself contributes no options. It does NOT
        // make a run hermetic: a workspace rc does not suppress the user's
        // ~/.bazelrc or the system one, and the comment here used to claim it
        // did. Use hermeticStartupOptions() for that.
        write(directory.resolve(".bazelrc"), FIXTURE_RC);
        return new BazelWorkspaceFixture(directory);
    }

    private void writeBuildFile(String contents) throws IOException {
        write(root.resolve("BUILD.bazel"), contents + "\n");
    }

    private static String genrule(String name, String dependency, String command) {
        String sources = dependency == null ? "" : "    srcs = [\":" + dependency + "\"],\n";
        return """
                genrule(
                    name = "%s",
                %s    outs = ["%s.txt"],
                    cmd = "%s > $@",
                )
                """.formatted(name, sources, name, command);
    }

    private static void write(Path file, String contents) throws IOException {
        Files.writeString(file, contents, StandardCharsets.UTF_8);
    }
}
