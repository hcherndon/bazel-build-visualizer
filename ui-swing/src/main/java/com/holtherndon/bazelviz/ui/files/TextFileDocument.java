package com.holtherndon.bazelviz.ui.files;

import com.holtherndon.bazelviz.runner.files.ExecutionFileSystem;
import com.holtherndon.bazelviz.runner.files.ExecutionPath;
import com.holtherndon.bazelviz.runner.files.FileContents;
import com.holtherndon.bazelviz.runner.files.FileVersion;
import com.holtherndon.bazelviz.runner.files.LocalExecutionFileSystem;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;

/** Bounded UTF-8 loading and conflict-aware atomic saving for the file editor. */
public final class TextFileDocument {

  /** Largest file the in-app editor reads or writes: 16 MiB. */
  public static final int MAX_FILE_BYTES = 16 * 1024 * 1024;

  private TextFileDocument() {}

  /** One loaded version of a file. */
  public record Loaded(Path path, String text, Stamp stamp) {
    public Loaded {
      Objects.requireNonNull(path, "path");
      Objects.requireNonNull(text, "text");
      Objects.requireNonNull(stamp, "stamp");
    }
  }

  /** One loaded version on any execution filesystem. */
  public record ExecutionLoaded(ExecutionPath path, String text, FileVersion version) {
    public ExecutionLoaded {
      Objects.requireNonNull(path, "path");
      Objects.requireNonNull(text, "text");
      Objects.requireNonNull(version, "version");
    }
  }

  /** Identity of the bytes read, used to refuse a stale save. */
  public record Stamp(long bytes, long modifiedMillis, String sha256) {}

  /** Reads one regular text file without allocating beyond the documented cap. */
  public static Loaded load(Path requested) throws IOException {
    Objects.requireNonNull(requested, "requested");
    LocalExecutionFileSystem files = new LocalExecutionFileSystem();
    ExecutionLoaded loaded = load(files, files.path(requested));
    Path path = files.localPath(loaded.path()).orElseThrow();
    return new Loaded(path, loaded.text(), stamp(loaded.version()));
  }

  /** Reads one regular text file from a local or remote execution. */
  public static ExecutionLoaded load(ExecutionFileSystem files, ExecutionPath requested)
      throws IOException {
    Objects.requireNonNull(files, "files");
    Objects.requireNonNull(requested, "requested");
    FileContents contents = files.read(requested, MAX_FILE_BYTES);
    byte[] bytes = contents.bytes();
    if (looksBinary(bytes)) {
      throw new IOException(
          "the file appears to be binary and cannot be shown as text: " + contents.path());
    }
    return new ExecutionLoaded(
        contents.path(), new String(bytes, StandardCharsets.UTF_8), contents.version());
  }

  /**
   * Saves edited UTF-8 text only if the file still contains the bytes that were loaded. The
   * replacement is atomic where the filesystem supports it.
   */
  public static Loaded save(Loaded baseline, String text) throws IOException {
    Objects.requireNonNull(baseline, "baseline");
    LocalExecutionFileSystem files = new LocalExecutionFileSystem();
    ExecutionPath path = files.path(baseline.path());
    ExecutionLoaded saved =
        save(files, new ExecutionLoaded(path, baseline.text(), version(baseline.stamp())), text);
    return new Loaded(
        files.localPath(saved.path()).orElseThrow(), saved.text(), stamp(saved.version()));
  }

  /** Conflict-aware UTF-8 save through the execution filesystem. */
  public static ExecutionLoaded save(
      ExecutionFileSystem files, ExecutionLoaded baseline, String text) throws IOException {
    Objects.requireNonNull(files, "files");
    Objects.requireNonNull(baseline, "baseline");
    Objects.requireNonNull(text, "text");
    byte[] replacement = text.getBytes(StandardCharsets.UTF_8);
    if (replacement.length > MAX_FILE_BYTES) {
      throw new IOException(
          "the file is "
              + replacement.length
              + " bytes; the in-app text editor limit is "
              + MAX_FILE_BYTES
              + " bytes: "
              + baseline.path());
    }
    FileContents saved =
        files.replaceAtomically(baseline.path(), baseline.version(), replacement, MAX_FILE_BYTES);
    byte[] stored = saved.bytes();
    if (!Arrays.equals(stored, replacement)) {
      throw new IOException("the filesystem did not store the requested bytes: " + baseline.path());
    }
    return new ExecutionLoaded(
        saved.path(), new String(stored, StandardCharsets.UTF_8), saved.version());
  }

  private static boolean looksBinary(byte[] bytes) {
    int checked = Math.min(bytes.length, 8 * 1024);
    for (int i = 0; i < checked; i++) {
      if (bytes[i] == 0) {
        return true;
      }
    }
    return false;
  }

  private static Stamp stamp(FileVersion version) {
    return new Stamp(version.bytes(), version.modifiedMillis(), version.sha256());
  }

  private static FileVersion version(Stamp stamp) {
    return new FileVersion(stamp.bytes(), stamp.modifiedMillis(), stamp.sha256());
  }
}
