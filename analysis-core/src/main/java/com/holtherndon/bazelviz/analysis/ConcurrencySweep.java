package com.holtherndon.bazelviz.analysis;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.OptionalLong;

/**
 * How many actions were running at once, over the whole build.
 *
 * <h2>What this answers</h2>
 *
 * <p>Plan 15.2 asks for peak observed concurrency, average observed
 * concurrency, the parallelism factor, time with zero active actions, and time
 * with low concurrency. Plan 16.1 asks for the low-parallelism windows
 * themselves, so a finding can point at one. All of those come from one exact
 * sweep over the observed spans, which is what this is.
 *
 * <h2>Exact, not binned</h2>
 *
 * <p>The timeline's level-of-detail pyramid (Phase 6) also counts active
 * actions, but per bin: it answers "how busy was this stretch" at the
 * resolution a screen can show. This answers "what is the largest number of
 * actions that were ever running at one instant", and a binned count cannot,
 * because a peak narrower than a bin is averaged away inside it. So this sorts
 * the endpoints and walks every transition.
 *
 * <h2>Half-open spans</h2>
 *
 * <p>A span occupies {@code [start, end)}. An action ending at exactly the
 * instant another starts does not overlap it, so a chain of back-to-back
 * actions reports a concurrency of one rather than briefly two. The walk
 * enforces that by applying every end at an instant before every start at it.
 *
 * <p>The consequence worth knowing is that a span of zero length occupies no
 * time and so is never running. That is not a corner case: Bazel 8.4.1
 * publishes {@code endTime == startTime} for every action it reports, a
 * five-second sleep included (docs/bazel-ground-truth.md). A sweep over BEP
 * action timings from that version finds nothing running at any instant, and
 * {@link Result#instantaneousSpans()} is how the answer says so rather than
 * reporting a build that ran nothing.
 *
 * <h2>Unknown timings are counted, not assumed</h2>
 *
 * <p>An action with no observed start or end is added with
 * {@link Spans#addUntimed()} and takes no part in the sweep. It still travels
 * into the result, so every figure here can be read against how much of the
 * build it covers instead of being taken for the whole of it (rule 13).
 */
public final class ConcurrencySweep {

    private ConcurrencySweep() {}

    /** Receives one constant-concurrency interval at a time, in order. */
    @FunctionalInterface
    private interface LevelVisitor {
        /** {@code active} actions were running throughout {@code [from, to)}. */
        void level(int active, long fromMicros, long toMicros);
    }

    /**
     * The spans to sweep, accumulated one at a time.
     *
     * <p>A builder rather than two arrays because the caller streams rows out
     * of a database and does not know the count up front — and because the
     * actions that could not be timed have to be counted in the same pass. A
     * caller that filtered those out before calling would leave nothing able to
     * say how much of the build the sweep covered.
     *
     * <p>Not thread-safe: one reader thread fills one instance.
     */
    public static final class Spans {

        private long[] starts = new long[64];
        private long[] ends = new long[64];
        private int size;
        private long untimed;
        private long instantaneous;
        private boolean sorted;

        /**
         * Records an observed span.
         *
         * <p>A span of zero length is counted and not stored: it is never
         * running under the half-open convention, so including it would only
         * put an event pair into the walk that cancels itself.
         *
         * @param startMicros when it began
         * @param endMicros when it finished, which may not precede the start
         */
        public void add(long startMicros, long endMicros) {
            if (endMicros < startMicros) {
                throw new IllegalArgumentException(
                        "a span cannot end before it starts: " + startMicros + " to " + endMicros);
            }
            if (endMicros == startMicros) {
                instantaneous++;
                return;
            }
            if (size == starts.length) {
                starts = Arrays.copyOf(starts, starts.length * 2);
                ends = Arrays.copyOf(ends, ends.length * 2);
            }
            starts[size] = startMicros;
            ends[size] = endMicros;
            size++;
            sorted = false;
        }

        /** Records an action nothing timed, so the coverage figure knows of it. */
        public void addUntimed() {
            untimed++;
        }

        /** Spans that occupy time, and so take part in the sweep. */
        public int sweptCount() {
            return size;
        }

        /** Actions that had a timing at all, including the zero-length ones. */
        public long timedCount() {
            return size + instantaneous;
        }

        /** Actions with no observed timing. */
        public long untimedCount() {
            return untimed;
        }

        private void sort() {
            if (!sorted) {
                Arrays.sort(starts, 0, size);
                Arrays.sort(ends, 0, size);
                sorted = true;
            }
        }

        /**
         * The stretches where fewer than {@code threshold} actions were
         * running.
         *
         * <p>A second walk rather than a field on {@link Result}, because the
         * threshold is not knowable until the first sweep has run: plan 16.1
         * defines a low-parallelism window relative to "the session's typical
         * concurrency", which is one of the numbers the sweep produces.
         *
         * @param threshold report stretches where the active count stays below
         *     this
         * @param minimumMicros ignore stretches shorter than this, so the
         *     microsecond between two actions is not reported as a stall
         */
        public List<Window> windowsBelow(int threshold, long minimumMicros) {
            if (threshold < 1) {
                throw new IllegalArgumentException("threshold must be at least 1: " + threshold);
            }
            if (minimumMicros < 0) {
                throw new IllegalArgumentException(
                        "a window cannot be shorter than nothing: " + minimumMicros);
            }
            sort();
            List<Window> windows = new ArrayList<>();
            long[] open = {Long.MIN_VALUE};
            int[] peakInWindow = {0};
            walk(starts, ends, size, (active, from, to) -> {
                if (active < threshold) {
                    if (open[0] == Long.MIN_VALUE) {
                        open[0] = from;
                        peakInWindow[0] = active;
                    } else {
                        peakInWindow[0] = Math.max(peakInWindow[0], active);
                    }
                } else if (open[0] != Long.MIN_VALUE) {
                    if (from - open[0] >= minimumMicros) {
                        windows.add(new Window(open[0], from, peakInWindow[0]));
                    }
                    open[0] = Long.MIN_VALUE;
                }
            });
            // A window still open at the end closes at the last observed end;
            // it is a real stretch of the build, not an artefact of the walk.
            if (open[0] != Long.MIN_VALUE && size > 0) {
                long closesAt = ends[size - 1];
                if (closesAt - open[0] >= minimumMicros) {
                    windows.add(new Window(open[0], closesAt, peakInWindow[0]));
                }
            }
            return List.copyOf(windows);
        }
    }

    /** A stretch of the build where few actions were running. */
    public record Window(long startMicros, long endMicros, int peakActive) {

        public Window {
            if (endMicros < startMicros) {
                throw new IllegalArgumentException(
                        "a window cannot end before it starts: " + startMicros + " to " + endMicros);
            }
        }

        public long durationMicros() {
            return endMicros - startMicros;
        }
    }

    /**
     * Sweeps every span and reports what was running when.
     *
     * <p>Two walks over the sorted endpoints: the first finds the peak so the
     * level histogram can be sized exactly, the second fills it. Both use the
     * same walk, so the peak and the histogram cannot disagree about a tie.
     * Sorting two primitive arrays dominates, so the whole thing is
     * {@code O(n log n)} with no boxing and no per-span allocation.
     */
    public static Result sweep(Spans spans) {
        Objects.requireNonNull(spans, "spans");
        spans.sort();
        int size = spans.size;
        if (size == 0) {
            return Result.nothingSwept(spans.untimed, spans.instantaneous);
        }

        long[] starts = spans.starts;
        long[] ends = spans.ends;

        int[] peak = {0};
        walk(starts, ends, size, (active, from, to) -> peak[0] = Math.max(peak[0], active));

        long[] timeAtLevel = new long[peak[0] + 1];
        long[] peakFirstSeen = {starts[0]};
        boolean[] peakSeen = {false};
        walk(starts, ends, size, (active, from, to) -> {
            timeAtLevel[active] += to - from;
            if (!peakSeen[0] && active == peak[0]) {
                peakFirstSeen[0] = from;
                peakSeen[0] = true;
            }
        });

        long spanMicros = 0;
        boolean spanExact = true;
        for (int i = 0; i < size; i++) {
            try {
                spanMicros = Math.addExact(spanMicros, ends[i] - starts[i]);
            } catch (ArithmeticException overflow) {
                spanExact = false;
                break;
            }
        }

        return new Result(
                peak[0],
                peakFirstSeen[0],
                timeAtLevel,
                starts[0],
                ends[size - 1],
                spanExact ? OptionalLong.of(spanMicros) : OptionalLong.empty(),
                size,
                spans.untimed,
                spans.instantaneous);
    }

    /**
     * Walks the sorted endpoints, handing each constant-concurrency interval to
     * {@code visitor} in time order.
     *
     * <p>The one tricky loop in this class, written once so the peak pass and
     * the histogram pass cannot drift apart. Every event at an instant is
     * applied before the interval that follows it is emitted, so no interval is
     * empty and no instant is visited twice. Ends are applied before starts,
     * which is the half-open convention; that is safe because a zero-length
     * span was never stored, so every end at an instant belongs to a span that
     * started strictly earlier and has therefore already been counted.
     */
    private static void walk(long[] starts, long[] ends, int size, LevelVisitor visitor) {
        if (size == 0) {
            return;
        }
        int startIndex = 0;
        int endIndex = 0;
        int active = 0;
        long now = starts[0];
        while (startIndex < size && starts[startIndex] == now) {
            active++;
            startIndex++;
        }
        while (endIndex < size) {
            long next = startIndex < size
                    ? Math.min(starts[startIndex], ends[endIndex])
                    : ends[endIndex];
            visitor.level(active, now, next);
            now = next;
            while (endIndex < size && ends[endIndex] == now) {
                active--;
                endIndex++;
            }
            while (startIndex < size && starts[startIndex] == now) {
                active++;
                startIndex++;
            }
        }
    }

    /** What the sweep found. */
    public static final class Result {

        private final int peakActive;
        private final long peakFirstSeenMicros;
        private final long[] timeAtLevel;
        private final long windowStartMicros;
        private final long windowEndMicros;
        private final OptionalLong totalSpanMicros;
        private final long sweptSpans;
        private final long untimedSpans;
        private final long instantaneousSpans;

        Result(int peakActive, long peakFirstSeenMicros, long[] timeAtLevel,
                long windowStartMicros, long windowEndMicros, OptionalLong totalSpanMicros,
                long sweptSpans, long untimedSpans, long instantaneousSpans) {
            this.peakActive = peakActive;
            this.peakFirstSeenMicros = peakFirstSeenMicros;
            this.timeAtLevel = timeAtLevel.clone();
            this.windowStartMicros = windowStartMicros;
            this.windowEndMicros = windowEndMicros;
            this.totalSpanMicros = totalSpanMicros;
            this.sweptSpans = sweptSpans;
            this.untimedSpans = untimedSpans;
            this.instantaneousSpans = instantaneousSpans;
        }

        static Result nothingSwept(long untimed, long instantaneous) {
            return new Result(
                    0, 0, new long[] {0}, 0, 0, OptionalLong.empty(), 0, untimed, instantaneous);
        }

        /** The most actions observed running at one instant. */
        public int peakActive() {
            return peakActive;
        }

        /** When that peak was first reached. */
        public long peakFirstSeenMicros() {
            return peakFirstSeenMicros;
        }

        /** Microseconds spent with exactly {@code level} actions running. */
        public long timeAtLevel(int level) {
            return level < 0 || level >= timeAtLevel.length ? 0 : timeAtLevel[level];
        }

        /** The first observed start. */
        public long windowStartMicros() {
            return windowStartMicros;
        }

        /** The last observed end. */
        public long windowEndMicros() {
            return windowEndMicros;
        }

        /** The stretch the sweep covers, first start to last end. */
        public long windowMicros() {
            return windowEndMicros - windowStartMicros;
        }

        /**
         * Microseconds inside the window with nothing running.
         *
         * <p>Plan 15.2's "time with zero active actions", except that this is
         * over the observed spans and not over Bazel's execution phase. A
         * caller that knows the phase boundaries can compare the two; one that
         * does not should not be told that it does.
         */
        public long idleMicros() {
            return timeAtLevel(0);
        }

        /** Microseconds spent with fewer than {@code threshold} actions running. */
        public long microsBelow(int threshold) {
            long total = 0;
            for (int level = 0; level < threshold && level < timeAtLevel.length; level++) {
                total += timeAtLevel[level];
            }
            return total;
        }

        /** The summed length of every swept span, absent if that overflowed. */
        public OptionalLong totalSpanMicros() {
            return totalSpanMicros;
        }

        /**
         * Plan 15.4's parallelism factor: span time over wall-clock interval.
         *
         * <p>An aggregate concurrency indicator and <em>not</em> CPU
         * utilization. A build of remote actions can report far more than the
         * machine has cores, and a build of one long local action reports one
         * whatever it did to the CPU.
         */
        public OptionalDouble parallelismFactor() {
            long window = windowMicros();
            if (window <= 0 || totalSpanMicros.isEmpty()) {
                return OptionalDouble.empty();
            }
            return OptionalDouble.of((double) totalSpanMicros.getAsLong() / (double) window);
        }

        /**
         * Average actions running across the window, idle stretches included.
         *
         * <p>Identical to {@link #parallelismFactor()} by construction. Both
         * names exist because plan 15.2 and plan 15.4 ask for them under
         * different names, and a reader who found only one would reasonably
         * conclude the other was missing.
         */
        public OptionalDouble averageActive() {
            return parallelismFactor();
        }

        /** Average actions running across only the stretches something was running. */
        public OptionalDouble averageActiveWhileBusy() {
            long busy = windowMicros() - idleMicros();
            if (busy <= 0 || totalSpanMicros.isEmpty()) {
                return OptionalDouble.empty();
            }
            return OptionalDouble.of((double) totalSpanMicros.getAsLong() / (double) busy);
        }

        /** Spans that occupied time and took part in the sweep. */
        public long sweptSpans() {
            return sweptSpans;
        }

        /** Actions with no observed timing, which took no part in any of this. */
        public long untimedSpans() {
            return untimedSpans;
        }

        /**
         * Spans whose start and end were the same instant.
         *
         * <p>They occupy no time, so nothing was running during them. Bazel
         * 8.4.1 reports every action that way, which makes a sweep of its BEP
         * timings find nothing running at any instant — true of the timings and
         * false of the build.
         */
        public long instantaneousSpans() {
            return instantaneousSpans;
        }

        /** True when some of the build is not described by these figures. */
        public boolean isPartial() {
            return untimedSpans > 0 || instantaneousSpans > 0;
        }

        /** What has to be said alongside these numbers. */
        public String describe() {
            if (sweptSpans == 0) {
                if (instantaneousSpans > 0) {
                    return "All " + instantaneousSpans + " timed actions report the same start and"
                            + " end instant, so no two of them can be shown to have overlapped."
                            + " Concurrency is unknown here, not zero.";
                }
                return untimedSpans == 0
                        ? "No actions to sweep."
                        : "None of the " + untimedSpans + " actions has an observed start and end,"
                                + " so concurrency is unknown rather than zero.";
            }
            StringBuilder text = new StringBuilder();
            text.append("Peak ").append(peakActive).append(" actions running at once, ")
                    .append(String.format("%.2f", parallelismFactor().orElse(0)))
                    .append(" on average across ").append(windowMicros() / 1000)
                    .append(" ms, from ").append(sweptSpans).append(" observed spans.");
            if (untimedSpans > 0) {
                text.append(' ').append(untimedSpans)
                        .append(" further actions have no observed timing and are not counted here;")
                        .append(" these figures describe the rest.");
            }
            if (instantaneousSpans > 0) {
                text.append(' ').append(instantaneousSpans)
                        .append(" spans start and end at the same instant, so they occupy no time")
                        .append(" and never raise the count.");
            }
            return text.toString();
        }
    }
}
