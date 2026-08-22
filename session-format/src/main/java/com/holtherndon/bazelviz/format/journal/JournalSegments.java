package com.holtherndon.bazelviz.format.journal;

import com.holtherndon.bazelviz.core.journal.JournalFormat;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Naming and enumeration of journal segment files inside a {@code raw/}
 * directory. Segment file names are the only index recovery is allowed to
 * trust before checksums are verified, so parsing them is deliberately strict:
 * a file that does not match the pattern exactly is not a segment, and is left
 * alone rather than guessed at.
 */
public final class JournalSegments {

    private JournalSegments() {}

    /**
     * Matches {@code bes-000000.journal}. Six digits is the minimum written by
     * {@link JournalFormat#segmentFileName(int)}; more digits are accepted so a
     * session that somehow exceeds 999,999 segments is still enumerable in
     * numeric order rather than silently invisible.
     */
    private static final Pattern SEGMENT_NAME = Pattern.compile("bes-(\\d{6,})\\.journal");

    public static Path segmentFile(Path directory, int segmentIndex) {
        if (segmentIndex < 0) {
            throw new IllegalArgumentException("segmentIndex must be >= 0, got " + segmentIndex);
        }
        return directory.resolve(JournalFormat.segmentFileName(segmentIndex));
    }

    /** The segment index encoded in a file name, or empty when the name is not a segment. */
    public static OptionalInt parseSegmentIndex(String fileName) {
        Matcher matcher = SEGMENT_NAME.matcher(fileName);
        if (!matcher.matches()) {
            return OptionalInt.empty();
        }
        try {
            return OptionalInt.of(Integer.parseInt(matcher.group(1)));
        } catch (NumberFormatException overflow) {
            // A name with more digits than an int can hold is not a segment
            // this build wrote; treating it as one would corrupt the ordering.
            return OptionalInt.empty();
        }
    }

    /**
     * Segment indexes present in {@code directory}, ascending. An absent
     * directory yields an empty list rather than an error: a session that has
     * not journaled anything yet is a normal state, not a failure.
     */
    public static List<Integer> listSegmentIndexes(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        List<Integer> indexes = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
            for (Path entry : entries) {
                if (!Files.isRegularFile(entry)) {
                    continue;
                }
                parseSegmentIndex(entry.getFileName().toString()).ifPresent(indexes::add);
            }
        }
        indexes.sort(Integer::compareTo);
        return List.copyOf(indexes);
    }

    /** The highest segment index present, or empty when the journal has no segments. */
    public static OptionalInt highestSegmentIndex(Path directory) throws IOException {
        List<Integer> indexes = listSegmentIndexes(directory);
        return indexes.isEmpty()
                ? OptionalInt.empty()
                : OptionalInt.of(indexes.get(indexes.size() - 1));
    }

    /**
     * Segment indexes that are missing, e.g. {@code [0, 1, 3]} reports
     * {@code [2]} and {@code [2, 3]} reports {@code [0, 1]}. A gap means a
     * segment file was deleted or never landed; the caller decides what to do,
     * but it must never pass unnoticed because the journal would then be
     * silently short.
     *
     * <p>The scan starts at 0, not at the lowest index present. Every journal
     * begins at segment 0 — {@link JournalWriter#create} only ever creates that
     * index and refuses a directory that already holds segments, and nothing
     * ever deletes one — so a run starting above 0 is proof that the front of
     * the journal was lost, not evidence of a journal that legitimately starts
     * later. Anchoring the scan at the first surviving index made exactly that
     * loss invisible, and recovery then reported the mutilated journal as
     * complete.
     */
    public static List<Integer> missingSegmentIndexes(List<Integer> present) {
        if (present.isEmpty()) {
            return List.of();
        }
        List<Integer> missing = new ArrayList<>();
        for (int i = 0; i < present.get(present.size() - 1); i++) {
            if (!present.contains(i)) {
                missing.add(i);
            }
        }
        return List.copyOf(missing);
    }
}
