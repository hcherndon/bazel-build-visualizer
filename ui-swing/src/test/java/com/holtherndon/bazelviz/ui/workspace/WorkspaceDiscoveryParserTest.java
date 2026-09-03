package com.holtherndon.bazelviz.ui.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

final class WorkspaceDiscoveryParserTest {

  @Test
  void parsesLocalAndSshRowsWhileAllowingBlankAndCommentLines() {
    WorkspaceDiscoveryParser.ParseResult result =
        WorkspaceDiscoveryParser.parse(
            """

              # generated on this computer
            local | Local checkout | /code/repository
            ssh|Linux checkout|builder-prod|/srv/repository
            """);

    assertThat(result.diagnostics()).isEmpty();
    assertThat(result.workspaces()).hasSize(2);
    WorkspaceProfile local = result.workspaces().get(0);
    assertThat(local.label()).isEqualTo("Local checkout");
    assertThat(local.kind()).isEqualTo(WorkspaceProfile.Kind.LOCAL);
    assertThat(local.destination()).isEmpty();
    assertThat(local.workingDirectory()).isEqualTo("/code/repository");
    assertThat(local.bazelExecutable()).isEqualTo("bazel");
    assertThat(local.lastOpenedMicros()).isEmpty();

    WorkspaceProfile ssh = result.workspaces().get(1);
    assertThat(ssh.label()).isEqualTo("Linux checkout");
    assertThat(ssh.kind()).isEqualTo(WorkspaceProfile.Kind.SSH);
    assertThat(ssh.destination()).contains("builder-prod");
    assertThat(ssh.port()).isEqualTo(OptionalInt.empty());
    assertThat(ssh.workingDirectory()).isEqualTo("/srv/repository");
    assertThat(ssh.bazelExecutable()).isEqualTo("bazel");
  }

  @Test
  void identifiersAreDeterministicAndDoNotDependOnTheDisplayName() {
    WorkspaceDiscoveryParser.ParseResult first =
        WorkspaceDiscoveryParser.parse("ssh|First name|builder-prod|/srv/repository\n");
    WorkspaceDiscoveryParser.ParseResult renamed =
        WorkspaceDiscoveryParser.parse("ssh|Renamed workspace|builder-prod|/srv/repository\n");
    WorkspaceDiscoveryParser.ParseResult otherRepository =
        WorkspaceDiscoveryParser.parse("ssh|First name|builder-prod|/srv/other\n");

    assertThat(first.workspaces().getFirst().id())
        .isEqualTo(renamed.workspaces().getFirst().id())
        .isNotEqualTo(otherRepository.workspaces().getFirst().id());
    assertThat(first.workspaces().getFirst().id())
        .matches("[0-9a-f]{8}-[0-9a-f]{4}-3[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
  }

  @Test
  void invalidAndDuplicateRowsHaveLineDiagnosticsWithoutEchoingValues() {
    WorkspaceDiscoveryParser.ParseResult result =
        WorkspaceDiscoveryParser.parse(
            """
            local|Good|/code/repository
            local|Renamed duplicate|/code/repository
            local|too-few
            ssh|too|many|fields|here
            cloud|Unknown|/somewhere
            ssh|Credential|builder:do-not-repeat@host|/srv/repository
            """);

    assertThat(result.workspaces()).hasSize(1);
    assertThat(result.diagnostics())
        .anySatisfy(message -> assertThat(message).contains("Line 2", "duplicates", "line 1"))
        .anySatisfy(message -> assertThat(message).contains("Line 3", "exactly 3"))
        .anySatisfy(message -> assertThat(message).contains("Line 4", "exactly 4"))
        .anySatisfy(message -> assertThat(message).contains("Line 5", "'local' or 'ssh'"))
        .anySatisfy(message -> assertThat(message).contains("Line 6", "not permitted"));
    assertThat(result.diagnostics())
        .allSatisfy(
            message ->
                assertThat(message)
                    .doesNotContain("do-not-repeat")
                    .doesNotContain("Renamed duplicate"));
  }

  @Test
  void acceptedRowsAreBoundedAndEveryExtraRowIsDiagnosed() {
    StringBuilder output = new StringBuilder();
    for (int index = 0; index < WorkspaceDiscovery.MAX_ACCEPTED_ROWS + 2; index++) {
      output.append("local|Repository ").append(index).append("|/code/").append(index).append('\n');
    }

    WorkspaceDiscoveryParser.ParseResult result = WorkspaceDiscoveryParser.parse(output.toString());

    assertThat(result.workspaces()).hasSize(WorkspaceDiscovery.MAX_ACCEPTED_ROWS);
    assertThat(result.diagnostics())
        .anySatisfy(message -> assertThat(message).contains("Line 101", "100", "was not accepted"))
        .anySatisfy(message -> assertThat(message).contains("Line 102", "100", "was not accepted"));
  }

  @Test
  void rowDiagnosticsHaveAnExactVisibleBound() {
    String output = "unknown\n".repeat(WorkspaceDiscovery.MAX_ROW_DIAGNOSTICS + 6);

    WorkspaceDiscoveryParser.ParseResult result = WorkspaceDiscoveryParser.parse(output);

    assertThat(result.workspaces()).isEmpty();
    assertThat(result.diagnostics()).hasSize(WorkspaceDiscovery.MAX_ROW_DIAGNOSTICS);
    assertThat(result.diagnostics().getLast())
        .contains("7 additional row diagnostics", "omitted", "1000");
  }

  @Test
  void everyParseReturnsFreshImmutableLists() {
    WorkspaceDiscoveryParser.ParseResult first = WorkspaceDiscoveryParser.parse("");
    WorkspaceDiscoveryParser.ParseResult second = WorkspaceDiscoveryParser.parse("");

    assertThat(first.workspaces()).isNotSameAs(second.workspaces());
    assertThat(first.diagnostics()).isNotSameAs(second.diagnostics());
    assertThatThrownBy(
            () -> first.workspaces().add(WorkspaceProfile.local("Ignored", "/ignored", "bazel")))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> first.diagnostics().add("ignored"))
        .isInstanceOf(UnsupportedOperationException.class);
  }
}
