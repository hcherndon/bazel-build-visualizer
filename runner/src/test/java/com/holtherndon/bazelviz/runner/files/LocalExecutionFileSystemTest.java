package com.holtherndon.bazelviz.runner.files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class LocalExecutionFileSystemTest {

  @Test
  @DisplayName("paths stay bound to their execution and never cross local contexts")
  void pathsAreExecutionScoped(@TempDir Path directory) throws Exception {
    LocalExecutionFileSystem first = new LocalExecutionFileSystem("first");
    LocalExecutionFileSystem second = new LocalExecutionFileSystem("second");
    ExecutionPath file = first.path(Files.writeString(directory.resolve("file.txt"), "x"));

    assertThat(first.read(file, 8).bytes()).containsExactly('x');
    assertThatThrownBy(() -> second.stat(file))
        .hasMessageContaining("belongs to execution first")
        .hasMessageContaining("not second");
    assertThat(second.localPath(file)).isEmpty();
  }

  @Test
  @DisplayName("bounded reads fail rather than truncating and carry a strong version")
  void readsAreBoundedAndVersioned(@TempDir Path directory) throws Exception {
    LocalExecutionFileSystem files = new LocalExecutionFileSystem("workspace");
    ExecutionPath file = files.path(Files.writeString(directory.resolve("source.txt"), "hello"));

    FileContents contents = files.read(file, 5);

    assertThat(new String(contents.bytes(), StandardCharsets.UTF_8)).isEqualTo("hello");
    assertThat(contents.version().bytes()).isEqualTo(5);
    assertThat(contents.version().sha256()).hasSize(64);
    assertThatThrownBy(() -> files.read(file, 4))
        .hasMessageContaining("operation limit is 4 bytes");
  }

  @Test
  @DisplayName("conditional replacement preserves newer external work")
  void replacementDetectsConflicts(@TempDir Path directory) throws Exception {
    LocalExecutionFileSystem files = new LocalExecutionFileSystem("workspace");
    Path local = Files.writeString(directory.resolve("BUILD.bazel"), "before\n");
    ExecutionPath file = files.path(local);
    FileContents baseline = files.read(file, 1024);
    Files.writeString(local, "newer work\n");

    assertThatThrownBy(
            () ->
                files.replaceAtomically(
                    file, baseline.version(), "my edit\n".getBytes(StandardCharsets.UTF_8), 1024))
        .isInstanceOf(FileConflictException.class)
        .hasMessageContaining("not overwritten");
    assertThat(Files.readString(local)).isEqualTo("newer work\n");
  }

  @Test
  @DisplayName("directory pages are sorted, bounded, complete, and do not follow symlinks")
  void directoryListingIsPaged(@TempDir Path directory) throws Exception {
    LocalExecutionFileSystem files = new LocalExecutionFileSystem("workspace");
    Files.writeString(directory.resolve("charlie"), "c");
    Files.writeString(directory.resolve("alpha"), "a");
    Files.createDirectory(directory.resolve("bravo"));
    boolean symlinkCreated;
    try {
      Files.createSymbolicLink(directory.resolve("delta"), Path.of("alpha"));
      symlinkCreated = true;
    } catch (UnsupportedOperationException | IOException unsupported) {
      symlinkCreated = false;
    }

    List<FileMetadata> all = new ArrayList<>();
    Optional<String> token = Optional.empty();
    do {
      DirectoryPage page = files.list(files.path(directory), token, 2);
      assertThat(page.entries()).hasSizeLessThanOrEqualTo(2);
      assertThat(page.totalEntries()).hasValue(symlinkCreated ? 4 : 3);
      all.addAll(page.entries());
      token = page.nextToken();
    } while (token.isPresent());

    assertThat(all)
        .extracting(entry -> entry.path().fileName())
        .containsExactlyElementsOf(
            symlinkCreated
                ? List.of("alpha", "bravo", "charlie", "delta")
                : List.of("alpha", "bravo", "charlie"));
    assertThat(all).anyMatch(FileMetadata::isDirectory);
    if (symlinkCreated) {
      assertThat(all.getLast().kind()).isEqualTo(FileMetadata.Kind.SYMBOLIC_LINK);
    }
  }

  @Test
  @DisplayName("a directory symlink can be canonicalized and listed explicitly")
  void directorySymlinkCanBeTraversedWhenRequested(@TempDir Path directory) throws Exception {
    LocalExecutionFileSystem files = new LocalExecutionFileSystem("workspace");
    Path target = Files.createDirectory(directory.resolve("output-tree"));
    Files.writeString(target.resolve("artifact.txt"), "built");
    Path link = directory.resolve("bazel-out");
    try {
      Files.createSymbolicLink(link, target);
    } catch (UnsupportedOperationException | IOException unsupported) {
      Assumptions.abort("symbolic links are unavailable on this host");
    }

    ExecutionPath requested = files.path(link);
    assertThat(files.stat(requested).kind()).isEqualTo(FileMetadata.Kind.SYMBOLIC_LINK);

    ExecutionPath canonical = files.canonicalize(requested);
    DirectoryPage page = files.list(canonical, Optional.empty(), 10);

    assertThat(page.directory()).isEqualTo(canonical);
    assertThat(page.entries())
        .extracting(entry -> entry.path().fileName())
        .containsExactly("artifact.txt");
  }

  @Test
  @DisplayName("downloads and uploads stream through bounded atomic local destinations")
  void transfersAreBounded(@TempDir Path directory) throws Exception {
    LocalExecutionFileSystem files = new LocalExecutionFileSystem("workspace");
    Path source = Files.writeString(directory.resolve("source.bin"), "payload");
    Path downloaded = directory.resolve("nested/downloaded.bin");

    files.download(files.path(source), downloaded, 7);
    assertThat(Files.readString(downloaded)).isEqualTo("payload");

    ExecutionPath uploaded = files.path(directory.resolve("uploaded.bin"));
    files.upload(downloaded, uploaded, 7, UploadMode.CREATE_NEW);
    assertThat(Files.readString(directory.resolve("uploaded.bin"))).isEqualTo("payload");
    assertThatThrownBy(() -> files.upload(downloaded, uploaded, 7, UploadMode.CREATE_NEW))
        .isInstanceOf(FileAlreadyExistsException.class);
    assertThatThrownBy(() -> files.download(files.path(source), downloaded, 6))
        .hasMessageContaining("operation limit is 6 bytes");
  }
}
