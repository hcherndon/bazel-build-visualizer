package com.holtherndon.bazelviz.ui.timeline;

import java.util.Arrays;

/**
 * The individual spans visible in one time range, in primitive arrays.
 *
 * <h2>Why arrays and not a list of records</h2>
 *
 * <p>Close zoom draws spans one by one, and a window is refetched every time
 * the user pans. A list of records would allocate one object per span per pan;
 * three parallel arrays allocate three, and the paint loop reads them without
 * a dereference. This is the same reasoning as {@code CsrGraph}'s.
 *
 * <h2>Bounded, and honest about it</h2>
 *
 * <p>A range can contain more spans than a screen has pixels. The window takes
 * at most {@link #MAX_SPANS} and records how many it left out, so the view can
 * say "showing 5,000 of 40,000 here" instead of drawing a dense stripe that
 * looks complete. Plan rule 12: never silently truncate.
 *
 * <p>Truncation is a signal to zoom in, not a failure — and the count is what
 * makes it one.
 */
public final class SpanWindow {

    /**
     * Spans drawn individually before the view falls back to density.
     *
     * <p>Above this the individual marks are narrower than a pixel and the
     * aggregate bins are both faster and more honest, so this is the point at
     * which drawing spans stops being better than drawing bins.
     */
    public static final int MAX_SPANS = 20_000;

    /** A window covering nothing, for a session with no timeline. */
    public static final SpanWindow EMPTY =
            new SpanWindow(new long[0], new long[0], new int[0], new long[0], 0, 0, 0, 0);

    private final long[] startMicros;
    private final long[] endMicros;
    private final int[] flags;
    private final long[] nodeIds;
    private final int size;
    private final long fromMicros;
    private final long toMicros;
    private final long droppedSpans;

    private SpanWindow(long[] startMicros, long[] endMicros, int[] flags, long[] nodeIds,
            int size, long fromMicros, long toMicros, long droppedSpans) {
        this.startMicros = startMicros;
        this.endMicros = endMicros;
        this.flags = flags;
        this.nodeIds = nodeIds;
        this.size = size;
        this.fromMicros = fromMicros;
        this.toMicros = toMicros;
        this.droppedSpans = droppedSpans;
    }

    /** Collects spans for a range; call from a worker, never the EDT. */
    public static Builder builder(long fromMicros, long toMicros) {
        return new Builder(fromMicros, toMicros);
    }

    public int size() {
        return size;
    }

    public long startMicros(int i) {
        return startMicros[i];
    }

    public long endMicros(int i) {
        return endMicros[i];
    }

    public int flags(int i) {
        return flags[i];
    }

    /** The row id this span came from, for selection round-trips. */
    public long nodeId(int i) {
        return nodeIds[i];
    }

    /** The range this window was built for. */
    public long fromMicros() {
        return fromMicros;
    }

    public long toMicros() {
        return toMicros;
    }

    /** True when this window still covers {@code [from, to)}. */
    public boolean covers(long from, long to) {
        return from >= fromMicros && to <= toMicros;
    }

    /** Accumulates spans without knowing how many there will be. */
    public static final class Builder {

        private final long fromMicros;
        private final long toMicros;
        private long[] starts = new long[1024];
        private long[] ends = new long[1024];
        private int[] flagValues = new int[1024];
        private long[] ids = new long[1024];
        private int size;
        private long dropped;

        private Builder(long fromMicros, long toMicros) {
            this.fromMicros = fromMicros;
            this.toMicros = toMicros;
        }

        /** Adds a span, or counts it as dropped once the cap is reached. */
        public Builder add(long start, long end, int flags, long nodeId) {
            if (size == MAX_SPANS) {
                dropped++;
                return this;
            }
            if (size == starts.length) {
                int grown = Math.min(size * 2, MAX_SPANS);
                starts = Arrays.copyOf(starts, grown);
                ends = Arrays.copyOf(ends, grown);
                flagValues = Arrays.copyOf(flagValues, grown);
                ids = Arrays.copyOf(ids, grown);
            }
            starts[size] = start;
            ends[size] = end;
            flagValues[size] = flags;
            ids[size] = nodeId;
            size++;
            return this;
        }

        /** How many spans did not fit. */
        public long dropped() {
            return dropped;
        }

        public SpanWindow build() {
            return new SpanWindow(
                    Arrays.copyOf(starts, size), Arrays.copyOf(ends, size),
                    Arrays.copyOf(flagValues, size), Arrays.copyOf(ids, size),
                    size, fromMicros, toMicros, dropped);
        }
    }

    /**
     * Spans in this range that were not collected.
     *
     * <p>Non-zero means the view is showing some of what is here, and must say
     * so rather than letting a truncated stripe read as the whole.
     */
    public long droppedSpans() {
        return droppedSpans;
    }
}
