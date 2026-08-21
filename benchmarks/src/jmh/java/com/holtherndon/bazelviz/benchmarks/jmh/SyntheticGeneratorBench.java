package com.holtherndon.bazelviz.benchmarks.jmh;

import com.holtherndon.bazelviz.testsupport.synthetic.SyntheticAction;
import com.holtherndon.bazelviz.testsupport.synthetic.SyntheticActionGenerator;
import com.holtherndon.bazelviz.testsupport.synthetic.SyntheticScale;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Cost floor of {@link SyntheticActionGenerator#actionAt(long)}. Every table,
 * timeline, and SQL benchmark draws rows from this generator, so its per-row
 * cost is the baseline to subtract when interpreting their numbers.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1)
@State(Scope.Thread)
public class SyntheticGeneratorBench {

    private SyntheticActionGenerator generator;
    private long actionCount;
    private long nextIndex;

    @Setup
    public void setUp() {
        generator = new SyntheticActionGenerator(SyntheticScale.TIER2, 42L);
        actionCount = generator.actionCount();
        nextIndex = 0;
    }

    @Benchmark
    public SyntheticAction actionAtSequential() {
        long index = nextIndex;
        nextIndex = index + 1 == actionCount ? 0 : index + 1;
        return generator.actionAt(index);
    }

    @Benchmark
    public SyntheticAction actionAtRandom() {
        // Scatter the index with a cheap SplitMix64 finalizer so this measures
        // random access without a heavyweight RNG competing in the loop.
        long index = nextIndex++;
        long scattered = Math.floorMod(splitMix64(index), actionCount);
        return generator.actionAt(scattered);
    }

    private static long splitMix64(long z) {
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }
}
