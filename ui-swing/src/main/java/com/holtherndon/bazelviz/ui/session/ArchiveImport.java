package com.holtherndon.bazelviz.ui.session;

import com.holtherndon.bazelviz.format.portable.BvizFormatException;
import com.holtherndon.bazelviz.format.portable.BvizIndex;
import com.holtherndon.bazelviz.format.portable.BvizLimits;
import com.holtherndon.bazelviz.format.portable.BvizReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Brings a {@code .bviz} archive into the managed session library.
 *
 * <h2>Validate, extract, then adopt — in that order</h2>
 *
 * <p>The archive is validated end to end before a byte is written (see {@link BvizReader}),
 * extracted into a temporary directory beside its destination, and moved into place only once it is
 * whole. An extraction that failed halfway into the sessions root would leave a directory that
 * looks like a session and is not, which the next scan would list and the next open would fail on.
 *
 * <h2>An archive already here is not imported twice</h2>
 *
 * <p>A session's identity is its UUID, and the archive carries the one it was exported under.
 * Extracting it a second time under a different name would produce two directories claiming to be
 * the same session, with manifests that agree with each other and directory names that do not. So a
 * duplicate is refused, with the path of the one already present — which is what the user wanted to
 * look at anyway.
 */
public final class ArchiveImport {

  private ArchiveImport() {}

  /**
   * What an import produced.
   *
   * @param redacted the archive was exported with redaction, so it holds no raw capture and nothing
   *     in it can be re-derived from source bytes
   */
  public record Result(Path sessionRoot, BvizIndex index, boolean redacted, List<String> notes) {

    public Result {
      notes = List.copyOf(notes);
    }

    public String describe() {
      StringBuilder text =
          new StringBuilder("Imported ")
              .append(index.entries().size())
              .append(" files into ")
              .append(sessionRoot.getFileName())
              .append('.');
      if (redacted) {
        text.append(
            " This archive is redacted: it has no raw capture, so its"
                + " enrichments cannot be re-run and its database cannot be rebuilt.");
      }
      for (String note : notes) {
        text.append(' ').append(note);
      }
      return text.toString();
    }
  }

  /**
   * Validates and extracts {@code archive} into the sessions root.
   *
   * @throws BvizFormatException when the archive cannot be trusted, or when the session it holds is
   *     already in the library
   */
  static Result into(Path archive, Path sessionsRoot, BvizLimits limits) throws IOException {
    Objects.requireNonNull(archive, "archive");
    Objects.requireNonNull(sessionsRoot, "sessionsRoot");

    // Nothing is written by this call; it is the whole archive, checked.
    BvizReader.Validation validation = BvizReader.validate(archive, limits);
    return into(validation, sessionsRoot, limits);
  }

  /** Extracts a validation already produced by the process mutation coordinator. */
  static Result into(BvizReader.Validation validation, Path sessionsRoot, BvizLimits limits)
      throws IOException {
    Objects.requireNonNull(validation, "validation");
    Objects.requireNonNull(sessionsRoot, "sessionsRoot");
    Objects.requireNonNull(limits, "limits");
    String uuid = validation.index().sessionId();
    Path destination = sessionsRoot.resolve("session-" + uuid);
    if (Files.exists(destination)) {
      throw new BvizFormatException(
          "this session is already in the library, at "
              + destination
              + ". Open it from there rather than importing a second copy: two"
              + " directories claiming the same session would have manifests that"
              + " agree and names that do not.");
    }
    Files.createDirectories(sessionsRoot);
    // A unique staging directory is defence in depth around the process coordinator. A
    // caller that bypasses it still cannot delete another import's partially extracted data.
    // Keep the prefix outside "session-*" too, so a crash residue can never look like a
    // managed session to catalog reconciliation.
    Path staging = Files.createTempDirectory(sessionsRoot, ".incoming-session-" + uuid + "-");
    boolean ok = false;
    try {
      BvizReader.extract(validation.archive(), staging, limits);
      Files.move(staging, destination);
      ok = true;
    } finally {
      if (!ok) {
        deleteRecursively(staging);
      }
    }
    return new Result(
        destination, validation.index(), validation.isRedacted(), validation.warnings());
  }

  private static void deleteRecursively(Path directory) throws IOException {
    if (!Files.exists(directory)) {
      return;
    }
    try (var walk = Files.walk(directory)) {
      for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(path);
      }
    }
  }
}
