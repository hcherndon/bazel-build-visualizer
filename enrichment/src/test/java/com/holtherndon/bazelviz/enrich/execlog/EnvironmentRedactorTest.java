package com.holtherndon.bazelviz.enrich.execlog;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.core.enrich.EnrichmentCommand.EnvVar;
import java.util.List;
import java.util.Optional;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What is withheld, and the difference between withheld and unset.
 *
 * <p>Plan 22.2 requires the default patterns and requires them to be editable. The interesting
 * property is not which names match — it is that a withheld value stays distinguishable from an
 * absent one, because a user asking "why did this action behave differently" needs to know a
 * variable was set.
 */
final class EnvironmentRedactorTest {

  private final EnvironmentRedactor redactor = new EnvironmentRedactor();

  @Test
  @DisplayName("secret-looking names lose their value and say they did")
  void secretsAreWithheld() {
    EnvVar withheld = redactor.apply("AWS_SECRET_ACCESS_KEY", "AKIAnotreallyasecret");

    assertThat(withheld.redacted()).isTrue();
    assertThat(withheld.value()).isEmpty();
    // The name survives. Hiding that the variable existed would hide the
    // difference between two builds.
    assertThat(withheld.name()).isEqualTo("AWS_SECRET_ACCESS_KEY");
  }

  @Test
  @DisplayName("matching is case-insensitive and by substring")
  void matchingIsGenerous() {
    assertThat(redactor.shouldRedact("npm_token")).isTrue();
    assertThat(redactor.shouldRedact("MY_PASSWORD_FILE")).isTrue();
    assertThat(redactor.shouldRedact("GithubAuthHeader")).isTrue();
    // Deliberately caught although it is a path, not a secret: erring
    // towards withholding is the point.
    assertThat(redactor.shouldRedact("SSH_KEY_PATH")).isTrue();
  }

  @Test
  @DisplayName("ordinary variables keep their values")
  void ordinaryVariablesSurvive() {
    EnvVar path = redactor.apply("PATH", "/usr/bin:/bin");

    assertThat(path.redacted()).isFalse();
    assertThat(path.value()).hasValue("/usr/bin:/bin");
  }

  @Test
  @DisplayName("a variable set to the empty string is not the same as a withheld one")
  void emptyIsNotWithheld() {
    EnvVar empty = redactor.apply("BAZEL_TEST_ONLY", "");

    assertThat(empty.redacted()).isFalse();
    assertThat(empty.value()).hasValue("");
  }

  @Test
  @DisplayName("the patterns are the user's to choose, including choosing none")
  void patternsAreEditable() {
    EnvironmentRedactor onlyTokens = new EnvironmentRedactor(List.of("TOKEN"));
    assertThat(onlyTokens.shouldRedact("MY_TOKEN")).isTrue();
    assertThat(onlyTokens.shouldRedact("MY_PASSWORD")).isFalse();

    assertThat(EnvironmentRedactor.none().shouldRedact("AWS_SECRET_ACCESS_KEY")).isFalse();
  }

  @Test
  @DisplayName("a redacted variable cannot be built carrying its value")
  void theTypeRefusesToLeak() {
    // The constructor is the last line of defence: a caller that sets the
    // flag and passes the value anyway is a bug that should not compile
    // into a database.
    Assertions.assertThatThrownBy(() -> new EnvVar("TOKEN", Optional.of("leaked"), true))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
