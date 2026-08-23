package com.holtherndon.bazelviz.format.portable;

import com.holtherndon.bazelviz.format.session.ManagedSessionLayout;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Writes a session directory into a portable {@code .bviz} archive (plan 10.4).
 *
 * <h2>Through a temporary file, then renamed</h2>
 *
 * <p>Plan 10.4: "export through a temporary file, then atomically rename" and
 * "verify checksums before declaring success". Both matter for the same
 * reason: an export interrupted halfway leaves a file that looks like an
 * archive, and a user who mails it discovers the problem at the other end. So
 * nothing appears at the target path until the bytes have been written,
 * re-read, and checked against the digests recorded while writing.
 *
 * <h2>Already-compressed files are not compressed again</h2>
 *
 * <p>Plan 10.4 asks for this, and the reason is CPU rather than size: a
 * multi-gigabyte zstd execution log or a journal of compressed payloads gains
 * nothing from deflate and costs minutes. Each file is sampled — 128 KB,
 * deflated, measured — and one that does not compress is written at
 * {@link Deflater#NO_COMPRESSION}.
 *
 * <p>That is deliberately <em>not</em> {@link ZipEntry#STORED}, which would be
 * the literal reading. A stored entry requires its size and CRC to be known
 * before the first byte is written, which means reading every large file twice.
 * A no-compression deflate entry costs about five bytes per 64 KB block —
 * 0.008% — and one pass. The rule's purpose is met and the cost is not paid.
 *
 * <h2>A redacted archive cannot carry the raw sources</h2>
 *
 * <p>The raw journal is the original bytes, faithfully (ADR-004) — secrets
 * included. Exporting it beside a redacted database would undo the redaction
 * completely, so {@link Options#redacted} forces the raw directory out and
 * {@link BvizIndex#includesRawSources} records that it is missing. An archive
 * cannot be both redacted and complete, and the honest response is to say which
 * one it is.
 */
public final class BvizWriter {

    private BvizWriter() {}

    /** The extension a portable session carries. */
    public static final String EXTENSION = ".bviz";

    /** Bytes sampled to decide whether a file is already compressed. */
    private static final int SAMPLE_BYTES = 128 * 1024;

    /** Above this ratio the sample did not compress and deflate is skipped. */
    private static final double INCOMPRESSIBLE_RATIO = 0.95;

    private static final int BUFFER_BYTES = 64 * 1024;

    /**
     * What to export.
     *
     * @param includeRawSources carry {@code raw/} — the whole capture, and as
     *     sensitive as the machine it ran on. Ignored when {@code redacted}.
     * @param redacted the contents went through the redaction engine; forces
     *     the raw sources out
     * @param replacements archive-relative path to a substitute file, which is
     *     how a redacted database and a redacted manifest reach the archive
     *     without this class knowing what redaction is
     */
    public record Options(
            boolean includeRawSources,
            boolean redacted,
            String note,
            Map<String, Path> replacements) {

        public Options {
            Objects.requireNonNull(note, "note");
            replacements = Map.copyOf(replacements);
        }

        /** Everything, including the raw bytes. As sensitive as the session. */
        public static Options complete(String note) {
            return new Options(true, false, note, Map.of());
        }

        /** A redacted export: no raw sources, substituted files for the rest. */
        public static Options redacted(String note, Map<String, Path> replacements) {
            return new Options(false, true, note, replacements);
        }

        boolean carriesRaw() {
            return includeRawSources && !redacted;
        }
    }

    /** What an export produced. */
    public record Result(Path archive, BvizIndex index, long archiveBytes, long sourceBytes) {

        public String describe() {
            return "Wrote " + index.entries().size() + " files, "
                    + sourceBytes + " bytes in, " + archiveBytes + " bytes out"
                    + (index.redacted()
                            ? ". Redacted: the raw capture is not in this archive, so it cannot"
                                    + " be re-derived from source bytes."
                            : ". Complete: this archive contains the raw capture and is as"
                                    + " sensitive as the machine it was taken on.");
        }
    }

    /**
     * What the export will need, before it starts (plan 10.4).
     *
     * @param sourceBytes the exact size of what will go in, which is also the
     *     upper bound on the archive — a bound rather than a guess, because
     *     compression can only help and an incompressible session cannot grow
     * @param freeBytes what the target's filesystem reports, or -1 when it
     *     would not say
     */
    public record SpaceEstimate(long sourceBytes, int entryCount, long freeBytes) {

        /** True when the target filesystem certainly has room for the worst case. */
        public boolean fits() {
            return freeBytes < 0 || freeBytes > sourceBytes;
        }

        public String describe() {
            if (freeBytes < 0) {
                return entryCount + " files, at most " + sourceBytes
                        + " bytes. Free space could not be determined for this location.";
            }
            return entryCount + " files, at most " + sourceBytes + " bytes, with " + freeBytes
                    + " bytes free" + (fits() ? "." : " — that is not enough.");
        }
    }

    /** Measures what an export would need without writing anything. */
    public static SpaceEstimate estimate(Path sessionRoot, Path target, Options options)
            throws IOException {
        List<Source> sources = collect(sessionRoot, options);
        long bytes = 0;
        for (Source source : sources) {
            bytes += Files.size(source.file());
        }
        long free = -1;
        try {
            Path location = target.toAbsolutePath().getParent();
            if (location != null && Files.exists(location)) {
                FileStore store = Files.getFileStore(location);
                free = store.getUsableSpace();
            }
        } catch (IOException unavailable) {
            // A filesystem that will not report usable space is not a reason to
            // refuse the export; it is a reason not to promise it will fit.
            free = -1;
        }
        return new SpaceEstimate(bytes, sources.size(), free);
    }

    /**
     * Writes the archive.
     *
     * @param target where the archive lands; a temporary file beside it is
     *     written first and renamed only after every checksum verifies
     */
    public static Result write(
            Path sessionRoot, Path target, Options options, String appVersion, long createdMicros)
            throws IOException {
        Objects.requireNonNull(sessionRoot, "sessionRoot");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(options, "options");
        List<Source> sources = collect(sessionRoot, options);
        if (sources.isEmpty()) {
            throw new BvizFormatException(
                    "there is nothing to export at " + sessionRoot
                            + ": no manifest, no database, no raw sources");
        }

        Path partial = target.resolveSibling(target.getFileName() + ".partial");
        Files.deleteIfExists(partial);
        Path parent = target.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        List<BvizIndex.Entry> entries = new ArrayList<>(sources.size());
        long sourceBytes = 0;
        boolean ok = false;
        try {
            try (OutputStream out = Files.newOutputStream(partial);
                    ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
                for (Source source : sources) {
                    entries.add(writeEntry(zip, source, createdMicros));
                    sourceBytes += Files.size(source.file());
                }
                BvizIndex index = new BvizIndex(
                        BvizIndex.FORMAT_VERSION, appVersion,
                        sessionIdOf(sessionRoot), createdMicros,
                        options.redacted(), options.carriesRaw(), options.note(), entries);
                zip.setLevel(Deflater.BEST_COMPRESSION);
                ZipEntry entry = new ZipEntry(BvizIndex.FILE_NAME);
                entry.setTime(createdMicros / 1_000);
                zip.putNextEntry(entry);
                zip.write(index.toJson().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }

            BvizIndex index = new BvizIndex(
                    BvizIndex.FORMAT_VERSION, appVersion, sessionIdOf(sessionRoot), createdMicros,
                    options.redacted(), options.carriesRaw(), options.note(), entries);
            // Plan 10.4: verify checksums before declaring success. Re-reading
            // what was just written is the only way to catch a truncated write,
            // a full disk that reported success, or a bit that flipped between
            // the buffer and the platter.
            BvizReader.verify(partial, index, BvizLimits.defaults());

            Files.move(partial, target,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            ok = true;
            return new Result(target, index, Files.size(target), sourceBytes);
        } finally {
            if (!ok) {
                Files.deleteIfExists(partial);
            }
        }
    }

    private static BvizIndex.Entry writeEntry(
            ZipOutputStream zip, Source source, long createdMicros) throws IOException {
        zip.setLevel(compressionLevelFor(source.file()));
        ZipEntry entry = new ZipEntry(source.path());
        // A fixed timestamp, so exporting the same session twice produces the
        // same bytes. The build makes the same choice for its own archives.
        entry.setTime(createdMicros / 1_000);
        zip.putNextEntry(entry);
        MessageDigest digest = sha256();
        long bytes = 0;
        byte[] buffer = new byte[BUFFER_BYTES];
        try (InputStream in = new DigestInputStream(
                Files.newInputStream(source.file()), digest)) {
            int read;
            while ((read = in.read(buffer)) >= 0) {
                zip.write(buffer, 0, read);
                bytes += read;
            }
        }
        zip.closeEntry();
        return new BvizIndex.Entry(source.path(), bytes, hex(digest.digest()));
    }

    /**
     * Deflate, unless a sample says the file is already compressed.
     *
     * <p>Measured rather than guessed from the extension: a {@code .bin}
     * execution log is zstd on one Bazel version and uncompressed on another,
     * and a name-based rule would be wrong on whichever it did not anticipate.
     */
    private static int compressionLevelFor(Path file) throws IOException {
        long size = Files.size(file);
        if (size < SAMPLE_BYTES) {
            return Deflater.BEST_SPEED;
        }
        byte[] sample = new byte[SAMPLE_BYTES];
        int read;
        try (InputStream in = Files.newInputStream(file)) {
            read = in.readNBytes(sample, 0, sample.length);
        }
        if (read <= 0) {
            return Deflater.BEST_SPEED;
        }
        Deflater deflater = new Deflater(Deflater.BEST_SPEED);
        try {
            deflater.setInput(sample, 0, read);
            deflater.finish();
            byte[] out = new byte[read + 64];
            int compressed = 0;
            while (!deflater.finished() && compressed < out.length) {
                int produced = deflater.deflate(out, compressed, out.length - compressed);
                if (produced == 0) {
                    break;
                }
                compressed += produced;
            }
            return (double) compressed / read > INCOMPRESSIBLE_RATIO
                    ? Deflater.NO_COMPRESSION
                    : Deflater.BEST_SPEED;
        } finally {
            deflater.end();
        }
    }

    /** One file to write, and the name it goes under. */
    private record Source(String path, Path file) {}

    /**
     * Every file that belongs in the archive, in a fixed order.
     *
     * <p>Sorted, so the same session exports to the same bytes. The lock
     * directory is never included: a lock is a statement about this machine's
     * running processes and means nothing anywhere else.
     */
    private static List<Source> collect(Path sessionRoot, Options options) throws IOException {
        Map<String, Path> found = new LinkedHashMap<>();
        if (!Files.isDirectory(sessionRoot)) {
            throw new BvizFormatException("not a session directory: " + sessionRoot);
        }
        try (var walk = Files.walk(sessionRoot)) {
            walk.filter(Files::isRegularFile).forEach(file -> {
                String relative = sessionRoot.relativize(file).toString().replace('\\', '/');
                if (!BvizPaths.isExportable(relative)) {
                    return;
                }
                if (relative.equals(BvizIndex.FILE_NAME)) {
                    // A session directory that already holds an archive.json —
                    // an extracted archive, re-exported — must not carry the old
                    // index into the new one.
                    return;
                }
                if (BvizPaths.isRawSource(relative) && !options.carriesRaw()) {
                    return;
                }
                found.put(relative, file);
            });
        }
        options.replacements().forEach((path, file) -> {
            if (BvizPaths.isExportable(path)) {
                found.put(path, file);
            }
        });
        List<Source> sources = new ArrayList<>(found.size());
        found.forEach((path, file) -> sources.add(new Source(path, file)));
        sources.sort((left, right) -> left.path().compareTo(right.path()));
        return List.copyOf(sources);
    }

    private static String sessionIdOf(Path sessionRoot) {
        return ManagedSessionLayout.sessionIdFromDirectoryName(sessionRoot)
                .map(Object::toString)
                .orElse(sessionRoot.getFileName() == null
                        ? "unknown" : sessionRoot.getFileName().toString());
    }

    static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            text.append(String.format(Locale.ROOT, "%02x", value));
        }
        return text.toString();
    }
}
