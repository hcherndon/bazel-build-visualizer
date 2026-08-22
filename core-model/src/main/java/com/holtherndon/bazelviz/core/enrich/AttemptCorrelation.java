package com.holtherndon.bazelviz.core.enrich;

/**
 * How an execution-log attempt came to be attached to a BEP action — or why it
 * was not.
 *
 * <h2>Why this is stored rather than inferred from a null</h2>
 *
 * <p>Plan 24 makes "ambiguous correlations remain visible" a Phase 4 exit
 * criterion, and a null {@code action_id} cannot carry that. Four different
 * facts all produce a null: the action ran in the Bazel server and never
 * spawned a subprocess, the spawn is a test whose outputs BEP does not name,
 * the spawn matched more than one action, and the spawn matched nothing at all.
 * The first is normal and expected; the last means the correlation is wrong.
 *
 * <p>Measured shape of the problem, from {@code docs/exec-log-and-profile.md}:
 * a build producing 13 {@code actionCompleted} events produced 4 spawns (K1),
 * so most actions legitimately have no attempt; and every test produced two
 * spawns sharing one label and mnemonic, the second being XML generation which
 * succeeds even when the test failed (K3).
 */
public enum AttemptCorrelation {

    /**
     * The spawn's produced output path equalled an action's primary output.
     *
     * <p>The strong case: {@code primary_output} is the BEP's action identity
     * (finding A1), so this is an identity match rather than a heuristic.
     * Measured 4 of 4 for ordinary actions on 7.6.1, 8.4.1 and 9.2.0.
     */
    MATCHED_BY_OUTPUT,

    /**
     * The spawn is a test execution, matched to a test by label rather than to
     * an action by output.
     *
     * <p>Necessary because test spawns match no action on any version: on 7.6.1
     * the main test spawn has no resolvable output at all, and on 8.4.1+ its
     * only one is a {@code test.outputs} directory that BEP never names as a
     * primary output (K2).
     */
    MATCHED_BY_TEST_LABEL,

    /**
     * More than one action was an equally good match, so none was chosen.
     *
     * <p>Choosing one and moving on is what makes a tool quietly wrong. The
     * row keeps its measurements and says the attachment is undecided.
     */
    AMBIGUOUS,

    /**
     * No action matched, and one was expected.
     *
     * <p>Distinct from {@link #NO_ACTION_EXPECTED}: this means the correlation
     * failed, and enough of these means the join key is wrong.
     */
    UNMATCHED,

    /**
     * There is no action to match because the spawn is not an action execution
     * the BEP reports.
     *
     * <p>The common, healthy case for the two-thirds of actions that run inside
     * the Bazel server (K1), and for the XML-generation spawn that follows
     * every test (K3).
     */
    NO_ACTION_EXPECTED;

    /** True when this attempt is attached to an action. */
    public boolean isAttached() {
        return this == MATCHED_BY_OUTPUT;
    }

    /** True when the correlation failed or could not be decided. */
    public boolean needsAttention() {
        return this == AMBIGUOUS || this == UNMATCHED;
    }
}
