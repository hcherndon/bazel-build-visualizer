package com.holtherndon.bazelviz.ui.export;

import com.holtherndon.bazelviz.capture.file.export.BepStreamExport;
import com.holtherndon.bazelviz.core.redact.RedactionPolicy;
import com.holtherndon.bazelviz.core.redact.RedactionReport;
import com.holtherndon.bazelviz.core.redact.Redactor;
import com.holtherndon.bazelviz.format.portable.BvizWriter;
import com.holtherndon.bazelviz.format.session.ManagedSessionLayout;
import com.holtherndon.bazelviz.format.session.SessionManifest;
import com.holtherndon.bazelviz.format.session.SessionManifestCodec;
import com.holtherndon.bazelviz.format.session.SessionManifestRedaction;
import com.holtherndon.bazelviz.storage.export.TableExport;
import com.holtherndon.bazelviz.storage.redact.SessionRedaction;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

/**
 * Runs the exports, off the event thread, and shows what redaction did before anything leaves the
 * machine.
 *
 * <h2>The confirmation is not a courtesy</h2>
 *
 * <p>docs/privacy.md: an export "shows the user exactly what was redacted before anything is
 * written". Pattern matching finds what it was told to look for, so the honest offer is not
 * "everything sensitive was removed" but "here is what was removed, and here is how much of the
 * session it touched". That requires redaction to run <em>first</em>, into a temporary file, with
 * the archive built only after somebody has read the report and said yes.
 *
 * <h2>Redacted staging is isolated and short lived</h2>
 *
 * <p>The database copy briefly contains original pages before its rewrite completes. Each export
 * therefore gets a new owner-only directory outside the source session. Only the redacted manifest
 * and database survive to confirmation, both are made owner-read-only, and the directory is removed
 * before either completion callback runs.
 *
 * <h2>Path prefixes come from the session</h2>
 *
 * <p>A redactor that only masks {@code /Users/<name>} leaves the rest of an absolute path intact,
 * which is most of it. The workspace and output-base paths are in the session's own tables, so they
 * are read and mapped to {@code [workspace]} and {@code [output-base]} — which keeps the
 * informative part of every path and drops the identifying part.
 */
public final class ExportController {

  private static final FileAttribute<Set<PosixFilePermission>> OWNER_DIRECTORY =
      PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"));

  private static final Set<PosixFilePermission> OWNER_READ_ONLY =
      PosixFilePermissions.fromString("r--------");

  private final ExecutorService worker;
  private final Consumer<Runnable> onEventThread;
  private final Path scratchParent;

  public ExportController(ExecutorService worker, Consumer<Runnable> onEventThread) {
    this(worker, onEventThread, Path.of(System.getProperty("java.io.tmpdir")));
  }

  ExportController(ExecutorService worker, Consumer<Runnable> onEventThread, Path scratchParent) {
    this.worker = Objects.requireNonNull(worker, "worker");
    this.onEventThread = Objects.requireNonNull(onEventThread, "onEventThread");
    this.scratchParent = Objects.requireNonNull(scratchParent, "scratchParent");
  }

  /**
   * The two choices plan 22.2 asks to be offered, both off by default.
   *
   * @param omitEnvironmentValues plan 22.2's "optional omission of environment values while
   *     retaining names" — stronger than the pattern rules, and the right answer for a build whose
   *     environment holds something the patterns will not recognise
   * @param pseudonymiseLabels hides internal project structure at the cost of making the export
   *     nearly unreadable, so it is never a default
   */
  public record RedactionOptions(boolean omitEnvironmentValues, boolean pseudonymiseLabels) {

    public static RedactionOptions defaults() {
      return new RedactionOptions(false, false);
    }

    RedactionPolicy applyTo(RedactionPolicy policy) {
      RedactionPolicy result = policy;
      if (omitEnvironmentValues) {
        result = result.omittingEnvironmentValues();
      }
      if (pseudonymiseLabels) {
        result = result.redactingLabels();
      }
      return result;
    }
  }

  /** Shown the report, decides whether the export proceeds. Called on the UI thread. */
  @FunctionalInterface
  public interface Confirmer {
    boolean confirm(RedactionReport report);
  }

  /**
   * Writes a portable archive.
   *
   * @param redacted when true the session is redacted into a temporary copy, the report is
   *     confirmed, and the archive carries no raw capture
   */
  public void exportArchive(
      Path sessionRoot,
      Path target,
      boolean redacted,
      RedactionOptions redactionOptions,
      String appVersion,
      Confirmer confirmer,
      Consumer<BvizWriter.Result> onDone,
      Consumer<Throwable> onError) {
    worker.execute(
        () -> {
          Path scratch = null;
          BvizWriter.Result completed = null;
          Throwable failure = null;
          boolean declined = false;
          try {
            long createdMicros = nowMicros();
            BvizWriter.Options options;
            if (redacted) {
              Path trustedRoot = requireNoFollowDirectory(sessionRoot, "session");
              ManagedSessionLayout layout = ManagedSessionLayout.at(trustedRoot);
              Path sourceManifest =
                  requireContainedRegularFile(
                      trustedRoot, layout.manifestFile(), "session manifest");
              Path sourceDatabase =
                  requireContainedRegularFile(
                      trustedRoot, layout.databaseFile(), "session database");
              scratch = createOwnerOnlyTempDirectory(scratchParent, ".bbv-redacted-export-");
              Path stagedDatabase = scratch.resolve(ManagedSessionLayout.DATABASE_FILE_NAME);
              Redactor[] exportRedactor = new Redactor[1];
              SessionRedaction.copyRedacted(
                  sourceDatabase,
                  stagedDatabase,
                  copiedDatabase -> {
                    Redactor value =
                        new Redactor(redactionOptions.applyTo(policyFor(copiedDatabase)));
                    exportRedactor[0] = value;
                    return value;
                  });
              Redactor shared = Objects.requireNonNull(exportRedactor[0], "export redactor");
              SessionManifest source = readManifestNoFollow(sourceManifest);
              Path stagedManifest = scratch.resolve(ManagedSessionLayout.MANIFEST_FILE_NAME);
              SessionManifestCodec.standard()
                  .write(stagedManifest, SessionManifestRedaction.redact(source, shared));
              makeOwnerReadOnly(stagedManifest);
              makeOwnerReadOnly(stagedDatabase);
              if (!confirmOnEventThread(confirmer, shared.report())) {
                declined = true;
              } else {
                options =
                    BvizWriter.Options.redacted(
                        "redacted export",
                        scratch,
                        Map.of(
                            ManagedSessionLayout.MANIFEST_FILE_NAME,
                            stagedManifest,
                            ManagedSessionLayout.DATABASE_FILE_NAME,
                            stagedDatabase));
                BvizWriter.SpaceEstimate estimate =
                    BvizWriter.estimate(trustedRoot, target, options, appVersion, createdMicros);
                if (!estimate.fits()) {
                  throw new IOException(
                      "not enough room at " + target.getParent() + ": " + estimate.describe());
                }
                completed =
                    BvizWriter.write(trustedRoot, target, options, appVersion, createdMicros);
              }
            } else {
              options = BvizWriter.Options.complete("complete export");
              BvizWriter.SpaceEstimate estimate =
                  BvizWriter.estimate(sessionRoot, target, options, appVersion, createdMicros);
              if (!estimate.fits()) {
                throw new IOException(
                    "not enough room at " + target.getParent() + ": " + estimate.describe());
              }
              completed = BvizWriter.write(sessionRoot, target, options, appVersion, createdMicros);
            }
          } catch (Exception caught) {
            if (caught instanceof InterruptedException) {
              Thread.currentThread().interrupt();
            }
            failure = caught;
          }
          try {
            deleteTree(scratch);
          } catch (IOException | RuntimeException cleanupFailure) {
            if (failure != null) {
              failure.addSuppressed(cleanupFailure);
            } else {
              failure = cleanupFailure;
            }
          }
          if (failure != null) {
            Throwable reported = failure;
            onEventThread.accept(() -> onError.accept(reported));
          } else if (!declined) {
            BvizWriter.Result result = Objects.requireNonNull(completed, "completed export");
            onEventThread.accept(() -> onDone.accept(result));
          }
        });
  }

  /** Writes the captured stream back out as a binary BEP file. */
  public void exportBep(
      Path sessionRoot,
      Path target,
      Consumer<BepStreamExport.Result> onDone,
      Consumer<Throwable> onError) {
    worker.execute(
        () -> {
          try {
            BepStreamExport.Result result =
                BepStreamExport.write(ManagedSessionLayout.at(sessionRoot).rawDirectory(), target);
            onEventThread.accept(() -> onDone.accept(result));
          } catch (Exception failure) {
            onEventThread.accept(() -> onError.accept(failure));
          }
        });
  }

  /** Writes one table as CSV or JSON, redacted unless the caller says otherwise. */
  public void exportTable(
      Path sessionRoot,
      TableExport.Table table,
      TableExport.Format format,
      Path target,
      boolean redact,
      Consumer<TableExport.Result> onDone,
      Consumer<Throwable> onError) {
    worker.execute(
        () -> {
          Path database = ManagedSessionLayout.at(sessionRoot).databaseFile();
          try (Connection connection =
              DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath())) {
            Optional<Redactor> redactor =
                redact ? Optional.of(new Redactor(policyFor(database))) : Optional.empty();
            TableExport.Result result =
                TableExport.write(connection, table, format, target, redactor);
            onEventThread.accept(() -> onDone.accept(result));
          } catch (Exception failure) {
            onEventThread.accept(() -> onError.accept(failure));
          }
        });
  }

  /**
   * The export policy with this session's own paths mapped.
   *
   * <p>Read from the session rather than guessed: the workspace and output base are the two
   * prefixes that appear on nearly every path in a build, and mapping them is the difference
   * between an export whose paths are readable and one where every one of them is {@code
   * /Users/[user]/…}.
   */
  static RedactionPolicy policyFor(Path database) {
    RedactionPolicy policy = RedactionPolicy.forExport();
    if (!Files.isRegularFile(database)) {
      return policy;
    }
    List<String> prefixes = new ArrayList<>();
    try (Connection connection =
        DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath())) {
      prefixes.addAll(
          readPrefix(
              connection, "SELECT workspace_directory FROM build_invocation WHERE singleton = 1"));
      prefixes.addAll(
          readPrefix(
              connection, "SELECT working_directory FROM build_invocation WHERE singleton = 1"));
      prefixes.addAll(
          readPrefix(connection, "SELECT output_base FROM profile_metadata WHERE id = 1"));
    } catch (Exception unreadable) {
      // A session whose database will not open still exports; it simply
      // maps fewer prefixes, and the account-name masking still applies.
      return policy;
    }
    // Longest first, so a workspace under a home directory maps as the
    // workspace. The redactor sorts too; doing it here keeps the names
    // stable when two prefixes are the same string.
    prefixes.sort(Comparator.comparingInt(String::length).reversed());
    int index = 0;
    for (String prefix : prefixes) {
      String placeholder =
          index == prefixes.size() - 1 && prefixes.size() > 1
              ? "[output-base]"
              : (index == 0 ? "[workspace]" : "[path" + index + "]");
      policy = policy.withPathPrefix(prefix, placeholder);
      index++;
    }
    return policy;
  }

  private static List<String> readPrefix(Connection connection, String sql) {
    try (PreparedStatement statement = connection.prepareStatement(sql);
        ResultSet rows = statement.executeQuery()) {
      if (rows.next()) {
        String value = rows.getString(1);
        if (value != null && !value.isBlank()) {
          return List.of(value);
        }
      }
    } catch (Exception missing) {
      // The table may not exist in an older session; that is not an error.
      return List.of();
    }
    return List.of();
  }

  private boolean confirmOnEventThread(Confirmer confirmer, RedactionReport report)
      throws InterruptedException {
    boolean[] answer = {false};
    RuntimeException[] failure = {null};
    CountDownLatch decided = new CountDownLatch(1);
    onEventThread.accept(
        () -> {
          try {
            answer[0] = confirmer.confirm(report);
          } catch (RuntimeException callbackFailure) {
            failure[0] = callbackFailure;
          } finally {
            decided.countDown();
          }
        });
    decided.await();
    if (failure[0] != null) {
      throw failure[0];
    }
    return answer[0];
  }

  private static Path requireNoFollowDirectory(Path directory, String description)
      throws IOException {
    BasicFileAttributes attributes =
        Files.readAttributes(directory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    if (attributes.isSymbolicLink() || !attributes.isDirectory()) {
      throw new IOException(description + " is not a no-follow directory: " + directory);
    }
    return directory.toRealPath();
  }

  private static Path requireContainedRegularFile(Path root, Path file, String description)
      throws IOException {
    Path normalized = file.toAbsolutePath().normalize();
    BasicFileAttributes attributes =
        Files.readAttributes(normalized, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    if (attributes.isSymbolicLink() || !attributes.isRegularFile()) {
      throw new IOException(description + " is not a regular no-follow file: " + file);
    }
    Path real = normalized.toRealPath();
    if (!real.startsWith(root) || !real.equals(normalized)) {
      throw new IOException(description + " escapes or links outside the session: " + file);
    }
    return normalized;
  }

  private static SessionManifest readManifestNoFollow(Path manifest) throws IOException {
    BasicFileAttributes before =
        Files.readAttributes(manifest, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    SessionManifest value;
    try (var input =
            Files.newInputStream(manifest, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        var reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
      value = SessionManifestCodec.standard().readForPortableArchive(reader, manifest.toString());
    }
    BasicFileAttributes after =
        Files.readAttributes(manifest, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    if (!sameFile(before, after) || !manifest.equals(manifest.toRealPath())) {
      throw new IOException("session manifest changed while it was read: " + manifest);
    }
    return value;
  }

  private static boolean sameFile(BasicFileAttributes left, BasicFileAttributes right) {
    return !right.isSymbolicLink()
        && right.isRegularFile()
        && left.size() == right.size()
        && left.lastModifiedTime().equals(right.lastModifiedTime())
        && (left.fileKey() == null
            || right.fileKey() == null
            || left.fileKey().equals(right.fileKey()));
  }

  private static Path createOwnerOnlyTempDirectory(Path parent, String prefix) throws IOException {
    BasicFileAttributes attributes =
        Files.readAttributes(parent, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    if (attributes.isSymbolicLink() || !attributes.isDirectory()) {
      throw new IOException("export scratch parent is not a no-follow directory: " + parent);
    }
    try {
      return Files.createTempDirectory(parent, prefix, OWNER_DIRECTORY);
    } catch (UnsupportedOperationException unsupported) {
      return Files.createTempDirectory(parent, prefix);
    }
  }

  private static void makeOwnerReadOnly(Path file) throws IOException {
    try {
      Files.setPosixFilePermissions(file, OWNER_READ_ONLY);
    } catch (UnsupportedOperationException unsupported) {
      // The owner-only directory remains the access boundary on non-POSIX filesystems.
    }
  }

  private static void deleteTree(Path path) throws IOException {
    if (path == null || !Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
      return;
    }
    Files.walkFileTree(
        path,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
              throws IOException {
            Files.delete(file);
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult postVisitDirectory(Path directory, IOException failure)
              throws IOException {
            if (failure != null) {
              throw failure;
            }
            Files.delete(directory);
            return FileVisitResult.CONTINUE;
          }
        });
  }

  private static long nowMicros() {
    return System.currentTimeMillis() * 1_000L;
  }
}
