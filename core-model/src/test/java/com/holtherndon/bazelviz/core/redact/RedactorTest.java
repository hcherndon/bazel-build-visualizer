package com.holtherndon.bazelviz.core.redact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The redaction rules, and the four ways redaction quietly fails: missing the second shape of a
 * flag, destroying the information the export exists for, putting the secret into the report that
 * exists to keep it out, and claiming more than pattern matching can deliver.
 *
 * <p>Plan 24's Phase 9 exit criterion is "redaction tests pass", which is the only exit criterion
 * in the plan that names its own test suite. This is it.
 */
final class RedactorTest {

  private static final byte[] KEY = "a fixed key for tests".getBytes(StandardCharsets.UTF_8);

  private static Redactor exporting() {
    return new Redactor(RedactionPolicy.forExport(), KEY);
  }

  // --- secrets by name ---------------------------------------------------

  @ParameterizedTest
  @ValueSource(
      strings = {
        "GITHUB_TOKEN", "github_token", "AWS_SECRET_ACCESS_KEY", "MY_PASSWORD",
        "SERVICE_CREDENTIAL", "DEPLOY_APIKEY", "SIGNING_KEY", "AUTHORIZATION",
        "CI_PASSPHRASE", "some_api_key",
      })
  @DisplayName("the shipped name patterns cover the four families the plan names")
  void namePatternsCoverTheFamilies(String name) {
    Redactor redactor = exporting();

    String redacted = redactor.environmentValue(name, "hunter2", "env");

    assertThat(redacted).doesNotContain("hunter2").startsWith("[redacted:");
  }

  @ParameterizedTest
  @ValueSource(strings = {"PATH", "HOME", "BAZEL_VERSION", "TMPDIR", "keyboard_layout"})
  @DisplayName("ordinary environment names are left alone")
  void ordinaryNamesSurvive(String name) {
    assertThat(exporting().environmentValue(name, "/usr/bin", "env")).isEqualTo("/usr/bin");
  }

  @Test
  @DisplayName("a flag carrying its value with = has only the value redacted")
  void flagWithInlineValue() {
    String redacted =
        exporting()
            .argument("--remote_header=Authorization: Bearer abcdef0123456789", "command_line");

    assertThat(redacted).startsWith("--remote_header=").doesNotContain("abcdef0123456789");
  }

  @Test
  @DisplayName("a flag carrying its value as the next argument is redacted too")
  void flagWithSeparateValue() {
    // Bazel accepts both shapes. An export that redacted only the first
    // would leak the second, and the second is what a shell history
    // usually produces.
    List<String> redacted =
        exporting()
            .argv(
                List.of(
                    "bazel",
                    "build",
                    "--remote_header",
                    "Authorization: Bearer sekrit123",
                    "//pkg:target"),
                "command_line");

    assertThat(redacted).hasSize(5);
    assertThat(redacted.get(2)).isEqualTo("--remote_header");
    assertThat(redacted.get(3)).doesNotContain("sekrit123").startsWith("[redacted:");
    // And the target survives: an export whose command shows nothing is
    // not an export of a command.
    assertThat(redacted.get(4)).isEqualTo("//pkg:target");
    assertThat(redacted.getFirst()).isEqualTo("bazel");
  }

  // --- secrets by shape --------------------------------------------------

  @Test
  @DisplayName("a bearer token is redacted wherever it appears, and only the token")
  void bearerTokens() {
    String redacted =
        exporting()
            .text(
                "server replied 401 for header Bearer eyJhbGciOiJIUzI1NiJ9abcdef",
                "failure_message");

    assertThat(redacted)
        .doesNotContain("eyJhbGciOiJIUzI1NiJ9abcdef")
        .contains("server replied 401 for header Bearer [redacted:");
  }

  @Test
  @DisplayName("a URL password is redacted and the host survives")
  void urlCredentials() {
    String redacted =
        exporting()
            .argument(
                "--remote_cache=https://ci-user:s3cr3tpassword@cache.example.com/v1", "options");

    assertThat(redacted)
        .doesNotContain("s3cr3tpassword")
        .contains("ci-user")
        .contains("cache.example.com");
  }

  @Test
  @DisplayName("a PEM private key is redacted whole")
  void pemKeys() {
    String redacted =
        exporting()
            .text(
                "key was -----BEGIN RSA PRIVATE KEY-----\nMIIEow\n-----END RSA PRIVATE KEY-----",
                "failure_message");

    assertThat(redacted).doesNotContain("MIIEow").contains("BEGIN RSA PRIVATE KEY");
  }

  @Test
  @DisplayName("digests and action keys are not mistaken for secrets")
  void buildIdentifiersSurvive() {
    // The reason the value rules are three and not thirty: a rule broad
    // enough to catch "long random-looking string" would redact most of
    // what makes an export useful.
    Redactor redactor = exporting();

    assertThat(
            redactor.argument(
                "sha256:9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
                "digest"))
        .isEqualTo("sha256:9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08");
    assertThat(redactor.argument("k8-fastbuild-ST-2a1b3c4d5e6f", "configuration"))
        .isEqualTo("k8-fastbuild-ST-2a1b3c4d5e6f");
  }

  // --- pseudonyms --------------------------------------------------------

  @Test
  @DisplayName("the same secret gets the same pseudonym, and a different one a different name")
  void pseudonymsAreStableWithinOneExport() {
    Redactor redactor = exporting();

    String first = redactor.environmentValue("GITHUB_TOKEN", "ghp_aaa", "env");
    String again = redactor.environmentValue("OTHER_TOKEN", "ghp_aaa", "env");
    String different = redactor.environmentValue("GITHUB_TOKEN", "ghp_bbb", "env");

    // The point of a pseudonym rather than asterisks: a reader can see that
    // one credential is in use everywhere without seeing the credential.
    assertThat(again).isEqualTo(first);
    assertThat(different).isNotEqualTo(first);
    assertThat(redactor.report().distinctSecrets()).isEqualTo(2);
    assertThat(redactor.report().redactions()).isEqualTo(3);
  }

  @Test
  @DisplayName("two exports of the same secret produce different pseudonyms")
  void pseudonymsDoNotLinkAcrossExports() {
    // The key is per-redactor and never written. A bare digest of a
    // low-entropy secret is a password hash, and a password hash in a file
    // being sent to a colleague is the secret with one extra step.
    String one =
        new Redactor(RedactionPolicy.forExport())
            .environmentValue("GITHUB_TOKEN", "ghp_aaa", "env");
    String two =
        new Redactor(RedactionPolicy.forExport())
            .environmentValue("GITHUB_TOKEN", "ghp_aaa", "env");

    assertThat(one).isNotEqualTo(two);
  }

  @Test
  @DisplayName("forced pseudonyms stay stable within one export without relying on token shape")
  void forcedPseudonymsAreExportScoped() {
    Redactor redactor = exporting();

    String first = redactor.pseudonymize("builder@internal", "ssh.display", "an SSH identity");
    String again =
        redactor.pseudonymize("builder@internal", "ssh.destination", "an SSH destination");

    assertThat(first).startsWith("[redacted:").isEqualTo(again);
    assertThat(redactor.report().byField()).containsKeys("ssh.display", "ssh.destination");
  }

  // --- paths -------------------------------------------------------------

  @Test
  @DisplayName("a mapped prefix is replaced and the informative remainder survives")
  void pathPrefixesAreMapped() {
    Redactor redactor =
        new Redactor(
            RedactionPolicy.forExport()
                .withPathPrefix("/Users/someone/code/project", "[workspace]")
                .withPathPrefix("/private/var/tmp/_bazel_someone/abc123", "[output-base]"),
            KEY);

    assertThat(
            redactor.path("/Users/someone/code/project/bazel-out/darwin-fastbuild/bin/a.o", "path"))
        .isEqualTo("[workspace]/bazel-out/darwin-fastbuild/bin/a.o");
    assertThat(redactor.path("/private/var/tmp/_bazel_someone/abc123/execroot/x", "path"))
        .isEqualTo("[output-base]/execroot/x");
  }

  @Test
  @DisplayName("the longest matching prefix wins")
  void longestPrefixWins() {
    Redactor redactor =
        new Redactor(
            RedactionPolicy.forExport()
                .withPathPrefix("/Users/someone", "[home]")
                .withPathPrefix("/Users/someone/code/project", "[workspace]"),
            KEY);

    // A workspace under a home directory must map as the workspace, or the
    // export loses the distinction between the two.
    assertThat(redactor.path("/Users/someone/code/project/BUILD", "path"))
        .isEqualTo("[workspace]/BUILD");
  }

  @Test
  @DisplayName("an unmapped home path has its account name masked")
  void unmappedHomePathsLoseTheAccountName() {
    Redactor redactor = exporting();

    assertThat(redactor.path("/Users/someone/Downloads/thing.bep", "path"))
        .isEqualTo("/Users/[user]/Downloads/thing.bep");
    assertThat(redactor.path("/home/someone/build.log", "path"))
        .isEqualTo("/home/[user]/build.log");
  }

  @Test
  @DisplayName("a relative path is left exactly as it is")
  void relativePathsSurvive() {
    assertThat(exporting().path("bazel-out/darwin-fastbuild/bin/a.o", "path"))
        .isEqualTo("bazel-out/darwin-fastbuild/bin/a.o");
  }

  @Test
  @DisplayName("credentials embedded in a path-like URL are removed before path mapping")
  void pathUrlsLoseCredentials() {
    String redacted =
        exporting().path("https://ci-user:verysecretpassword@cache.example/build", "source.path");

    assertThat(redacted)
        .contains("ci-user")
        .contains("cache.example")
        .doesNotContain("verysecretpassword");
  }

  @Test
  @DisplayName("paths inside a failure message are mapped too")
  void pathsInsideMessages() {
    // Bazel's own failure text embeds whole command lines and sandbox
    // paths, so treating a message as opaque would leak what the path
    // mapping exists to catch.
    Redactor redactor =
        new Redactor(
            RedactionPolicy.forExport().withPathPrefix("/Users/someone/code/p", "[workspace]"),
            KEY);

    assertThat(redactor.text("cc failed: /Users/someone/code/p/src/a.cc:12: undefined", "failure"))
        .isEqualTo("cc failed: [workspace]/src/a.cc:12: undefined");
  }

  @Test
  @DisplayName("the display policy leaves paths alone")
  void displayPolicyKeepsPaths() {
    // The person at the keyboard already has the session on their disk, and
    // a path they cannot paste into a terminal is worse at the job the view
    // exists for.
    Redactor redactor = new Redactor(RedactionPolicy.forDisplay(), KEY);

    assertThat(redactor.path("/Users/someone/code/x", "path")).isEqualTo("/Users/someone/code/x");
    assertThat(redactor.environmentValue("GITHUB_TOKEN", "ghp_aaa", "env"))
        .startsWith("[redacted:");
  }

  // --- options -----------------------------------------------------------

  @Test
  @DisplayName("omitting environment values keeps every name")
  void environmentValuesCanBeOmittedEntirely() {
    Redactor redactor = new Redactor(RedactionPolicy.forExport().omittingEnvironmentValues(), KEY);

    assertThat(redactor.environmentValue("PATH", "/usr/bin:/bin", "env")).isEqualTo("[omitted]");
    assertThat(redactor.report().byRule()).containsKey("omit-environment-values");
  }

  @Test
  @DisplayName("labels survive by default and are pseudonymised only on request")
  void labelsAreOptOut() {
    assertThat(exporting().label("//src/main/java:lib", "label")).isEqualTo("//src/main/java:lib");

    Redactor hiding = new Redactor(RedactionPolicy.forExport().redactingLabels(), KEY);
    assertThat(hiding.label("//src/main/java:lib", "label")).startsWith("[redacted:");
    assertThat(RedactionPolicy.forExport().redactingLabels().describe())
        .contains("much harder to read");
  }

  @Test
  @DisplayName("a user pattern is added without replacing the shipped ones")
  void userPatternsExtend() {
    Redactor redactor =
        new Redactor(RedactionPolicy.forExport().withUserPatterns(List.of("INTERNAL_*")), KEY);

    assertThat(redactor.environmentValue("INTERNAL_ENDPOINT", "https://x", "env"))
        .startsWith("[redacted:");
    assertThat(redactor.environmentValue("GITHUB_TOKEN", "ghp", "env")).startsWith("[redacted:");
  }

  @Test
  @DisplayName("a user pattern is a glob, so its punctuation is literal")
  void userPatternsAreNotRegexes() {
    // A user-supplied regular expression run once per argument over five
    // million actions is a denial-of-service risk, so the pattern language
    // is a glob and every other character is quoted.
    Redactor redactor =
        new Redactor(RedactionPolicy.forExport().withUserPatterns(List.of("A.B")), KEY);

    assertThat(redactor.environmentValue("A.B", "x", "env")).startsWith("[redacted:");
    assertThat(redactor.environmentValue("AxB", "x", "env")).isEqualTo("x");
  }

  @Test
  @DisplayName("a blank pattern is a configuration error, not a pattern matching everything")
  void blankPatternsAreRefused() {
    assertThatThrownBy(() -> SecretPattern.named("  ", "blank"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // --- the report --------------------------------------------------------

  @Test
  @DisplayName("the report counts by rule and by field, and never quotes the secret")
  void theReportSaysWhatHappenedWithoutSayingWhat() {
    Redactor redactor = exporting();
    redactor.environmentValue("GITHUB_TOKEN", "ghp_aaa", "attempt_env_vars.value");
    redactor.argument("--remote_header=Bearer ghp_bbb", "actions.command_line");
    redactor.path("/Users/someone/x", "artifacts.path");

    String report = redactor.report().toString();
    assertThat(report)
        .doesNotContain("ghp_aaa")
        .doesNotContain("ghp_bbb")
        .doesNotContain("someone")
        .contains("attempt_env_vars.value")
        .contains("actions.command_line")
        .contains("redactions across");
    assertThat(redactor.report().byField())
        .containsKeys("attempt_env_vars.value", "actions.command_line", "artifacts.path");
  }

  @Test
  @DisplayName("finding nothing is reported as a result, not as silence")
  void anEmptyReportStillSaysSomething() {
    Redactor redactor = exporting();
    redactor.environmentValue("PATH", "/usr/bin", "env");

    assertThat(redactor.report().isEmpty()).isTrue();
    assertThat(redactor.report().toString())
        .contains("No pattern matched")
        .contains("not a guarantee");
  }

  @Test
  @DisplayName("the report never promises more than pattern matching can deliver")
  void theReportDoesNotOverclaim() {
    Redactor redactor = exporting();
    redactor.environmentValue("GITHUB_TOKEN", "ghp_aaa", "env");

    assertThat(redactor.report().toString())
        .contains("finds what it was told to look for")
        .doesNotContain("all secrets")
        .doesNotContain("safe to share");
  }

  @Test
  @DisplayName("reports merge, so a multi-pass export reports once")
  void reportsMerge() {
    Redactor first = exporting();
    first.environmentValue("GITHUB_TOKEN", "a", "env");
    Redactor second = exporting();
    second.environmentValue("AWS_SECRET_ACCESS_KEY", "b", "env");

    RedactionReport combined = new RedactionReport();
    combined.merge(first.report());
    combined.merge(second.report());

    assertThat(combined.redactions()).isEqualTo(2);
    assertThat(combined.distinctSecrets()).isEqualTo(2);
    assertThat(combined.byField()).containsEntry("env", 2L);
  }

  @Test
  @DisplayName("an export policy cannot be built with secret redaction switched off")
  void exportRedactionIsMandatory() {
    // docs/privacy.md: export runs redaction mandatorily. There is no
    // constructor, factory or wither that produces an export policy with an
    // empty pattern list.
    assertThat(RedactionPolicy.forExport().patterns()).isNotEmpty();
    assertThat(RedactionPolicy.forExport().redactAbsolutePaths()).isTrue();
    for (Method method : RedactionPolicy.class.getMethods()) {
      if (method.getReturnType() != RedactionPolicy.class || method.getParameterCount() != 0) {
        continue;
      }
      assertThat(method.getName())
          .as("a no-argument wither that could disable redaction")
          .isNotEqualTo("withoutRedaction");
    }
  }
}
