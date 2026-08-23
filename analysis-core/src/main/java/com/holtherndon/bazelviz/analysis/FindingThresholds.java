package com.holtherndon.bazelviz.analysis;

/**
 * The numbers every finding has to declare.
 *
 * <h2>Why they are a parameter and not constants in the rules</h2>
 *
 * <p>Plan section 16 requires each finding to state "threshold used", and a
 * threshold buried in the rule that produced it can be stated only by copying
 * it into a string beside the comparison — where the two drift apart on the
 * first change. Passing them in means the rule reads the same value it prints,
 * and means a user can move one and see the finding list change, which is the
 * honest response to a reader who disagrees with where a line was drawn.
 *
 * <p>None of these is a discovered constant. They are round numbers chosen to
 * be roughly right on the builds measured while writing them, and the
 * {@code why} text of every finding says so by naming the threshold rather than
 * asserting a problem.
 *
 * @param queueDominatedFraction queue time above this share of an attempt makes
 *     it a candidate
 * @param transferDominatedFraction network, upload and fetch time together
 *     above this share
 * @param tinyActionMicros an action shorter than this counts as tiny
 * @param tinyActionCount how many tiny actions in one group before the group is
 *     worth mentioning
 * @param cacheMissRate a group missing more often than this is a candidate
 * @param cacheCoverageFloor and only when at least this share of the group
 *     reported a cache state at all — plan 16.1 requires sufficient coverage
 *     before a cache finding may be raised
 * @param lowParallelismDivisor a window is low-parallelism below the session's
 *     typical concurrency divided by this
 * @param lowParallelismMinimumMicros and only when it lasts at least this long
 * @param criticalPathShare a derived critical path longer than this share of
 *     the wall clock means the dependencies, not the machine, set the length
 * @param highFanOut direct consumers above this
 * @param highInputBytes an action reading more than this
 * @param highOutputBytes an action producing more than this
 * @param repeatedAttempts attempts at or above this on one action
 * @param graphCoverageFloor below this share of the action graph correlated,
 *     graph-derived findings are unreliable and one says so
 * @param minimumGroupActions a group smaller than this is not reported at all —
 *     a rate over three actions is noise wearing a percentage sign
 */
public record FindingThresholds(
        double queueDominatedFraction,
        double transferDominatedFraction,
        long tinyActionMicros,
        long tinyActionCount,
        double cacheMissRate,
        double cacheCoverageFloor,
        int lowParallelismDivisor,
        long lowParallelismMinimumMicros,
        double criticalPathShare,
        long highFanOut,
        long highInputBytes,
        long highOutputBytes,
        long repeatedAttempts,
        double graphCoverageFloor,
        long minimumGroupActions) {

    /** The defaults, all of them round numbers rather than measured constants. */
    public static FindingThresholds defaults() {
        return new FindingThresholds(
                /* queueDominatedFraction= */ 0.5,
                /* transferDominatedFraction= */ 0.5,
                /* tinyActionMicros= */ 50_000,
                /* tinyActionCount= */ 500,
                /* cacheMissRate= */ 0.5,
                /* cacheCoverageFloor= */ 0.5,
                /* lowParallelismDivisor= */ 2,
                /* lowParallelismMinimumMicros= */ 500_000,
                /* criticalPathShare= */ 0.5,
                /* highFanOut= */ 100,
                /* highInputBytes= */ 512L * 1024 * 1024,
                /* highOutputBytes= */ 256L * 1024 * 1024,
                /* repeatedAttempts= */ 2,
                /* graphCoverageFloor= */ 0.5,
                /* minimumGroupActions= */ 10);
    }
}
