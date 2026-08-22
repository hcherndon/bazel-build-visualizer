package com.holtherndon.bazelviz.app.cli;

import com.holtherndon.bazelviz.capture.file.importer.ImportPhase;
import com.holtherndon.bazelviz.capture.file.importer.ImportProgress;
import com.holtherndon.bazelviz.capture.file.importer.ImportProgressListener;
import java.io.PrintStream;
import java.util.Locale;
import java.util.OptionalDouble;
import java.util.function.LongSupplier;

/**
 * Draws import progress on stderr, leaving stdout free for the summary so that
 * {@code bbv import --json … > summary.json} works while the operator still
 * watches the progress.
 *
 * <p>Throttled twice over. The importer already limits how often it samples;
 * this adds a wall-clock floor of its own because a terminal is slower than a
 * sampler and a redirected stderr would otherwise collect thousands of lines.
 * A phase change is always drawn, throttle or not: "preserving" taking thirty
 * seconds on a large file must not look like a hang.
 *
 * <p>The percentage appears only when the importer supplies a total size. When
 * {@code totalBytes} is empty — a growing file, an unstattable source — the
 * line shows what is known and no percentage at all. Inventing a denominator so
 * that a bar can reach 100% is exactly the "unavailable rendered as a number"
 * the project rules forbid.
 */
final class ProgressReporter implements ImportProgressListener {

    /** Five redraws a second: fast enough to look live, slow enough to read. */
    static final long DEFAULT_INTERVAL_MILLIS = 200;

    private final PrintStream err;
    private final boolean quiet;
    private final boolean interactive;
    private final long intervalNanos;
    private final LongSupplier nanoTime;

    private ImportPhase lastPhase;
    private long lastDrawNanos;
    private long lastRecords;
    private long lastRecordNanos;
    private int lastLineLength;
    private boolean lineOpen;

    ProgressReporter(PrintStream err, boolean quiet, boolean interactive) {
        this(err, quiet, interactive, DEFAULT_INTERVAL_MILLIS, System::nanoTime);
    }

    ProgressReporter(
            PrintStream err,
            boolean quiet,
            boolean interactive,
            long intervalMillis,
            LongSupplier nanoTime) {
        this.err = err;
        this.quiet = quiet;
        this.interactive = interactive;
        this.intervalNanos = intervalMillis * 1_000_000L;
        this.nanoTime = nanoTime;
    }

    @Override
    public void onProgress(ImportProgress progress) {
        if (quiet) {
            return;
        }
        long now = nanoTime.getAsLong();
        boolean phaseChanged = progress.phase() != lastPhase;
        if (!phaseChanged && lastDrawNanos != 0 && now - lastDrawNanos < intervalNanos) {
            return;
        }
        if (phaseChanged && lineOpen) {
            endLine();
        }
        String rate = instantRate(progress.recordsRead(), now);
        lastPhase = progress.phase();
        lastDrawNanos = now;
        lastRecords = progress.recordsRead();
        lastRecordNanos = now;
        draw(render(progress, rate));
    }

    /**
     * The rate over the gap between two samples rather than over the whole run,
     * because during a long import the recent throughput is the number that
     * tells the operator whether anything is still moving. Empty until there is
     * a real interval to divide by.
     */
    private String instantRate(long records, long now) {
        if (lastRecordNanos == 0 || now <= lastRecordNanos || records < lastRecords) {
            return null;
        }
        long deltaRecords = records - lastRecords;
        if (deltaRecords == 0) {
            return null;
        }
        return Formatting.rate(deltaRecords, now - lastRecordNanos);
    }

    private String render(ImportProgress progress, String rate) {
        StringBuilder line = new StringBuilder();
        line.append(String.format(Locale.ROOT, "%-18s", phaseLabel(progress.phase())));
        line.append(String.format(Locale.ROOT, "%12s events", Formatting.count(progress.recordsRead())));
        line.append("  ").append(Formatting.bytes(progress.bytesRead()));
        if (progress.totalBytes().isPresent()) {
            line.append(" / ").append(Formatting.bytes(progress.totalBytes().getAsLong()));
            OptionalDouble fraction = progress.fractionComplete();
            if (fraction.isPresent()) {
                line.append(String.format(Locale.ROOT, " (%.0f%%)", fraction.getAsDouble() * 100));
            }
        }
        if (rate != null) {
            line.append("  ").append(rate);
        }
        return line.toString();
    }

    private static String phaseLabel(ImportPhase phase) {
        return switch (phase) {
            case DETECTING -> "detecting";
            case PRESERVING -> "preserving source";
            case REPLAYING_JOURNAL -> "replaying journal";
            case READING -> "reading";
            case FINALIZING -> "finalizing";
        };
    }

    private void draw(String line) {
        if (interactive) {
            StringBuilder padded = new StringBuilder(line);
            for (int i = line.length(); i < lastLineLength; i++) {
                padded.append(' ');
            }
            err.print('\r');
            err.print(padded);
            err.flush();
            lastLineLength = line.length();
            lineOpen = true;
        } else {
            err.println(line);
            err.flush();
        }
    }

    private void endLine() {
        if (lineOpen) {
            err.println();
            err.flush();
            lineOpen = false;
            lastLineLength = 0;
        }
    }

    /** Closes the progress line so the summary starts on a clean row. */
    void finish() {
        if (quiet) {
            return;
        }
        endLine();
    }
}
