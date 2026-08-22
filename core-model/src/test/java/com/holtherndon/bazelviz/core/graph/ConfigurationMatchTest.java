package com.holtherndon.bazelviz.core.graph;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Whether a graph may be called this build's.
 *
 * <p>Plan 8.6: never claim an exact graph match unless the configuration
 * equivalence has been verified. This is where "verified" is defined.
 */
final class ConfigurationMatchTest {

    private static final String A = "1a589d14ca3886895c1228db75ec6c30d0c253d2c9f4c3070e5f3535de9";
    private static final String B = "2d8934052f1445fdec9fefac5a616f1fb9d9dea67b8c1b3f6e1572370634";
    private static final String OTHER = "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff";

    @Test
    @DisplayName("the same configurations on both sides is the only exact match")
    void sameSetIsExact() {
        assertThat(ConfigurationMatch.of(List.of(A, B), List.of(A, B)))
                .isEqualTo(ConfigurationMatch.EXACT);
        assertThat(ConfigurationMatch.EXACT.permitsExactClaim()).isTrue();
    }

    @Test
    @DisplayName("a configuration the build never used makes the graph some other build's")
    void extraConfigurationIsAMismatch() {
        assertThat(ConfigurationMatch.of(List.of(A), List.of(A, OTHER)))
                .isEqualTo(ConfigurationMatch.MISMATCHED);
        // Checked before completeness: a graph carrying a configuration the
        // build never used is describing something else, and that matters more
        // than whether it also covers everything.
        assertThat(ConfigurationMatch.of(List.of(A, B), List.of(A, B, OTHER)))
                .isEqualTo(ConfigurationMatch.MISMATCHED);
        assertThat(ConfigurationMatch.MISMATCHED.permitsExactClaim()).isFalse();
    }

    @Test
    @DisplayName("covering only some of the build's configurations is partial, not wrong")
    void missingConfigurationIsPartial() {
        assertThat(ConfigurationMatch.of(List.of(A, B), List.of(A)))
                .isEqualTo(ConfigurationMatch.PARTIAL);
        assertThat(ConfigurationMatch.PARTIAL.permitsExactClaim()).isFalse();
    }

    @Test
    @DisplayName("nothing to compare against is unknown, never exact")
    void nothingToCompareIsUnknown() {
        // An imported graph with no build behind it. Returning EXACT here --
        // vacuously true of an empty set -- would let a graph claim to match a
        // build that was never observed.
        assertThat(ConfigurationMatch.of(List.of(), List.of(A)))
                .isEqualTo(ConfigurationMatch.UNKNOWN);
        assertThat(ConfigurationMatch.of(List.of(A), List.of()))
                .isEqualTo(ConfigurationMatch.UNKNOWN);
        assertThat(ConfigurationMatch.UNKNOWN.permitsExactClaim()).isFalse();
    }

    @Test
    @DisplayName("only one state lets a graph be called the build's")
    void exactlyOneStatePermitsTheClaim() {
        assertThat(java.util.Arrays.stream(ConfigurationMatch.values())
                        .filter(ConfigurationMatch::permitsExactClaim)
                        .toList())
                .containsExactly(ConfigurationMatch.EXACT);
    }

    @Test
    @DisplayName("every state has a sentence, and none of them renders as an enum name")
    void everyStateIsWorded() {
        for (ConfigurationMatch match : ConfigurationMatch.values()) {
            String text = match.describe(List.of(A), List.of(OTHER));
            assertThat(text).as("%s", match).isNotBlank().doesNotContain("_");
        }
        // The mismatched sentence has to say the actions are still real, or a
        // reader concludes the whole graph is garbage.
        assertThat(ConfigurationMatch.MISMATCHED.describe(List.of(), List.of(OTHER)))
                .contains("actions and edges in it are real");
    }
}
