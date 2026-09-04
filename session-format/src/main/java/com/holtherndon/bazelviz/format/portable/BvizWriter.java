package com.holtherndon.bazelviz.format.portable;

import com.holtherndon.bazelviz.format.session.SessionManifestCodec;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileStore;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Writes a session directory into a portable {@code .bviz} archive (plan 10.4).
 *
 * <h2>Through a temporary file, then renamed</h2>
 *
 * <p>Plan 10.4: "export through a temporary file, then atomically rename" and "verify checksums
 * before declaring success". Both matter for the same reason: an export interrupted halfway leaves
 * a file that looks like an archive, and a user who mails it discovers the problem at the other
 * end. So nothing appears at the target path until the bytes have been written, re-read, and
 * checked against the digests recorded while writing.
 *
 * <h2>Already-compressed files are not compressed again</h2>
 *
 * <p>Plan 10.4 asks for this, and the reason is CPU rather than size: a multi-gigabyte zstd
 * execution log or a journal of compressed payloads gains nothing from deflate and costs minutes.
 * Each file is sampled — 128 KB, deflated, measured — and one that does not compress is written at
 * {@link Deflater#NO_COMPRESSION}.
 *
 * <p>That is deliberately <em>not</em> {@link ZipEntry#STORED}, which would be the literal reading.
 * A stored entry requires its size and CRC to be known before the first byte is written, which
 * means reading every large file twice. A no-compression deflate entry costs about five bytes per
 * 64 KB block — 0.008% — and one pass. The rule's purpose is met and the cost is not paid.
 *
 * <h2>A redacted archive cannot carry the raw sources</h2>
 *
 * <p>The raw journal is the original bytes, faithfully (ADR-004) — secrets included. Exporting it
 * beside a redacted database would undo the redaction completely, so {@link Options#redacted}
 * forces the raw directory out and {@link BvizIndex#includesRawSources} records that it is missing.
 * An archive cannot be both redacted and complete, and the honest response is to say which one it
 * is.
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

  private static final Set<String> REDACTED_ENTRY_NAMES = Set.of("manifest.json", "session.sqlite");

  private static final FileAttribute<Set<PosixFilePermission>> OWNER_DIRECTORY =
      PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"));

  private static final Set<PosixFilePermission> OWNER_READ_ONLY =
      PosixFilePermissions.fromString("r--------");

  /**
   * What to export.
   *
   * @param includeRawSources carry {@code raw/} — the whole capture, and as sensitive as the
   *     machine it ran on. Ignored when {@code redacted}.
   * @param redacted the contents went through the redaction engine; forces the raw sources out
   * @param replacementRoot trusted root containing every replacement under its archive name
   * @param replacements archive-relative path to a substitute file
   */
  public record Options(
      boolean includeRawSources,
      boolean redacted,
      String note,
      Path replacementRoot,
      Map<String, Path> replacements) {

    public Options {
      Objects.requireNonNull(note, "note");
      replacements = Map.copyOf(replacements);
      if (redacted && replacementRoot == null) {
        throw new IllegalArgumentException("a redacted export needs a trusted replacement root");
      }
      if (!redacted && (replacementRoot != null || !replacements.isEmpty())) {
        throw new IllegalArgumentException("only a redacted export may substitute source files");
      }
    }

    /** Everything, including the raw bytes. As sensitive as the session. */
    public static Options complete(String note) {
      return new Options(true, false, note, null, Map.of());
    }

    /** A redacted export: no raw sources, substituted files for the rest. */
    public static Options redacted(
        String note, Path replacementRoot, Map<String, Path> replacements) {
      return new Options(false, true, note, replacementRoot, replacements);
    }

    boolean carriesRaw() {
      return includeRawSources && !redacted;
    }
  }

  /** What an export produced. */
  public record Result(Path archive, BvizIndex index, long archiveBytes, long sourceBytes) {

    public String describe() {
      return "Wrote "
          + index.entries().size()
          + " files, "
          + sourceBytes
          + " bytes in, "
          + archiveBytes
          + " bytes out"
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
   * @param sourceBytes exact bytes copied into immutable writer snapshots
   * @param archiveBytesUpperBound a saturating upper bound for the resulting ZIP
   * @param requiredBytes peak target-filesystem space for snapshots plus the archive
   * @param freeBytes what the target's filesystem reports, or -1 when it would not say
   */
  public record SpaceEstimate(
      long sourceBytes,
      long archiveBytesUpperBound,
      long requiredBytes,
      int entryCount,
      long freeBytes) {

    /** True when the target filesystem certainly has room for the worst case. */
    public boolean fits() {
      return requiredBytes != Long.MAX_VALUE && (freeBytes < 0 || freeBytes >= requiredBytes);
    }

    public String describe() {
      if (freeBytes < 0) {
        return entryCount
            + " files, at most "
            + archiveBytesUpperBound
            + " archive bytes and "
            + requiredBytes
            + " peak temporary bytes. Free space could not be determined for this location.";
      }
      return entryCount
          + " files, at most "
          + archiveBytesUpperBound
          + " archive bytes and "
          + requiredBytes
          + " peak temporary bytes, with "
          + freeBytes
          + " bytes free"
          + (fits() ? "." : " — that is not enough.");
    }
  }

  /** Measures what an export would need without writing anything. */
  public static SpaceEstimate estimate(
      Path sessionRoot, Path target, Options options, String appVersion, long createdMicros)
      throws IOException {
    return estimate(sessionRoot, target, options, appVersion, createdMicros, BvizLimits.defaults());
  }

  /** The same estimate with injected limits, for exact-boundary verification. */
  static SpaceEstimate estimate(
      Path sessionRoot,
      Path target,
      Options options,
      String appVersion,
      long createdMicros,
      BvizLimits limits)
      throws IOException {
    List<Source> sources = collect(sessionRoot, options, limits);
    return estimateFor(sources, target, options, appVersion, createdMicros, limits);
  }

  /**
   * Writes the archive.
   *
   * @param target where the archive lands; a temporary file beside it is written first and renamed
   *     only after every checksum verifies
   */
  public static Result write(
      Path sessionRoot, Path target, Options options, String appVersion, long createdMicros)
      throws IOException {
    return write(sessionRoot, target, options, appVersion, createdMicros, BvizLimits.defaults());
  }

  /** The same writer with injected archive limits, for exact-boundary verification. */
  static Result write(
      Path sessionRoot,
      Path target,
      Options options,
      String appVersion,
      long createdMicros,
      BvizLimits limits)
      throws IOException {
    Objects.requireNonNull(sessionRoot, "sessionRoot");
    Objects.requireNonNull(target, "target");
    Objects.requireNonNull(options, "options");
    Objects.requireNonNull(appVersion, "appVersion");
    Objects.requireNonNull(limits, "limits");
    List<Source> sources = collect(sessionRoot, options, limits);
    if (sources.isEmpty()) {
      throw new BvizFormatException(
          "there is nothing to export at "
              + sessionRoot
              + ": no manifest, no database, no raw sources");
    }
    Path parent = target.toAbsolutePath().getParent();
    if (parent == null) {
      throw new BvizFormatException("an archive target needs a parent directory: " + target);
    }
    // Entry, name, replacement and expanded-size refusals happen before
    // anything is created beside the requested target.
    Files.createDirectories(parent);
    SpaceEstimate beforeScratch =
        estimateFor(sources, target, options, appVersion, createdMicros, limits);
    if (!beforeScratch.fits()) {
      throw new IOException("not enough room at " + parent + ": " + beforeScratch.describe());
    }
    Path scratch = null;
    Throwable operationFailure = null;
    try {
      scratch = createOwnerOnlyTempDirectory(parent, ".bviz-export-");
      sources = snapshotSources(sources, scratch);
      Source manifest = manifestSource(sources, sessionRoot);
      String sessionId = sessionIdOf(manifest.file(), sessionRoot);
      List<BvizIndex.Entry> entries = new ArrayList<>(sources.size());
      long sourceBytes = 0;
      Path partial = scratch.resolve("archive.partial");

      try (OutputStream out =
              Files.newOutputStream(
                  partial, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
          ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
        for (Source source : sources) {
          entries.add(writeEntry(zip, source, createdMicros));
          sourceBytes = saturatingAdd(sourceBytes, source.bytes());
        }
        BvizIndex index =
            new BvizIndex(
                BvizIndex.FORMAT_VERSION,
                appVersion,
                sessionId,
                createdMicros,
                options.redacted(),
                options.carriesRaw(),
                options.note(),
                entries);
        zip.setLevel(Deflater.BEST_COMPRESSION);
        ZipEntry entry = new ZipEntry(BvizIndex.FILE_NAME);
        entry.setTime(createdMicros / 1_000);
        zip.putNextEntry(entry);
        zip.write(index.toJson().getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
      }

      BvizIndex index =
          new BvizIndex(
              BvizIndex.FORMAT_VERSION,
              appVersion,
              sessionId,
              createdMicros,
              options.redacted(),
              options.carriesRaw(),
              options.note(),
              entries);
      // Plan 10.4: verify checksums before declaring success. Re-reading
      // what was just written is the only way to catch a truncated write,
      // a full disk that reported success, or a bit that flipped between
      // the buffer and the platter.
      BvizReader.verify(partial, index, limits);

      // No source snapshot survives publication. If deletion fails, the old target remains.
      deleteTree(scratch.resolve("inputs"));
      long archiveBytes = Files.size(partial);
      Result result = new Result(target, index, archiveBytes, sourceBytes);
      Files.move(
          partial, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      return result;
    } catch (IOException | RuntimeException failure) {
      operationFailure = failure;
      throw failure;
    } finally {
      if (scratch != null) {
        try {
          deleteTree(scratch);
        } catch (IOException | RuntimeException cleanupFailure) {
          if (operationFailure != null) {
            operationFailure.addSuppressed(cleanupFailure);
          } else if (cleanupFailure instanceof IOException io) {
            throw io;
          } else {
            throw cleanupFailure;
          }
        }
      }
    }
  }

  private static BvizIndex.Entry writeEntry(ZipOutputStream zip, Source source, long createdMicros)
      throws IOException {
    zip.setLevel(compressionLevelFor(source.file()));
    ZipEntry entry = new ZipEntry(source.path());
    // A fixed timestamp, so exporting the same session twice produces the
    // same bytes. The build makes the same choice for its own archives.
    entry.setTime(createdMicros / 1_000);
    zip.putNextEntry(entry);
    MessageDigest digest = sha256();
    long bytes = 0;
    byte[] buffer = new byte[BUFFER_BYTES];
    try (InputStream in = new DigestInputStream(Files.newInputStream(source.file()), digest)) {
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
   * <p>Measured rather than guessed from the extension: a {@code .bin} execution log is zstd on one
   * Bazel version and uncompressed on another, and a name-based rule would be wrong on whichever it
   * did not anticipate.
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

  /** One observed file to snapshot, and the name it goes under. */
  private record Source(
      String path, Path file, long bytes, Object fileKey, FileTime modifiedTime) {}

  /**
   * Every file that belongs in the archive, in a fixed order.
   *
   * <p>Sorted, so the same session exports to the same bytes. The lock directory is never included:
   * a lock is a statement about this machine's running processes and means nothing anywhere else.
   */
  private static List<Source> collect(Path sessionRoot, Options options, BvizLimits limits)
      throws IOException {
    Objects.requireNonNull(options, "options");
    Objects.requireNonNull(limits, "limits");
    Path root = requireDirectoryWithoutLinks(sessionRoot, "session");
    Map<String, Source> found = new LinkedHashMap<>();
    if (options.redacted()) {
      collectRedactedReplacements(options, found, limits);
    } else {
      collectCompleteSession(root, options, found, limits);
    }
    int entryCount = found.size() + 1;
    if (entryCount > limits.maxEntries()) {
      throw new BvizFormatException(
          "the export has "
              + entryCount
              + " entries including "
              + BvizIndex.FILE_NAME
              + "; the limit is "
              + limits.maxEntries());
    }
    List<Source> sources = new ArrayList<>(found.values());
    sources.sort((left, right) -> left.path().compareTo(right.path()));
    return List.copyOf(sources);
  }

  private static void collectCompleteSession(
      Path root, Options options, Map<String, Source> found, BvizLimits limits) throws IOException {
    Files.walkFileTree(
        root,
        EnumSet.noneOf(FileVisitOption.class),
        Integer.MAX_VALUE,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
              throws IOException {
            if (attributes.isSymbolicLink()) {
              throw new BvizFormatException(
                  "a session export cannot follow a symbolic link: " + file);
            }
            if (!attributes.isRegularFile()) {
              return FileVisitResult.CONTINUE;
            }
            requireContained(root, file, "session source");
            String relative = archiveName(root.relativize(file));
            if (!BvizPaths.isExportable(relative)) {
              return FileVisitResult.CONTINUE;
            }
            BvizPaths.requireSafe(relative);
            if (relative.equals(BvizIndex.FILE_NAME)) {
              // An extracted archive can contain its old generated index.
              return FileVisitResult.CONTINUE;
            }
            if (BvizPaths.isRawSource(relative) && !options.carriesRaw()) {
              return FileVisitResult.CONTINUE;
            }
            requireEntryCapacity(found.size(), limits);
            found.put(relative, source(relative, file, attributes));
            return FileVisitResult.CONTINUE;
          }
        });
  }

  private static void collectRedactedReplacements(
      Options options, Map<String, Source> found, BvizLimits limits) throws IOException {
    if (!options.replacements().keySet().equals(REDACTED_ENTRY_NAMES)) {
      throw new BvizFormatException(
          "a redacted archive must stage exactly manifest.json and session.sqlite");
    }
    Path suppliedRoot = options.replacementRoot().toAbsolutePath().normalize();
    Path replacementRoot =
        requireDirectoryWithoutLinks(options.replacementRoot(), "replacement scratch");
    for (Map.Entry<String, Path> replacement : options.replacements().entrySet()) {
      String name = replacement.getKey();
      BvizPaths.requireSafe(name);
      if (!REDACTED_ENTRY_NAMES.contains(name)) {
        throw new BvizFormatException("invalid redacted replacement name: " + name);
      }
      Path expected = suppliedRoot.resolve(name).normalize();
      Path supplied = replacement.getValue().toAbsolutePath().normalize();
      if (!supplied.equals(expected)) {
        throw new BvizFormatException(
            "replacement source must be staged under its archive name: " + name);
      }
      BasicFileAttributes attributes =
          Files.readAttributes(supplied, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      if (attributes.isSymbolicLink() || !attributes.isRegularFile()) {
        throw new BvizFormatException(
            "replacement source is not a regular no-follow file: " + name);
      }
      Path real = supplied.toRealPath();
      if (!real.equals(replacementRoot.resolve(name))) {
        throw new BvizFormatException(
            "replacement source escapes or links within its trusted root: " + name);
      }
      requireEntryCapacity(found.size(), limits);
      found.put(name, source(name, real, attributes));
    }
  }

  private static void requireEntryCapacity(int currentSourceEntries, BvizLimits limits)
      throws BvizFormatException {
    if (currentSourceEntries >= limits.maxEntries() - 1L) {
      throw new BvizFormatException(
          "the export exceeds the "
              + limits.maxEntries()
              + " entry limit including "
              + BvizIndex.FILE_NAME);
    }
  }

  private static Source source(String path, Path file, BasicFileAttributes attributes) {
    return new Source(
        path,
        file.toAbsolutePath().normalize(),
        attributes.size(),
        attributes.fileKey(),
        attributes.lastModifiedTime());
  }

  private static Path requireDirectoryWithoutLinks(Path directory, String description)
      throws IOException {
    Objects.requireNonNull(directory, "directory");
    BasicFileAttributes attributes =
        Files.readAttributes(directory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    if (attributes.isSymbolicLink() || !attributes.isDirectory()) {
      throw new BvizFormatException("not a no-follow " + description + " directory: " + directory);
    }
    return directory.toRealPath();
  }

  private static String archiveName(Path relative) {
    StringBuilder name = new StringBuilder();
    for (Path part : relative) {
      if (!name.isEmpty()) {
        name.append('/');
      }
      name.append(part);
    }
    return name.toString();
  }

  private static void requireContained(Path root, Path candidate, String description)
      throws BvizFormatException {
    Path normalizedRoot = root.toAbsolutePath().normalize();
    Path normalizedCandidate = candidate.toAbsolutePath().normalize();
    if (normalizedCandidate.equals(normalizedRoot)
        || !normalizedCandidate.startsWith(normalizedRoot)) {
      throw new BvizFormatException(description + " escapes its trusted root: " + candidate);
    }
  }

  private static void ensureRealPath(Path file, Path root, String description) throws IOException {
    Path real = file.toRealPath();
    requireContained(root, real, description);
    if (!real.equals(file.toAbsolutePath().normalize())) {
      throw new BvizFormatException(description + " passes through a symbolic link: " + file);
    }
  }

  private static List<Source> snapshotSources(List<Source> sources, Path scratch)
      throws IOException {
    Path inputDirectory = createOwnerOnlyDirectory(scratch.resolve("inputs"));
    List<Source> snapshots = new ArrayList<>(sources.size());
    for (int index = 0; index < sources.size(); index++) {
      Source source = sources.get(index);
      ensureRealPath(source.file(), source.file().getParent(), "export source");
      BasicFileAttributes before =
          Files.readAttributes(source.file(), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      if (!sameObservedFile(source, before)) {
        throw new BvizFormatException(
            "an export source changed before it could be snapshotted: " + source.path());
      }
      Path snapshot = inputDirectory.resolve(String.format(Locale.ROOT, "%08d", index));
      createOwnerOnlyFile(snapshot);
      copyExactly(source, snapshot);
      BasicFileAttributes after =
          Files.readAttributes(source.file(), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      ensureRealPath(source.file(), source.file().getParent(), "export source");
      if (!sameObservedFile(source, after)) {
        throw new BvizFormatException(
            "an export source changed while it was snapshotted: " + source.path());
      }
      makeOwnerReadOnly(snapshot);
      BasicFileAttributes snap =
          Files.readAttributes(snapshot, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      snapshots.add(source(source.path(), snapshot, snap));
    }
    return List.copyOf(snapshots);
  }

  private static void copyExactly(Source source, Path snapshot) throws IOException {
    byte[] buffer = new byte[BUFFER_BYTES];
    long remaining = source.bytes();
    try (InputStream input =
            Files.newInputStream(
                source.file(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        OutputStream output = Files.newOutputStream(snapshot, StandardOpenOption.WRITE)) {
      while (remaining > 0) {
        int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
        if (read < 0) {
          throw new BvizFormatException(
              "an export source shrank while it was snapshotted: " + source.path());
        }
        if (read == 0) {
          continue;
        }
        output.write(buffer, 0, read);
        remaining -= read;
      }
      if (input.read() >= 0) {
        throw new BvizFormatException(
            "an export source grew while it was snapshotted: " + source.path());
      }
    }
  }

  private static boolean sameObservedFile(Source source, BasicFileAttributes attributes) {
    if (attributes.isSymbolicLink()
        || !attributes.isRegularFile()
        || attributes.size() != source.bytes()
        || !attributes.lastModifiedTime().equals(source.modifiedTime())) {
      return false;
    }
    return source.fileKey() == null
        || attributes.fileKey() == null
        || source.fileKey().equals(attributes.fileKey());
  }

  private static SpaceEstimate estimateFor(
      List<Source> sources,
      Path target,
      Options options,
      String appVersion,
      long createdMicros,
      BvizLimits limits)
      throws IOException {
    long sourceBytes = 0;
    List<BvizIndex.Entry> placeholderEntries = new ArrayList<>(sources.size());
    for (Source source : sources) {
      if (source.bytes() > limits.maxEntryBytes()) {
        throw new BvizFormatException(
            "archive entry exceeds the expanded byte limit: " + source.path());
      }
      sourceBytes = saturatingAdd(sourceBytes, source.bytes());
      placeholderEntries.add(new BvizIndex.Entry(source.path(), source.bytes(), "0".repeat(64)));
    }
    BvizIndex placeholder =
        new BvizIndex(
            BvizIndex.FORMAT_VERSION,
            appVersion,
            "00000000-0000-0000-0000-000000000000",
            createdMicros,
            options.redacted(),
            options.carriesRaw(),
            options.note(),
            placeholderEntries);
    long indexBytes = placeholder.toJson().getBytes(StandardCharsets.UTF_8).length;
    if (indexBytes > limits.maxEntryBytes()
        || saturatingAdd(sourceBytes, indexBytes) > limits.maxExpandedBytes()) {
      throw new BvizFormatException("the export exceeds the expanded archive byte limit");
    }
    long archiveBytes = zipEndRecordsUpperBound();
    for (Source source : sources) {
      archiveBytes =
          saturatingAdd(
              archiveBytes,
              conservativeZipEntryBytes(
                  source.bytes(), source.path().getBytes(StandardCharsets.UTF_8).length));
    }
    archiveBytes =
        saturatingAdd(
            archiveBytes,
            conservativeZipEntryBytes(
                indexBytes, BvizIndex.FILE_NAME.getBytes(StandardCharsets.UTF_8).length));
    long requiredBytes = saturatingAdd(sourceBytes, archiveBytes);
    return new SpaceEstimate(
        sourceBytes, archiveBytes, requiredBytes, sources.size() + 1, usableSpace(target));
  }

  /** Saturating worst-case bytes for one DEFLATED ZIP64 entry and both headers. */
  static long conservativeZipEntryBytes(long uncompressedBytes, int nameBytes) {
    if (uncompressedBytes < 0 || nameBytes < 0) {
      throw new IllegalArgumentException("ZIP sizes cannot be negative");
    }
    // Twice the input plus a fixed margin is deliberately looser than the
    // maximum output of the JDK's Deflater, including empty streams.
    long compressed = saturatingAdd(saturatingMultiply(uncompressedBytes, 2), 1_024);
    long localHeader = saturatingAdd(30 + 64, nameBytes);
    long descriptor = 24;
    long centralHeader = saturatingAdd(46 + 64, nameBytes);
    return saturatingAdd(
        saturatingAdd(compressed, localHeader), saturatingAdd(descriptor, centralHeader));
  }

  private static long zipEndRecordsUpperBound() {
    return 22L + 56L + 20L;
  }

  static long saturatingAdd(long left, long right) {
    if (left < 0 || right < 0 || Long.MAX_VALUE - left < right) {
      return Long.MAX_VALUE;
    }
    return left + right;
  }

  private static long saturatingMultiply(long value, long multiplier) {
    if (value < 0 || multiplier < 0 || (value != 0 && multiplier > Long.MAX_VALUE / value)) {
      return Long.MAX_VALUE;
    }
    return value * multiplier;
  }

  private static long usableSpace(Path target) {
    try {
      Path location = target.toAbsolutePath().getParent();
      if (location != null && Files.exists(location)) {
        FileStore store = Files.getFileStore(location);
        return store.getUsableSpace();
      }
    } catch (IOException unavailable) {
      // Unknown is honest and lets the actual write report a real failure.
    }
    return -1;
  }

  static Path createOwnerOnlyTempDirectory(Path parent, String prefix) throws IOException {
    try {
      return Files.createTempDirectory(parent, prefix, OWNER_DIRECTORY);
    } catch (UnsupportedOperationException unsupported) {
      return Files.createTempDirectory(parent, prefix);
    }
  }

  private static Path createOwnerOnlyDirectory(Path directory) throws IOException {
    try {
      return Files.createDirectory(directory, OWNER_DIRECTORY);
    } catch (UnsupportedOperationException unsupported) {
      return Files.createDirectory(directory);
    }
  }

  private static void createOwnerOnlyFile(Path file) throws IOException {
    try {
      Files.createFile(
          file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
    } catch (UnsupportedOperationException unsupported) {
      Files.createFile(file);
    }
  }

  private static void makeOwnerReadOnly(Path file) throws IOException {
    try {
      Files.setPosixFilePermissions(file, OWNER_READ_ONLY);
    } catch (UnsupportedOperationException unsupported) {
      // Owner-only scratch still protects the file where POSIX modes do not exist.
    }
  }

  static void deleteTree(Path root) throws IOException {
    if (root == null || !Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
      return;
    }
    Files.walkFileTree(
        root,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
              throws IOException {
            Files.deleteIfExists(file);
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult postVisitDirectory(Path directory, IOException failure)
              throws IOException {
            if (failure != null) {
              throw failure;
            }
            Files.deleteIfExists(directory);
            return FileVisitResult.CONTINUE;
          }
        });
  }

  private static Source manifestSource(List<Source> sources, Path sessionRoot)
      throws BvizFormatException {
    return sources.stream()
        .filter(source -> source.path().equals("manifest.json"))
        .findFirst()
        .orElseThrow(
            () ->
                new BvizFormatException(
                    "cannot export " + sessionRoot + ": it has no exportable manifest.json"));
  }

  private static String sessionIdOf(Path manifest, Path sessionRoot) throws BvizFormatException {
    try {
      return SessionManifestCodec.standard()
          .readForPortableArchive(manifest)
          .sessionId()
          .toString();
    } catch (IOException malformed) {
      throw new BvizFormatException(
          "cannot export "
              + sessionRoot
              + ": the manifest source to be included is invalid or lacks a canonical sessionId",
          malformed);
    }
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
