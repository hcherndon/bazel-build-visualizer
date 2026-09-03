package com.holtherndon.bazelviz.runner.plan;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.runner.caps.BazelCapabilities;
import com.holtherndon.bazelviz.runner.caps.FlagSpec;
import com.holtherndon.bazelviz.runner.command.BazelCommand;
import com.holtherndon.bazelviz.runner.command.CommandLineParser;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What an auxiliary query carries from the build, and what it admits losing.
 *
 * <p>Plan 8.6's rules, each with the failure it prevents.
 */
final class AuxiliaryQueryPlannerTest {

  private static final Path BAZEL = Path.of("/usr/local/bin/bazel");
  private static final Path CWD = Path.of("/work");
  private static final Path AQUERY_OUT = Path.of("/session/raw/aquery.proto");

  @Test
  @DisplayName("configuration-affecting options are carried into the query")
  void configurationOptionsSurvive() {
    AuxiliaryQueryPlanner.Plan plan =
        plan("build", "--compilation_mode=opt", "--define=x=1", "//pkg:all");

    // Drop one of these and the query answers about a build nobody ran.
    assertThat(plan.argv()).contains("--compilation_mode=opt", "--define=x=1");
    assertThat(plan.reproducesOptions()).isTrue();
    assertThat(plan.mismatchWarning()).isEmpty();
  }

  @Test
  @DisplayName("the executable, startup options and workspace are reused")
  void contextIsReused() {
    BazelCommand original = parse(List.of("--output_base=/tmp/ob"), "build", "//pkg:a", "//pkg:b");
    AuxiliaryQueryPlanner.Plan plan =
        new AuxiliaryQueryPlanner(capabilities()).aquery(original, AQUERY_OUT);

    assertThat(plan.command().executable()).isEqualTo(BAZEL);
    // Startup options choose the output base; a query against a different
    // one re-analyses from scratch.
    assertThat(plan.command().startupArgs()).contains("--output_base=/tmp/ob");
    assertThat(plan.command().workingDirectory()).isEqualTo(CWD);
    assertThat(plan.command().targets()).isEmpty();
    assertThat(plan.argv())
        .contains("--query_file=/session/raw/" + AuxiliaryQueryPlanner.AQUERY_EXPRESSION_FILE);
    assertThat(plan.command().command()).isEqualTo("aquery");
  }

  @Test
  @DisplayName("instrumentation options are left behind without being called a loss")
  void instrumentationIsNotADroppedOption() {
    AuxiliaryQueryPlanner.Plan plan =
        plan(
            "build",
            "--bes_backend=grpc://127.0.0.1:1",
            "--profile=/tmp/p.json",
            "--starlark_cpu_profile=/tmp/starlark.pprof.gz",
            "--build_event_publish_all_actions",
            "//pkg:all");

    assertThat(plan.argv()).noneMatch(argument -> argument.startsWith("--bes_backend"));
    assertThat(plan.argv()).noneMatch(argument -> argument.startsWith("--profile"));
    assertThat(plan.argv()).noneMatch(argument -> argument.startsWith("--starlark_cpu_profile"));
    // They ask Bazel to do work a query does not do, so their absence
    // changes no graph and must not be reported as a reason for mismatch.
    assertThat(plan.droppedOptions()).isEmpty();
    assertThat(plan.reproducesOptions()).isTrue();
  }

  @Test
  @DisplayName("an option the query rejects is dropped and named")
  void rejectedOptionsAreReported() {
    // --test_lang_filters is a build/test option this fixture says aquery
    // does not accept.
    AuxiliaryQueryPlanner.Plan plan = plan("build", "--test_lang_filters=cc", "//pkg:all");

    assertThat(plan.argv()).noneMatch(argument -> argument.startsWith("--test_lang_filters"));
    assertThat(plan.droppedOptions()).containsExactly("--test_lang_filters=cc");
    assertThat(plan.reproducesOptions()).isFalse();
    // Plan 8.6 step 10: explain when the graph may not match.
    assertThat(plan.mismatchWarning())
        .hasValueSatisfying(
            warning -> assertThat(warning).contains("may describe a different configuration"));
  }

  @Test
  @DisplayName("an option this build has never heard of is kept, not silently dropped")
  void unknownOptionsAreKept() {
    // Starlark flags appear nowhere in `bazel help`, and they change the
    // graph. Dropping one because the capability table has not heard of it
    // would answer about a different build, silently; keeping it makes a
    // genuinely bad option fail the query loudly instead.
    AuxiliaryQueryPlanner.Plan plan = plan("build", "--//my:flag=on", "//pkg:all");

    assertThat(plan.argv()).contains("--//my:flag=on");
    assertThat(plan.droppedOptions()).isEmpty();
  }

  @Test
  @DisplayName("the output format is always proto")
  void outputIsProto() {
    assertThat(plan("build", "//pkg:all").argv()).contains("--output=proto");
    AuxiliaryQueryPlanner.Plan cquery =
        new AuxiliaryQueryPlanner(capabilities())
            .cquery(parse(List.of(), "build", "//pkg:all"), AQUERY_OUT);
    assertThat(cquery.argv()).contains("--output=proto");
    assertThat(cquery.command().command()).isEqualTo("cquery");
  }

  @Test
  @DisplayName("both graph queries name their recorded-target files")
  void graphQueriesUseTheDependencyClosure() {
    BazelCommand original = parse(List.of(), "build", "//pkg:a", "//other:b");
    AuxiliaryQueryPlanner planner = new AuxiliaryQueryPlanner(capabilities());

    AuxiliaryQueryPlanner.Plan aquery = planner.aquery(original, AQUERY_OUT);
    assertThat(aquery.command().targets()).isEmpty();
    assertThat(aquery.argv())
        .contains("--query_file=/session/raw/" + AuxiliaryQueryPlanner.AQUERY_EXPRESSION_FILE);
    assertThat(aquery.note())
        .get()
        .asString()
        .contains("exact top-level target labels", "transitive dependency closure");
    AuxiliaryQueryPlanner.Plan cquery = planner.cquery(original, AQUERY_OUT);
    assertThat(cquery.command().targets()).isEmpty();
    assertThat(cquery.argv())
        .contains("--query_file=/session/raw/" + AuxiliaryQueryPlanner.CQUERY_EXPRESSION_FILE);
    assertThat(cquery.note())
        .get()
        .asString()
        .contains("exact top-level target labels", "transitive dependency closure");
  }

  @Test
  @DisplayName("carried options are recorded separately from cquery's output option")
  void carriedOptionsAreRecorded() {
    AuxiliaryQueryPlanner.Plan plan =
        new AuxiliaryQueryPlanner(capabilities())
            .cquery(parse(List.of(), "build", "--compilation_mode=opt", "//pkg:a"), AQUERY_OUT);

    assertThat(plan.carriedOptions()).containsExactly("--compilation_mode=opt");
    assertThat(plan.command().targets()).isEmpty();
  }

  @Test
  @DisplayName("negative build patterns remain exclusions in the dependency query")
  void targetExclusionsArePreserved() {
    assertThat(
            AuxiliaryQueryPlanner.dependencyClosure(
                List.of("//...", "-//generated/...", "-@repo//pkg:skip")))
        .isEqualTo("deps(//... except set(//generated/... @repo//pkg:skip))");
  }

  @Test
  @DisplayName("a build with no targets records that its query-file fallback may be wider")
  void noTargetsWidensTheScope() {
    AuxiliaryQueryPlanner.Plan plan = plan("build");

    assertThat(plan.command().targets()).isEmpty();
    assertThat(plan.argv())
        .contains("--query_file=/session/raw/" + AuxiliaryQueryPlanner.AQUERY_EXPRESSION_FILE);
    assertThat(plan.note())
        .hasValueSatisfying(
            note -> assertThat(note).contains("falls back", "requested target patterns"));
  }

  @Test
  @DisplayName("a query is never run through a shell")
  void neverShellMode() {
    // The argv is built here and nothing is interpolated into a command
    // string (plan 22.2), so an option value containing a semicolon is an
    // argument and not a second command.
    AuxiliaryQueryPlanner.Plan plan = plan("build", "--define=x=a;rm -rf /", "//pkg:all");

    assertThat(plan.command().shellMode()).isFalse();
    assertThat(plan.argv()).contains("--define=x=a;rm -rf /");
  }

  @Test
  @DisplayName("both queries are planned from one command line")
  void bothQueriesFromOneCommand() {
    List<AuxiliaryQueryPlanner.Plan> plans =
        AuxiliaryQueryPlanner.forCommandLine(
            capabilities(),
            BAZEL,
            CWD,
            List.of("build", "//pkg:all"),
            AQUERY_OUT,
            Path.of("/session/raw/cquery.proto"));

    assertThat(plans).hasSize(2);
    assertThat(plans).extracting(p -> p.command().command()).containsExactly("aquery", "cquery");
  }

  // ---------------------------------------------------------------- helpers

  private static AuxiliaryQueryPlanner.Plan plan(String... argv) {
    return new AuxiliaryQueryPlanner(capabilities()).aquery(parse(List.of(), argv), AQUERY_OUT);
  }

  private static BazelCommand parse(List<String> startup, String... rest) {
    List<String> argv = new ArrayList<>(startup);
    argv.addAll(List.of(rest));
    return new CommandLineParser(Optional.of(capabilities())).parse(BAZEL, CWD, argv);
  }

  private static BazelCapabilities capabilities() {
    Map<String, FlagSpec> flags = new LinkedHashMap<>();
    put(flags, "compilation_mode", "build", "test", "aquery", "cquery");
    put(flags, "define", "build", "test", "aquery", "cquery");
    put(flags, "platforms", "build", "test", "aquery", "cquery");
    put(flags, "output", "aquery", "cquery");
    put(flags, "bes_backend", "build", "test", "run");
    put(flags, "profile", "build", "test");
    put(flags, "starlark_cpu_profile", "build", "test");
    put(flags, "build_event_publish_all_actions", "build", "test", "run");
    // Accepted by build and test, and not by the queries.
    put(flags, "test_lang_filters", "build", "test");
    return BazelCapabilities.fromFlags(
        "bazel 9.2.0",
        Optional.of("9.2.0"),
        BazelCapabilities.DetectionMethod.HELP_TEXT,
        flags,
        List.of());
  }

  private static void put(Map<String, FlagSpec> flags, String name, String... commands) {
    flags.put(
        name,
        new FlagSpec(
            name, Set.of(commands), true, false, Optional.of(true), Optional.empty(), List.of()));
  }
}
