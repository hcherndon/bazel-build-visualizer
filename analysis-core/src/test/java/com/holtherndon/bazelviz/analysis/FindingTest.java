package com.holtherndon.bazelviz.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.OptionalLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The two Phase 8 exit criteria that are properties of a finding rather than of
 * a rule: it must link to the records that support it, and it must not claim
 * more than observational data can carry.
 *
 * <p>Both are enforced in the constructor, so the test for each is simply an
 * attempt to build one that breaks it.
 */
final class FindingTest {

    private static Finding.Evidence someEvidence() {
        return Finding.Evidence.action(7, "//pkg:lib", "took 4.2 s");
    }

    private static Finding finding(String why) {
        return finding(why, List.of(someEvidence()), false);
    }

    private static Finding finding(
            String why, List<Finding.Evidence> evidence, boolean proven) {
        return new Finding(
                "test-rule",
                "Something is large",
                Finding.Severity.MEDIUM,
                Finding.Confidence.HIGH,
                evidence,
                List.of(new Finding.MetricValue("Duration", "4.2 s", "execution log")),
                "above 1 s",
                why,
                "Measured over one build on one machine.",
                "Open the action and look at its inputs.",
                List.of(Finding.Link.to(Finding.Link.View.ACTIONS, "Show it")),
                proven);
    }

    @Test
    @DisplayName("a finding without supporting records cannot be built")
    void evidenceIsRequired() {
        assertThatThrownBy(() -> finding("This may be worth investigating.", List.of(), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("link to the records");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "Fixing this will improve build time.",
        "This will reduce the build by four seconds.",
        "The slow build is caused by this action.",
        "This definitely explains the regression.",
        "This proves the cache is misconfigured.",
        "Removing it guarantees a faster build.",
    })
    @DisplayName("causal claims and promised outcomes are refused")
    void causalLanguageIsRefused(String why) {
        assertThatThrownBy(() -> finding(why))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("\"root cause\" needs explicit failure data behind it")
    void rootCauseNeedsProof() {
        String why = "The root cause may be visible in the failure detail.";

        assertThatThrownBy(() -> finding(why))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("root cause");
        // With a structured failure record behind it, the same sentence is a
        // report rather than a guess.
        assertThatCode(() -> finding(why, List.of(someEvidence()), true))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a conclusion where the data supports a candidate is refused")
    void hedgingIsRequired() {
        assertThatThrownBy(() -> finding("This action is the reason the build is slow."))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("candidate");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "This may indicate a cache problem.",
        "This action is a candidate for splitting.",
        "Long queue times are associated with remote executor load.",
        "It is worth investigating whether the inputs change every build.",
    })
    @DisplayName("the wording plan 16.2 asks for is accepted")
    void approvedLanguageIsAccepted(String why) {
        assertThatCode(() -> finding(why)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("every part of a finding is checked, not only the summary")
    void everyFieldIsChecked() {
        assertThatThrownBy(() -> new Finding(
                "test-rule", "This will improve build time",
                Finding.Severity.LOW, Finding.Confidence.LOW,
                List.of(someEvidence()), List.of(), "above 1 s",
                "This may matter.", "Measured once.", "Look at it.",
                List.of(), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("title");
        assertThatThrownBy(() -> new Finding(
                "test-rule", "Something is large",
                Finding.Severity.LOW, Finding.Confidence.LOW,
                List.of(someEvidence()), List.of(), "above 1 s",
                "This may matter.", "Measured once.",
                "Removing it will speed up the build.",
                List.of(), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("suggestedInvestigation");
    }

    @Test
    @DisplayName("evidence carries the id the UI needs to navigate to the record")
    void evidencePointsSomewhere() {
        Finding.Evidence action = Finding.Evidence.action(42, "//pkg:lib", "4.2 s");
        Finding.Evidence group = Finding.Evidence.group("Javac", "1,200 actions");

        assertThat(action.kind()).isEqualTo(Finding.Evidence.Kind.ACTION);
        assertThat(action.id()).isEqualTo(OptionalLong.of(42));
        // A group has no row id, and says so rather than reporting zero.
        assertThat(group.id()).isEmpty();
    }
}
