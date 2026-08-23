package com.holtherndon.bazelviz.format.portable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Reads a {@code .bviz} archive, refusing anything it cannot verify.
 *
 * <h2>Validate, then extract — never the other way round</h2>
 *
 * <p>Plan 24's Phase 9 exit criterion is "portable archives validate before
 * opening", and the order is the whole point. An extractor that writes as it
 * goes and checks afterwards has already written whatever it was going to write
 * by the time it notices. {@link #validate} decompresses every entry and
 * discards the bytes, so a bomb, a zip-slip name or a wrong digest is found
 * with nothing on disk; {@link #extract} runs the same checks again as it
 * writes, because between the two calls the file could have changed.
 *
 * <h2>The declared size is not evidence</h2>
 *
 * <p>A Zip entry's uncompressed size lives in a header the archive controls.
 * Every count here is of bytes actually produced by the decompressor, checked
 * against {@link BvizLimits} as they are produced, so an entry that claims one
 * kilobyte and delivers a terabyte stops at the limit rather than at the claim.
 *
 * <h2>The database is untrusted</h2>
 *
 * <p>Plan 22.4: "treat imported SQLite databases as untrusted; prefer
 * rebuilding from raw files unless the archive format and database schema pass
 * validation". This class does not open SQLite — that is another module's job —
 * but it reports what a caller needs to make that decision:
 * {@link Validation#includesRawSources()} says whether rebuilding is even
 * possible, and a caller that finds a database it cannot validate can rebuild
 * when it is, and must refuse when it is not.
 */
public final class BvizReader {

    private BvizReader() {}

    private static final int BUFFER_BYTES = 64 * 1024;

    /**
     * What an archive turned out to be.
     *
     * @param index the archive's own table of contents, already verified
     * @param includesRawSources whether the raw capture is present, which is
     *     what decides whether a caller may rebuild rather than trust
     * @param warnings things worth telling a user that are not refusals
     */
    public record Validation(
            Path archive,
            BvizIndex index,
            long expandedBytes,
            boolean includesRawSources,
            List<String> warnings) {

        public Validation {
            warnings = List.copyOf(warnings);
        }

        public boolean isRedacted() {
            return index.redacted();
        }

        public String describe() {
            StringBuilder text = new StringBuilder()
                    .append(index.entries().size()).append(" files, ")
                    .append(expandedBytes).append(" bytes, every checksum verified.");
            if (index.redacted()) {
                text.append(" This archive is redacted: it holds no raw capture, so nothing in it"
                        + " can be re-derived from source bytes.");
            }
            if (!includesRawSources && !index.redacted()) {
                text.append(" This archive has no raw capture, so its database cannot be"
                        + " rebuilt from source bytes.");
            }
            for (String warning : warnings) {
                text.append(' ').append(warning);
            }
            return text.toString();
        }
    }

    /**
     * Checks an archive end to end without writing anything.
     *
     * @throws BvizFormatException on the first thing that cannot be trusted
     */
    public static Validation validate(Path archive, BvizLimits limits) throws IOException {
        return read(archive, limits, null);
    }

    /**
     * Extracts into {@code destination}, which must be empty or absent.
     *
     * <p>Runs the same validation while writing rather than trusting the
     * earlier pass: the file on disk is not under this application's control
     * between the two calls.
     */
    public static Validation extract(Path archive, Path destination, BvizLimits limits)
            throws IOException {
        Objects.requireNonNull(destination, "destination");
        if (Files.exists(destination)) {
            try (var listing = Files.list(destination)) {
                if (listing.findAny().isPresent()) {
                    throw new BvizFormatException(
                            "refusing to extract into a directory that already holds files: "
                                    + destination);
                }
            }
        }
        Files.createDirectories(destination);
        return read(archive, limits, destination);
    }

    /**
     * Verifies an archive against an index the caller already has.
     *
     * <p>Used by the writer immediately after writing, where the index is in
     * memory and the question is only whether the bytes on disk match it.
     */
    static void verify(Path archive, BvizIndex expected, BvizLimits limits) throws IOException {
        Validation actual = validate(archive, limits);
        for (BvizIndex.Entry entry : expected.entries()) {
            BvizIndex.Entry written = actual.index().entry(entry.path())
                    .orElseThrow(() -> new BvizFormatException(
                            "the archive just written is missing " + entry.path()));
            if (!written.sha256().equals(entry.sha256()) || written.bytes() != entry.bytes()) {
                throw new BvizFormatException(
                        "the archive just written does not match what was read from disk at "
                                + entry.path() + "; the export was not completed");
            }
        }
    }

    private static Validation read(Path archive, BvizLimits limits, Path destination)
            throws IOException {
        Objects.requireNonNull(archive, "archive");
        Objects.requireNonNull(limits, "limits");
        if (!Files.isRegularFile(archive)) {
            throw new BvizFormatException("not a file: " + archive);
        }
        List<String> warnings = new ArrayList<>();
        try (ZipFile zip = new ZipFile(archive.toFile(), StandardCharsets.UTF_8)) {
            BvizIndex index = readIndex(zip);
            Set<String> seen = new HashSet<>();
            Set<String> indexed = new HashSet<>();
            for (BvizIndex.Entry entry : index.entries()) {
                if (!indexed.add(entry.path())) {
                    // Plan 22.4: "reject duplicate manifest entries". Two
                    // entries for one path means one of them is the file a
                    // reader gets and the other is the file it was checked
                    // against, and nothing says which.
                    throw new BvizFormatException(
                            "the index lists " + BvizPaths.describe(entry.path()) + " twice");
                }
            }

            long expanded = 0;
            int entries = 0;
            Enumeration<? extends ZipEntry> enumeration = zip.entries();
            while (enumeration.hasMoreElements()) {
                ZipEntry entry = enumeration.nextElement();
                if (entry.isDirectory()) {
                    BvizPaths.requireNoTraversal(entry.getName());
                    continue;
                }
                if (++entries > limits.maxEntries()) {
                    throw new BvizFormatException(
                            "the archive holds more than " + limits.maxEntries() + " entries");
                }
                String name = entry.getName();
                if (name.equals(BvizIndex.FILE_NAME)) {
                    continue;
                }
                BvizPaths.requireSafe(name);
                if (!seen.add(name)) {
                    throw new BvizFormatException(
                            "the archive holds " + BvizPaths.describe(name) + " twice");
                }
                BvizIndex.Entry declared = index.entry(name).orElseThrow(() ->
                        new BvizFormatException(
                                "the archive holds " + BvizPaths.describe(name)
                                        + ", which " + BvizIndex.FILE_NAME + " does not list"));
                long produced = copyAndCheck(
                        zip, entry, declared, limits, expanded, destination);
                expanded += produced;
            }
            for (BvizIndex.Entry declared : index.entries()) {
                if (!seen.contains(declared.path())) {
                    throw new BvizFormatException(
                            BvizIndex.FILE_NAME + " lists " + BvizPaths.describe(declared.path())
                                    + ", which the archive does not contain");
                }
            }
            if (index.redacted() && index.includesRawSources()) {
                throw new BvizFormatException(
                        "the archive claims to be both redacted and to carry the raw capture."
                                + " The raw capture is the unredacted bytes, so it cannot be"
                                + " both.");
            }
            boolean carriesRaw = seen.stream().anyMatch(BvizPaths::isRawSource);
            if (carriesRaw != index.includesRawSources()) {
                warnings.add("The index says the raw capture is "
                        + (index.includesRawSources() ? "present" : "absent")
                        + " and the archive says otherwise; the archive was believed.");
            }
            return new Validation(archive, index, expanded, carriesRaw, warnings);
        } catch (java.util.zip.ZipException malformed) {
            throw new BvizFormatException(
                    "this file is not a readable archive: " + malformed.getMessage(), malformed);
        }
    }

    private static BvizIndex readIndex(ZipFile zip) throws IOException {
        ZipEntry entry = zip.getEntry(BvizIndex.FILE_NAME);
        if (entry == null) {
            throw new BvizFormatException(
                    "this archive has no " + BvizIndex.FILE_NAME + " at its root, so there is"
                            + " nothing to validate it against. A .bviz archive written by this"
                            + " application always has one.");
        }
        // Bounded before parsing: the index is a few hundred kilobytes for a
        // very large session, and a reader that allocated whatever the entry
        // claimed would be the bomb the limits exist to stop.
        try (InputStream in = zip.getInputStream(entry)) {
            byte[] bytes = in.readNBytes(8 * 1024 * 1024);
            if (bytes.length == 8 * 1024 * 1024) {
                throw new BvizFormatException(
                        BvizIndex.FILE_NAME + " is larger than 8 MiB, which no real session"
                                + " produces");
            }
            return BvizIndex.fromJson(new String(bytes, StandardCharsets.UTF_8));
        }
    }

    /** Decompresses one entry, checking limits as bytes appear and the digest at the end. */
    private static long copyAndCheck(
            ZipFile zip,
            ZipEntry entry,
            BvizIndex.Entry declared,
            BvizLimits limits,
            long expandedSoFar,
            Path destination) throws IOException {
        MessageDigest digest = BvizWriter.sha256();
        long produced = 0;
        long compressed = Math.max(1, entry.getCompressedSize());
        byte[] buffer = new byte[BUFFER_BYTES];

        OutputStream out = null;
        Path target = null;
        if (destination != null) {
            target = resolveSafely(destination, entry.getName());
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            out = Files.newOutputStream(target);
        }
        try (InputStream in = new DigestInputStream(zip.getInputStream(entry), digest)) {
            int read;
            while ((read = in.read(buffer)) >= 0) {
                produced += read;
                if (produced > limits.maxEntryBytes()) {
                    throw new BvizFormatException(
                            BvizPaths.describe(entry.getName()) + " expands past "
                                    + limits.maxEntryBytes() + " bytes");
                }
                if (expandedSoFar + produced > limits.maxExpandedBytes()) {
                    throw new BvizFormatException(
                            "the archive expands past " + limits.maxExpandedBytes() + " bytes");
                }
                if (produced / compressed > limits.maxCompressionRatio()) {
                    throw new BvizFormatException(
                            BvizPaths.describe(entry.getName()) + " expands more than "
                                    + limits.maxCompressionRatio()
                                    + " times, which no session file does");
                }
                if (out != null) {
                    out.write(buffer, 0, read);
                }
            }
        } finally {
            if (out != null) {
                out.close();
            }
        }
        String actual = BvizWriter.hex(digest.digest());
        if (produced != declared.bytes() || !actual.equals(declared.sha256())) {
            // Deleted rather than left behind: a file that failed its checksum
            // is bytes of unknown provenance sitting in a directory the user is
            // about to open.
            if (target != null) {
                Files.deleteIfExists(target);
            }
            throw new BvizFormatException(
                    BvizPaths.describe(entry.getName()) + " does not match the checksum "
                            + BvizIndex.FILE_NAME + " records for it");
        }
        return produced;
    }

    /**
     * Resolves an entry name inside the destination, checked twice.
     *
     * <p>{@link BvizPaths} has already refused anything that is not a session
     * file, which closes zip-slip by allow-list. This is the belt to that
     * braces: whatever the name was, the resolved real path must still be
     * inside the destination.
     */
    private static Path resolveSafely(Path destination, String name) throws IOException {
        Path root = destination.toAbsolutePath().normalize();
        Path resolved = root.resolve(name).normalize();
        if (!resolved.startsWith(root)) {
            throw new BvizFormatException(
                    "entry " + BvizPaths.describe(name) + " resolves outside the destination");
        }
        return resolved;
    }
}
