package com.holtherndon.bazelviz.ui.workspace;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import javax.swing.SwingUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Bounded, atomic persistence for the optional local workspace-discovery script. */
public final class WorkspaceDiscoveryScriptStore {

  /** Maximum UTF-8 encoded size of the discovery script. */
  public static final int MAX_SCRIPT_BYTES = 65_536;

  private static final Logger log = LoggerFactory.getLogger(WorkspaceDiscoveryScriptStore.class);
  private static final String INVALID_WARNING =
      "The workspace discovery script is invalid and was not loaded.";
  private static final String UNREADABLE_WARNING =
      "The workspace discovery script could not be read.";
  private static final String SAVE_WARNING =
      "The workspace discovery script could not be saved; the previous script is unchanged.";

  private final Path file;
  private final Replacer replacer;

  public WorkspaceDiscoveryScriptStore(Path settingsDirectory) {
    this(settingsDirectory, WorkspaceDiscoveryScriptStore::replace);
  }

  WorkspaceDiscoveryScriptStore(Path settingsDirectory, Replacer replacer) {
    file =
        Objects.requireNonNull(settingsDirectory, "settingsDirectory")
            .resolve("workspace-discovery");
    this.replacer = Objects.requireNonNull(replacer, "replacer");
  }

  /** The one persistent script path, for diagnostics and direct execution. */
  public Path file() {
    return file;
  }

  /** Loads the script, or an empty string when it is absent or unusable. */
  public String load() {
    return loadWithDiagnostics().script();
  }

  /** Loads the script without executing it. */
  public LoadResult loadWithDiagnostics() {
    requireBackgroundThread();
    if (Files.notExists(file, LinkOption.NOFOLLOW_LINKS)) {
      return new LoadResult("", Source.MISSING, List.of());
    }
    if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
      log.warn("workspace discovery setting at {} is not a regular file", file);
      return unusable(UNREADABLE_WARNING);
    }
    try {
      long size = Files.size(file);
      if (size > MAX_SCRIPT_BYTES) {
        throw new IllegalArgumentException(
            "workspace discovery script exceeds " + MAX_SCRIPT_BYTES + " bytes");
      }
      byte[] encoded = Files.readAllBytes(file);
      String script = decodeUtf8(encoded);
      String validation = validationError(script);
      if (validation != null) {
        throw new IllegalArgumentException(validation);
      }
      return new LoadResult(script, Source.STORED, List.of());
    } catch (CharacterCodingException | IllegalArgumentException invalid) {
      log.warn("workspace discovery setting at {} is invalid", file, invalid);
      return unusable(INVALID_WARNING);
    } catch (IOException | RuntimeException unreadable) {
      log.warn("workspace discovery setting at {} could not be read", file, unreadable);
      return unusable(UNREADABLE_WARNING);
    }
  }

  /** Saves the complete script. A failure leaves the previous file unchanged. */
  public boolean save(String script) {
    return saveWithDiagnostics(script).saved();
  }

  /** Saves the complete script and returns safe, user-facing diagnostics. */
  public SaveResult saveWithDiagnostics(String script) {
    requireBackgroundThread();
    Path temporary = null;
    try {
      byte[] encoded = encodeAndValidate(script);
      Files.createDirectories(file.getParent());
      temporary = Files.createTempFile(file.getParent(), ".workspace-discovery-", ".tmp");
      Files.write(temporary, encoded);
      makeOwnerExecutable(temporary);
      replacer.replace(temporary, file);
      temporary = null;
      return new SaveResult(true, List.of());
    } catch (IllegalArgumentException invalid) {
      log.warn("invalid workspace discovery script was not saved", invalid);
      return failed(invalid.getMessage());
    } catch (IOException | RuntimeException failure) {
      log.warn("workspace discovery setting could not be saved to {}", file, failure);
      return failed(SAVE_WARNING);
    } finally {
      if (temporary != null) {
        try {
          Files.deleteIfExists(temporary);
        } catch (IOException | RuntimeException cleanupFailure) {
          log.warn(
              "temporary workspace discovery setting could not be removed from {}",
              temporary,
              cleanupFailure);
        }
      }
    }
  }

  private static byte[] encodeAndValidate(String script) {
    String checked = Objects.requireNonNull(script, "script");
    if (checked.length() > MAX_SCRIPT_BYTES) {
      throw new IllegalArgumentException(
          "The workspace discovery script exceeds " + MAX_SCRIPT_BYTES + " bytes.");
    }
    byte[] encoded = checked.getBytes(StandardCharsets.UTF_8);
    if (encoded.length > MAX_SCRIPT_BYTES) {
      throw new IllegalArgumentException(
          "The workspace discovery script exceeds " + MAX_SCRIPT_BYTES + " bytes.");
    }
    String validation = validationError(checked);
    if (validation != null) {
      throw new IllegalArgumentException(validation);
    }
    return encoded;
  }

  private static String validationError(String script) {
    if (script.isEmpty()) {
      return null;
    }
    if (!script.startsWith("#!")) {
      return "A non-empty workspace discovery script must begin with a shebang (#!).";
    }
    int newline = script.indexOf('\n');
    int carriageReturn = script.indexOf('\r');
    int lineEnd;
    if (newline < 0) {
      lineEnd = carriageReturn < 0 ? script.length() : carriageReturn;
    } else if (carriageReturn < 0) {
      lineEnd = newline;
    } else {
      lineEnd = Math.min(newline, carriageReturn);
    }
    if (script.substring(2, lineEnd).isBlank()) {
      return "The workspace discovery script shebang must name an interpreter.";
    }
    if (script.indexOf('\0') >= 0) {
      return "The workspace discovery script cannot contain NUL.";
    }
    return null;
  }

  private static String decodeUtf8(byte[] encoded) throws CharacterCodingException {
    return StandardCharsets.UTF_8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(encoded))
        .toString();
  }

  private static void makeOwnerExecutable(Path path) throws IOException {
    try {
      Set<PosixFilePermission> permissions = EnumSet.copyOf(Files.getPosixFilePermissions(path));
      permissions.add(PosixFilePermission.OWNER_EXECUTE);
      Files.setPosixFilePermissions(path, permissions);
    } catch (UnsupportedOperationException unsupported) {
      if (!path.toFile().setExecutable(true, true)) {
        throw new IOException("the discovery script could not be made executable");
      }
    }
    if (!Files.isExecutable(path)) {
      throw new IOException("the discovery script is not executable");
    }
  }

  private static void replace(Path temporary, Path destination) throws IOException {
    try {
      Files.move(
          temporary,
          destination,
          StandardCopyOption.REPLACE_EXISTING,
          StandardCopyOption.ATOMIC_MOVE);
    } catch (AtomicMoveNotSupportedException unsupported) {
      Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  private static void requireBackgroundThread() {
    if (SwingUtilities.isEventDispatchThread()) {
      throw new IllegalStateException("workspace discovery settings I/O must not run on the EDT");
    }
  }

  private static LoadResult unusable(String diagnostic) {
    return new LoadResult("", Source.UNUSABLE, List.of(diagnostic));
  }

  private static SaveResult failed(String diagnostic) {
    return new SaveResult(false, List.of(diagnostic));
  }

  private static <T> List<T> freshImmutable(List<T> values) {
    return Collections.unmodifiableList(new ArrayList<>(Objects.requireNonNull(values, "values")));
  }

  @FunctionalInterface
  interface Replacer {
    void replace(Path temporary, Path destination) throws IOException;
  }

  /** Why a load produced its returned script. */
  public enum Source {
    MISSING,
    STORED,
    UNUSABLE
  }

  /** Immutable result of one load attempt. */
  public record LoadResult(String script, Source source, List<String> diagnostics) {

    public LoadResult {
      script = Objects.requireNonNull(script, "script");
      source = Objects.requireNonNull(source, "source");
      diagnostics = freshImmutable(diagnostics);
    }

    public boolean configured() {
      return source == Source.STORED && !script.isEmpty();
    }
  }

  /** Immutable result of one complete-script save attempt. */
  public record SaveResult(boolean saved, List<String> diagnostics) {

    public SaveResult {
      diagnostics = freshImmutable(diagnostics);
    }
  }
}
