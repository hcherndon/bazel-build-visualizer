package com.holtherndon.bazelviz.ui.timeline;

import java.awt.Color;
import java.util.Optional;

/**
 * How a span or a bin is coloured, and what a colour is allowed to mean.
 *
 * <h2>Unknown is a colour</h2>
 *
 * <p>Plan 17.8 asks for cache and runner colour modes. Both describe facts that
 * most sessions do not have: without an execution log nothing knows whether an
 * action was a cache hit or where it ran. A mode that painted those spans in
 * the "miss" or "local" colour would put the most interesting claim on the
 * screen on no evidence at all.
 *
 * <p>So every mode has an explicit unknown colour, and a mode with nothing to
 * say refuses to be selected — {@link Mode#isAvailable} rather than a mode that
 * quietly paints one flat colour and looks like an answer.
 */
public final class TimelineColours {

    private TimelineColours() {}

    /** Spans and bins with nothing said about them. Deliberately unremarkable. */
    public static final Color UNKNOWN = new Color(0x9E, 0x9E, 0x9E);

    /** Work that succeeded, in the mode that says nothing else. */
    public static final Color PLAIN = new Color(0x42, 0x85, 0xF4);

    /** Work that failed. Loud in every mode, because it always matters. */
    public static final Color FAILED = new Color(0xD9, 0x3B, 0x3B);

    public static final Color CACHE_HIT = new Color(0x34, 0xA8, 0x53);
    public static final Color CACHE_MISS = new Color(0xF2, 0x8B, 0x30);

    public static final Color LOCAL = new Color(0x42, 0x85, 0xF4);
    public static final Color REMOTE = new Color(0x9C, 0x5C, 0xD1);

    /** The critical-path overlay. Drawn over, never instead of, a span's colour. */
    public static final Color CRITICAL_PATH = new Color(0xFF, 0xC1, 0x07);

    /** What a colour means in the current mode. */
    public enum Mode {
        /** One colour for work, one for failure. Always available. */
        OUTCOME("Outcome"),
        /** Hit, miss, or unknown. Needs an execution log. */
        CACHE("Cache result"),
        /** Local, remote, or unknown. Needs an execution log. */
        RUNNER("Where it ran");

        private final String displayName;

        Mode(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }

        /**
         * Whether this mode has anything to colour by in {@code model}.
         *
         * <p>A mode that is unavailable is not offered. Offering it and
         * painting everything grey would be technically honest and practically
         * a bug report.
         */
        public boolean isAvailable(TimelineModel model) {
            return switch (this) {
                case OUTCOME -> true;
                case CACHE -> model.canColourByCache();
                case RUNNER -> model.canColourByRunner();
            };
        }

        /** Why this mode is not offered, when it is not. */
        public Optional<String> unavailableReason(TimelineModel model) {
            if (isAvailable(model)) {
                return Optional.empty();
            }
            return Optional.of("no execution log has been imported, so nothing in this session"
                    + " knows " + (this == CACHE ? "which actions were cache hits"
                            : "where actions ran"));
        }
    }

    /**
     * The colour for a bin at broad zoom.
     *
     * <p>A bin holds many spans, so its colour is the majority fact rather than
     * a single span's. Where the bin has no majority — mixed hits and misses,
     * say — the unknown colour is used, because a bin coloured for whichever
     * happened to be counted first would be arbitrary.
     */
    public static Color forBin(TimelineLodIndex index, Mode mode, int level, int bin) {
        if (index.failureCount(level, bin) > 0) {
            return FAILED;
        }
        return switch (mode) {
            case OUTCOME -> PLAIN;
            case CACHE -> {
                int hits = index.cacheHitCount(level, bin);
                int misses = index.cacheMissCount(level, bin);
                if (hits == 0 && misses == 0) {
                    yield UNKNOWN;
                }
                yield hits > misses ? CACHE_HIT : misses > hits ? CACHE_MISS : UNKNOWN;
            }
            case RUNNER -> {
                int remote = index.remoteCount(level, bin);
                int local = index.localCount(level, bin);
                if (remote == 0 && local == 0) {
                    yield UNKNOWN;
                }
                yield remote > local ? REMOTE : local > remote ? LOCAL : UNKNOWN;
            }
        };
    }

    /** The colour for one span at close zoom. */
    public static Color forSpan(Mode mode, int flags) {
        if ((flags & SpanSource.FLAG_FAILED) != 0) {
            return FAILED;
        }
        return switch (mode) {
            case OUTCOME -> PLAIN;
            case CACHE -> (flags & SpanSource.FLAG_CACHE_KNOWN) == 0
                    ? UNKNOWN
                    : (flags & SpanSource.FLAG_CACHE_HIT) != 0 ? CACHE_HIT : CACHE_MISS;
            case RUNNER -> (flags & SpanSource.FLAG_RUNNER_KNOWN) == 0
                    ? UNKNOWN
                    : (flags & SpanSource.FLAG_REMOTE) != 0 ? REMOTE : LOCAL;
        };
    }
}
