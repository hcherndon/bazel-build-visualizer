package com.holtherndon.bazelviz.testsupport.synthetic;

import java.util.stream.LongStream;
import java.util.stream.Stream;

/**
 * Deterministic synthetic action source.
 *
 * <p>The load-bearing property is O(1) random access: {@link #actionAt(long)} is a pure function of
 * {@code (seed, index)}, so a 50-million-row table model can fetch any page without anything being
 * materialized or stored. All benchmark and spike code must go through this API; do not
 * pre-generate arrays of actions.
 */
public final class SyntheticActionGenerator {

  /** Plausible mnemonics so spikes render realistic strings. */
  public static final String[] MNEMONICS = {
    "CppCompile",
    "CppLink",
    "Javac",
    "JavaLink",
    "GoCompilePkg",
    "GoLink",
    "TsProject",
    "Turbine",
    "KotlinCompile",
    "ProtoCompile",
    "GenProtoDescriptor",
    "ObjcCompile",
    "SwiftCompile",
    "Genrule",
    "FileWrite",
    "SymlinkTree",
    "TestRunner",
    "SourceSymlinkManifest",
    "PackageZip",
    "ActionCacheCheck",
    "CcStrip",
    "SolibSymlink",
    "Middleman",
    "StarlarkAction",
  };

  private static final long MEAN_DURATION_MICROS = 30_000; // 30 ms
  private static final int SIMULATED_PARALLELISM = 64;
  private static final long MIN_DURATION_MICROS = 400; // 0.4 ms
  private static final long MAX_DURATION_MICROS = 120_000_000; // 120 s

  private final SyntheticScale scale;
  private final long seed;
  private final long wallMicros;

  public SyntheticActionGenerator(SyntheticScale scale, long seed) {
    this.scale = scale;
    this.seed = seed;
    this.wallMicros =
        Math.max(
            scale.actionCount() * MEAN_DURATION_MICROS / SIMULATED_PARALLELISM,
            2 * MAX_DURATION_MICROS);
  }

  public SyntheticScale scale() {
    return scale;
  }

  public long actionCount() {
    return scale.actionCount();
  }

  /** Simulated build wall-clock length; every action span fits inside [0, wallMicros). */
  public long buildWallMicros() {
    return wallMicros;
  }

  /** Pure O(1) lookup; identical (seed, index) always yields the identical action. */
  public SyntheticAction actionAt(long index) {
    if (index < 0 || index >= scale.actionCount()) {
      throw new IndexOutOfBoundsException("action index " + index + " of " + scale.actionCount());
    }
    long h1 = mix(seed ^ mix(index));
    long h2 = mix(h1);
    long h3 = mix(h2);
    long h4 = mix(h3);

    long duration = sampleDurationMicros(h1);
    long start = Math.floorMod(h2, Math.max(1, wallMicros - duration));

    int mnemonic = (int) Math.floorMod(h3, MNEMONICS.length);
    int target = (int) Math.floorMod(h3 >>> 8, Math.max(1, scale.actionCount() / 20));
    int pkg = target / 8;

    int status = Math.floorMod(h4, 1000) < 3 ? 1 : 0; // ~0.3% failures
    int cacheState = (int) Math.floorMod(h4 >>> 10, 3);
    int runner = (int) Math.floorMod(h4 >>> 20, 4);

    int inputCount = 1 + (int) Math.floorMod(h4 >>> 30, 200);
    long inputBytes = inputCount * (1024 + Math.floorMod(h1 >>> 16, 512 * 1024));
    int outputCount = 1 + (int) Math.floorMod(h2 >>> 40, 4);
    long outputBytes = outputCount * (256 + Math.floorMod(h3 >>> 24, 8 * 1024 * 1024));

    return new SyntheticAction(
        index,
        start,
        start + duration,
        mnemonic,
        target,
        pkg,
        status,
        cacheState,
        runner,
        inputCount,
        inputBytes,
        outputCount,
        outputBytes);
  }

  public Stream<SyntheticAction> stream() {
    return LongStream.range(0, scale.actionCount()).mapToObj(this::actionAt);
  }

  /** Log-skewed duration: most actions are milliseconds, a thin tail reaches minutes. */
  private static long sampleDurationMicros(long hash) {
    double u = (hash >>> 11) * 0x1.0p-53; // uniform [0,1)
    double skewed = u * u * u;
    double logMin = Math.log(MIN_DURATION_MICROS);
    double logMax = Math.log(MAX_DURATION_MICROS);
    long raw = (long) Math.exp(logMin + skewed * (logMax - logMin));
    // exp(log(400)) rounds one ulp low (399.999...), so near-zero u used to
    // truncate to 399 us; clamp so the documented [MIN, MAX] envelope holds
    // for every hash value.
    return Math.clamp(raw, MIN_DURATION_MICROS, MAX_DURATION_MICROS);
  }

  /** SplitMix64 finalizer. */
  static long mix(long z) {
    z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
    z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
    return z ^ (z >>> 31);
  }
}
