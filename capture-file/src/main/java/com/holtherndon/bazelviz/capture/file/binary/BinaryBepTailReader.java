package com.holtherndon.bazelviz.capture.file.binary;

import com.holtherndon.bazelviz.core.source.Completeness;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.OptionalLong;
import java.util.function.BooleanSupplier;

/**
 * Reads a binary BEP file that is still being written (plan 9.5, file-tail
 * fallback), one poll at a time.
 *
 * <p>Each {@link #poll} consumes every frame that is now complete and stops at
 * the first partial one, remembering its offset. The next poll starts from that
 * offset — it never rescans bytes it has already delivered, which is what makes
 * tailing a multi-gigabyte file affordable. The state that survives between
 * polls is a single {@code long}, so it can be checkpointed and resumed across
 * an application restart just as easily as across a poll.
 *
 * <p>A partial tail is not an error while the writer is alive: {@link #poll}
 * returning {@link BinaryBepParseOutcome#TRUNCATED} only means "the last frame
 * has not landed yet". Only {@link #finish}, called after the writing process
 * has exited and the file is final, turns a still-partial tail into a truncated
 * source. That two-step is why the plan says to finalize only after process
 * exit and a final read.
 *
 * <p>Polling schedule is the caller's business: this class does no waiting, no
 * file watching and no threading, so it can be driven from a background
 * executor without ever touching the EDT.
 *
 * <p>Not thread-safe. Use one reader from one thread.
 */
public final class BinaryBepTailReader {

    private final Path file;
    private final BinaryBepParser parser;

    private long offset;
    private long eventCount;
    private long lastObservedSize;
    private BinaryBepParseResult lastResult;
    private boolean finished;

    private BinaryBepTailReader(Path file, BinaryBepParser parser, long startOffset) {
        this.file = file;
        this.parser = parser;
        this.offset = startOffset;
        this.lastObservedSize = startOffset;
    }

    /** Tails {@code file} from the beginning. */
    public static BinaryBepTailReader following(Path file, BinaryBepParser parser) {
        return resumingAt(file, parser, 0);
    }

    /**
     * Tails {@code file} from a previously recorded frame boundary — the
     * {@code resumeOffset} of an earlier result, or a checkpoint written before
     * a restart.
     */
    public static BinaryBepTailReader resumingAt(Path file, BinaryBepParser parser, long startOffset) {
        if (startOffset < 0) {
            throw new IllegalArgumentException("startOffset must be >= 0, got " + startOffset);
        }
        return new BinaryBepTailReader(file, parser, startOffset);
    }

    /** The offset the next poll will start from: the first byte not yet delivered. */
    public long offset() {
        return offset;
    }

    /** Frames delivered across every poll so far. */
    public long eventCount() {
        return eventCount;
    }

    /** True once {@link #finish} has run; further polls are refused. */
    public boolean isFinished() {
        return finished;
    }

    /** Reads whatever is now complete. Returns immediately if nothing new arrived. */
    public BinaryBepParseResult poll(BinaryBepEventSink sink) throws IOException {
        return poll(sink, () -> false);
    }

    /**
     * Reads whatever is now complete, from the last stop point.
     *
     * @throws SourceReplacedException if the file shrank below the consumed
     *     offset, which means it is a different file than the one being read
     * @throws NoSuchFileException if the file was deleted
     */
    public BinaryBepParseResult poll(BinaryBepEventSink sink, BooleanSupplier cancelRequested)
            throws IOException {
        if (finished) {
            throw new IllegalStateException("tail reader for " + file + " is already finished");
        }
        long size = Files.size(file);
        if (size < offset) {
            throw new SourceReplacedException(file, offset, size);
        }
        lastObservedSize = size;
        if (size == offset) {
            // Nothing new. Reported honestly as "complete so far" with no events;
            // whether the source is really complete is settled by finish().
            lastResult = new BinaryBepParseResult(BinaryBepParseOutcome.COMPLETE, 0, offset, offset,
                    0, OptionalLong.empty(), parser.bufferBytes(),
                    "no new bytes at offset " + offset);
            return lastResult;
        }
        BinaryBepParseResult result = parser.parseFile(file, offset, sink, cancelRequested);
        offset = result.resumeOffset();
        eventCount += result.eventCount();
        lastResult = result;
        return result;
    }

    /**
     * Final read, for use once the writer has exited. Consumes anything that
     * arrived since the last poll and freezes the reader.
     *
     * <p>The returned result's completeness is now authoritative: a partial
     * frame at this point is a genuinely truncated source, not a slow writer.
     */
    public BinaryBepParseResult finish(BinaryBepEventSink sink) throws IOException {
        BinaryBepParseResult result = poll(sink);
        finished = true;
        return result;
    }

    /**
     * Completeness of the source as currently known. Before {@link #finish} a
     * partial tail is {@link Completeness#UNKNOWN}, not {@code TRUNCATED} — the
     * writer may still be mid-frame, and calling that truncation would report a
     * healthy live capture as damaged.
     */
    public Completeness completeness() {
        if (lastResult == null) {
            return Completeness.UNKNOWN;
        }
        if (!finished && lastResult.outcome() == BinaryBepParseOutcome.TRUNCATED) {
            return Completeness.UNKNOWN;
        }
        return lastResult.completeness();
    }

    /** The file size seen by the most recent poll, for progress reporting. */
    public long lastObservedSize() {
        return lastObservedSize;
    }

    /** The most recent poll's result, or null before the first poll. */
    public BinaryBepParseResult lastResult() {
        return lastResult;
    }
}
