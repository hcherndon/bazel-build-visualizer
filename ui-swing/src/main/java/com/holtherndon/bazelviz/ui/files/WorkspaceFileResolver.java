package com.holtherndon.bazelviz.ui.files;

import com.holtherndon.bazelviz.runner.files.ExecutionFileSystem;
import com.holtherndon.bazelviz.runner.files.ExecutionPath;
import com.holtherndon.bazelviz.runner.files.FileMetadata;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/** Resolves build labels, BEP file URIs, and action paths on one execution host. */
public final class WorkspaceFileResolver {

  private WorkspaceFileResolver() {}

  /**
   * Finds the BUILD file that owns {@code label}.
   *
   * <p>Bazel gives {@code BUILD.bazel} precedence over {@code BUILD}; this method does the same.
   * Only main-repository labels can be resolved from a main workspace path. Package traversal is
   * rejected before touching the filesystem, and the resolved real file must remain beneath the
   * real workspace root.
   */
  public static Path buildFile(Path workspaceRoot, String label) throws IOException {
    Objects.requireNonNull(workspaceRoot, "workspaceRoot");
    WorkspaceFileAccess access =
        WorkspaceFileAccess.local(Optional.of(workspaceRoot), Optional.empty());
    return requireLocal(access, buildFile(access, label));
  }

  /** Transport-neutral BUILD-file lookup with Bazel's BUILD.bazel precedence. */
  public static ExecutionPath buildFile(WorkspaceFileAccess access, String label)
      throws IOException {
    Objects.requireNonNull(access, "access");
    String packagePath = packagePath(label);
    ExecutionFileSystem files = access.files();
    ExecutionPath root =
        access
            .workspaceRoot()
            .or(() -> access.workingDirectory())
            .orElseThrow(
                () -> new IOException("this execution did not provide a Bazel workspace path"));
    ExecutionPath realRoot = files.canonicalize(root);
    ExecutionPath packageDirectory =
        packagePath.isEmpty() ? realRoot : files.resolve(realRoot, packagePath);
    for (String name : new String[] {"BUILD.bazel", "BUILD"}) {
      ExecutionPath candidate = files.resolve(packageDirectory, name);
      FileMetadata metadata = files.stat(candidate);
      if (metadata.state() == FileMetadata.State.INACCESSIBLE
          || metadata.state() == FileMetadata.State.UNAVAILABLE) {
        throw new IOException(
            "could not inspect "
                + candidate
                + ": "
                + metadata.detail().orElse(metadata.state().name().toLowerCase()));
      }
      if (metadata.state() == FileMetadata.State.PRESENT) {
        ExecutionPath real = files.canonicalize(candidate);
        FileMetadata resolved = files.stat(real);
        if (!files.isWithin(realRoot, real)) {
          throw new IOException("the BUILD file resolves outside the recorded workspace: " + real);
        }
        if (resolved.isRegularFile()) {
          return real;
        }
      }
    }
    throw new IOException(
        "no BUILD.bazel or BUILD file exists for " + label + " under " + packageDirectory);
  }

  /** Resolves one inspector link. All filesystem work happens in this call. */
  public static Path linkedFile(
      FileLink link, Optional<Path> workspaceRoot, Optional<Path> workingDirectory)
      throws IOException {
    WorkspaceFileAccess access = WorkspaceFileAccess.local(workspaceRoot, workingDirectory);
    return requireLocal(access, linkedFile(link, access));
  }

  /** Resolves one inspector link through its execution filesystem. */
  public static ExecutionPath linkedFile(FileLink link, WorkspaceFileAccess access)
      throws IOException {
    ExecutionPath candidate = pathCandidate(link, access);
    FileMetadata metadata = access.files().stat(candidate);
    if (metadata.state() != FileMetadata.State.PRESENT) {
      throw new IOException("the recorded file is " + stateText(metadata) + ": " + candidate);
    }
    ExecutionPath canonical = access.files().canonicalize(candidate);
    if (!access.files().stat(canonical).isRegularFile()) {
      throw new IOException("the recorded path is not a regular file: " + candidate);
    }
    return canonical;
  }

  /** Resolves a local candidate without requiring it to exist or be a regular file. */
  public static Path localPathCandidate(
      FileLink link, Optional<Path> workspaceRoot, Optional<Path> workingDirectory)
      throws IOException {
    WorkspaceFileAccess access = WorkspaceFileAccess.local(workspaceRoot, workingDirectory);
    return requireLocal(access, pathCandidate(link, access));
  }

  /** Resolves a candidate without requiring it to exist or be regular. */
  public static ExecutionPath pathCandidate(FileLink link, WorkspaceFileAccess access)
      throws IOException {
    Objects.requireNonNull(link, "link");
    Objects.requireNonNull(access, "access");
    return switch (link.base()) {
      case FILE_URI -> fileUri(access.files(), link.location());
      case WORKSPACE -> {
        ExecutionPath base =
            access
                .workspaceRoot()
                .or(() -> access.workingDirectory())
                .orElseThrow(
                    () -> new IOException("this execution did not provide a workspace path"));
        String relative = safeRelativePath(link.location());
        yield isAbsoluteLike(relative)
            ? access.files().path(relative)
            : access.files().resolve(base, relative);
      }
    };
  }

  /** A label that can name a main-workspace BUILD file, or empty. Pure. */
  public static Optional<String> mainRepositoryLabel(String candidate) {
    try {
      packagePath(candidate);
      return Optional.of(candidate);
    } catch (IllegalArgumentException invalid) {
      return Optional.empty();
    }
  }

  /** Package portion of a main-repository label. Pure and traversal-safe. */
  static String packagePath(String label) {
    Objects.requireNonNull(label, "label");
    String value = label.strip();
    // @// and @@// are accepted spellings for the main repository. Any
    // repository name before // is external and cannot be found from the
    // main workspace root without querying Bazel's output base.
    if (value.startsWith("@@//")) {
      value = value.substring(2);
    } else if (value.startsWith("@//")) {
      value = value.substring(1);
    } else if (value.startsWith("@")) {
      throw new IllegalArgumentException(
          "external-repository labels have no file under the main workspace: " + label);
    }
    if (!value.startsWith("//")) {
      throw new IllegalArgumentException("not an absolute Bazel label: " + label);
    }
    int colon = value.indexOf(':', 2);
    String packagePath = value.substring(2, colon < 0 ? value.length() : colon);
    if (packagePath.startsWith("/")
        || packagePath.endsWith("/")
        || packagePath.contains("\\")
        || packagePath.indexOf('\0') >= 0) {
      throw new IllegalArgumentException("invalid package path in label: " + label);
    }
    for (String segment : packagePath.split("/", -1)) {
      if (segment.equals(".")
          || segment.equals("..")
          || segment.isEmpty() && !packagePath.isEmpty()) {
        throw new IllegalArgumentException("unsafe package path in label: " + label);
      }
    }
    if (colon >= 0 && value.substring(colon + 1).isBlank()) {
      throw new IllegalArgumentException("label has no target name: " + label);
    }
    return packagePath;
  }

  private static ExecutionPath fileUri(ExecutionFileSystem files, String value) throws IOException {
    try {
      URI uri = new URI(value);
      if (!"file".equalsIgnoreCase(uri.getScheme())) {
        throw new IOException("only file URIs can be opened: " + value);
      }
      if (uri.getAuthority() != null
          && !uri.getAuthority().isEmpty()
          && !"localhost".equalsIgnoreCase(uri.getAuthority())) {
        throw new IOException("remote file URIs are not opened: " + value);
      }
      if ("localhost".equalsIgnoreCase(uri.getAuthority())) {
        uri = new URI("file", null, uri.getPath(), null);
      }
      return files.path(uri.getPath());
    } catch (URISyntaxException | IllegalArgumentException failure) {
      throw new IOException("the recorded file URI is invalid: " + value, failure);
    }
  }

  private static String safeRelativePath(String value) throws IOException {
    Objects.requireNonNull(value, "value");
    if (value.isBlank() || value.indexOf('\0') >= 0) {
      throw new IOException("the recorded path is invalid: " + value);
    }
    if (!isAbsoluteLike(value)) {
      int depth = 0;
      for (String segment : value.replace('\\', '/').split("/", -1)) {
        if (segment.equals("..")) {
          if (depth == 0) {
            throw new IOException("the recorded path escapes the workspace: " + value);
          }
          depth--;
        } else if (!segment.isEmpty() && !segment.equals(".")) {
          depth++;
        }
      }
    }
    return value;
  }

  private static boolean isAbsoluteLike(String value) {
    return value.startsWith("/")
        || value.startsWith("\\")
        || value.length() >= 3
            && Character.isLetter(value.charAt(0))
            && value.charAt(1) == ':'
            && (value.charAt(2) == '\\' || value.charAt(2) == '/');
  }

  private static Path requireLocal(WorkspaceFileAccess access, ExecutionPath path)
      throws IOException {
    return access
        .files()
        .localPath(path)
        .orElseThrow(() -> new IOException("the execution path is not local: " + path));
  }

  private static String stateText(FileMetadata metadata) {
    return switch (metadata.state()) {
      case MISSING -> "missing";
      case INACCESSIBLE -> "inaccessible";
      case UNAVAILABLE -> "unavailable";
      case PRESENT -> "present";
    };
  }
}
