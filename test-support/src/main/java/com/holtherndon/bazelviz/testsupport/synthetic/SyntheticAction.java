package com.holtherndon.bazelviz.testsupport.synthetic;

/**
 * One synthetic action. Field semantics mirror the eventual domain model
 * loosely; this type exists only for benchmarks and spikes and must stay a
 * cheap value carrier.
 *
 * <p>{@code cacheState}: 0 unknown, 1 hit, 2 miss. {@code runner}: 0 local,
 * 1 remote, 2 worker, 3 sandbox. {@code status}: 0 success, 1 failed,
 * 2 cancelled.
 */
public record SyntheticAction(
        long index,
        long startMicros,
        long endMicros,
        int mnemonicIndex,
        int targetIndex,
        int packageIndex,
        int status,
        int cacheState,
        int runner,
        int inputCount,
        long knownInputBytes,
        int outputCount,
        long knownOutputBytes) {

    public long durationMicros() {
        return endMicros - startMicros;
    }
}
