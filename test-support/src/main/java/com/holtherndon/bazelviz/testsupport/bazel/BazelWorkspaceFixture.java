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

    private static BazelWorkspaceFixture create(Path directory) throws IOException {
        Files.createDirectories(directory);
        write(directory.resolve("WORKSPACE"), "");
        write(directory.resolve("MODULE.bazel"), "module(name = \"bbv_fixture\", version = \"0.0.1\")\n");
        // Keeps a test run from inheriting the developer's ~/.bazelrc, which can
        // set --bes_backend, --config or a remote cache and turn a hermetic test
        // into a measurement of someone's laptop.
        write(directory.resolve(".bazelrc"), "");
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
