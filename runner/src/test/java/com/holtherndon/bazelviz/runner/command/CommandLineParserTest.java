package com.holtherndon.bazelviz.runner.command;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.runner.caps.BazelCapabilities;
import com.holtherndon.bazelviz.runner.caps.FlagSpec;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The command grammar, as measured against real Bazel binaries. */
class CommandLineParserTest {

  private static final Path BAZEL = Path.of("/usr/bin/bazel");
  private static final Path CWD = Path.of("/repo/services");

  @Test
  @DisplayName("startup options are separated from command options")
  void startupOptionsComeFirst() {
    BazelCommand command =
        new CommandLineParser()
            .parse(
                BAZEL,
                CWD,
                List.of(
                    "--output_base=/tmp/ob",
                    "--max_idle_secs=10",
                    "build",
                    "--keep_going",
                    "//..."));

    assertThat(command.startupArgs())
        .containsExactly("--output_base=/tmp/ob", "--max_idle_secs=10");
    assertThat(command.command()).isEqualTo("build");
    assertThat(command.commandArgs()).containsExactly("--keep_going");
    assertThat(command.targets()).containsExactly("//...");
  }

  @Test
  @DisplayName("flags and targets may be interleaved, because Bazel accepts them that way")
  void flagsAndTargetsInterleave() {
    BazelCommand command =
        new CommandLineParser()
            .parse(
                BAZEL,
                CWD,
                List.of("build", "//a", "--keep_going", "//b", "--verbose_failures", "//c"));

    assertThat(command.commandArgs()).containsExactly("--keep_going", "--verbose_failures");
    assertThat(command.targets()).containsExactly("//a", "//b", "//c");
    // The argv is rebuilt in Bazel's canonical order, which is equivalent:
    // an interleaved flag takes full effect wherever it appeared.
    assertThat(command.toArgv())
        .containsExactly(
            "/usr/bin/bazel", "build", "--keep_going", "--verbose_failures", "//a", "//b", "//c");
  }

  @Test
  @DisplayName("negative target patterns remain targets rather than becoming short flags")
  void negativeTargetPatternsRemainTargets() {
    BazelCommand command =
        new CommandLineParser()
            .parse(BAZEL, CWD, List.of("build", "//...", "-//generated/...", "-@repo//pkg:skip"));

    assertThat(command.commandArgs()).isEmpty();
    assertThat(command.targets()).containsExactly("//...", "-//generated/...", "-@repo//pkg:skip");
  }

  @Test
  @DisplayName("only the first -- separates; later ones are ordinary arguments")
  void onlyTheFirstSeparatorSeparates() {
    BazelCommand command =
        new CommandLineParser()
            .parse(BAZEL, CWD, List.of("run", "//:tool", "--", "--flag", "--", "value"));

    assertThat(command.targets()).containsExactly("//:tool");
    assertThat(command.argsAfterDoubleDash()).containsExactly("--flag", "--", "value");
    assertThat(command.toArgv())
        .containsExactly("/usr/bin/bazel", "run", "//:tool", "--", "--flag", "--", "value");
  }

  @Test
  @DisplayName("injected flags land before a user's -- separator, never after it")
  void injectedFlagsPrecedeTheSeparator() {
    BazelCommand original =
        new CommandLineParser().parse(BAZEL, CWD, List.of("run", "//:tool", "--", "--tool-arg"));

    BazelCommand instrumented =
        original.toBuilder().addCommandArg("--bes_backend=grpc://127.0.0.1:1234").build();

    List<String> argv = instrumented.toArgv();
    int flag = argv.indexOf("--bes_backend=grpc://127.0.0.1:1234");
    int separator = argv.indexOf("--");
    // Past the separator every token is a target pattern, so a flag there
    // is read as a negative pattern, the build dies during target
    // resolution, and no event stream is produced at all.
    assertThat(flag).isLessThan(separator);
  }

  @Test
  @DisplayName("a space-separated value is bound to its flag when the binary says it takes one")
  void separateValuesAreBoundUsingCapabilities() {
    BazelCapabilities capabilities =
        capabilitiesWith(
            new FlagSpec(
                "build_event_json_file",
                Set.of("build"),
                false,
                false,
                Optional.of(true),
                Optional.empty(),
                List.of()));

    BazelCommand command =
        new CommandLineParser(Optional.of(capabilities))
            .parse(
                BAZEL, CWD, List.of("build", "--build_event_json_file", "/tmp/out.json", "//..."));

    assertThat(command.commandArgs()).containsExactly("--build_event_json_file", "/tmp/out.json");
    assertThat(command.targets()).containsExactly("//...");
  }

  @Test
  @DisplayName("a target after a valueless flag stays a target")
  void valuelessFlagsDoNotSwallowTargets() {
    BazelCapabilities capabilities =
        capabilitiesWith(
            new FlagSpec(
                "keep_going",
                Set.of("build"),
                true,
                false,
                Optional.of(false),
                Optional.empty(),
                List.of()));

    BazelCommand command =
        new CommandLineParser(Optional.of(capabilities))
            .parse(BAZEL, CWD, List.of("build", "--keep_going", "//foo:bar"));

    assertThat(command.commandArgs()).containsExactly("--keep_going");
    assertThat(command.targets()).containsExactly("//foo:bar");
  }

  @Test
  @DisplayName("without capabilities, a target-shaped token is never taken as a flag's value")
  void unknownFlagsDoNotSwallowTargetShapedTokens() {
    BazelCommand command =
        new CommandLineParser()
            .parse(
                BAZEL,
                CWD,
                List.of("build", "--some_unknown_flag", "//foo:bar", "@repo//x", ":local", "..."));

    assertThat(command.commandArgs()).containsExactly("--some_unknown_flag");
    assertThat(command.targets()).containsExactly("//foo:bar", "@repo//x", ":local", "...");
  }

  @Test
  @DisplayName("an unknown startup option does not swallow the Bazel command")
  void unknownStartupOptionsDoNotEatTheCommand() {
    // --nohome_rc and --nosystem_rc are startup options and are filtered out
    // of the capability table, which lists only flags a command accepts. The
    // parser therefore knows nothing about them, and guessing that they take
    // a value ate the command: 'build' became a flag argument, '//...' became
    // the command, and the plan refused a valid build with a false reason.
    BazelCommand command =
        new CommandLineParser()
            .parse(BAZEL, CWD, List.of("--nohome_rc", "--nosystem_rc", "build", "//..."));

    assertThat(command.startupArgs()).containsExactly("--nohome_rc", "--nosystem_rc");
    assertThat(command.command()).isEqualTo("build");
    assertThat(command.targets()).containsExactly("//...");
  }

  @Test
  @DisplayName("a startup option whose value is known to be required still binds it")
  void knownStartupValuesAreStillBound() {
    BazelCapabilities capabilities =
        capabilitiesWith(
            new FlagSpec(
                "output_base",
                Set.of("startup"),
                false,
                false,
                Optional.of(true),
                Optional.empty(),
                List.of()));

    BazelCommand command =
        new CommandLineParser(Optional.of(capabilities))
            .parse(BAZEL, CWD, List.of("--output_base", "/tmp/ob", "build", "//..."));

    // The capability table drops startup-only flags, so this is the
    // behaviour when something else supplies the knowledge.
    assertThat(command.startupArgs()).containsExactly("--output_base", "/tmp/ob");
    assertThat(command.command()).isEqualTo("build");
  }

  @Test
  @DisplayName("a flag name is read without dashes or negation, and its attached value separately")
  void flagNamesAndValues() {
    assertThat(CommandLineParser.flagName("--bes_backend=grpc://x")).contains("bes_backend");
    assertThat(CommandLineParser.attachedValue("--bes_backend=grpc://x")).contains("grpc://x");
    assertThat(CommandLineParser.flagName("--nokeep_going")).contains("nokeep_going");
    assertThat(CommandLineParser.attachedValue("--keep_going")).isEmpty();
    assertThat(CommandLineParser.flagName("//foo")).isEmpty();
    assertThat(CommandLineParser.flagName("--")).isEmpty();
  }

  @Test
  @DisplayName("a typed command line is split on whitespace, honouring quotes")
  void tokenizing() {
    assertThat(CommandLineParser.tokenize("build --copt='-DX=1 -DY' //..."))
        .containsExactly("build", "--copt=-DX=1 -DY", "//...");
    assertThat(CommandLineParser.tokenize("   ")).isEmpty();
    assertThat(CommandLineParser.tokenize("test \"//a b:c\"")).containsExactly("test", "//a b:c");
  }

  @Test
  @DisplayName("flags with no command produce an empty command rather than a guess")
  void flagsWithoutACommand() {
    BazelCommand command = new CommandLineParser().parse(BAZEL, CWD, List.of("--output_base=/tmp"));

    assertThat(command.isEmpty()).isTrue();
    assertThat(command.startupArgs()).containsExactly("--output_base=/tmp");
  }

  private static BazelCapabilities capabilitiesWith(FlagSpec... specs) {
    Map<String, FlagSpec> byName = new LinkedHashMap<>();
    for (FlagSpec spec : specs) {
      byName.put(spec.name(), spec);
    }
    return BazelCapabilities.fromFlags(
        "bazel 9.2.0",
        Optional.of("9.2.0"),
        BazelCapabilities.DetectionMethod.FLAGS_PROTO,
        byName,
        List.of());
  }
}
