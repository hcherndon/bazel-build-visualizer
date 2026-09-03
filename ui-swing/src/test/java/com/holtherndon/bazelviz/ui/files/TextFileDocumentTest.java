package com.holtherndon.bazelviz.ui.files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.holtherndon.bazelviz.runner.files.LocalExecutionFileSystem;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Bounded reads and non-destructive saves behind the modeless editor. */
final class TextFileDocumentTest {

  @Test
  @DisplayName("UTF-8 text round-trips through an atomic explicit save")
  void textRoundTrips(@TempDir Path directory) throws Exception {
    Path file = Files.writeString(directory.resolve("BUILD.bazel"), "cc_library(name='a')\n");
    TextFileDocument.Loaded loaded = TextFileDocument.load(file);

    TextFileDocument.Loaded saved = TextFileDocument.save(loaded, "cc_library(name='b')\n");

    assertThat(Files.readString(file)).isEqualTo("cc_library(name='b')\n");
    assertThat(saved.text()).isEqualTo("cc_library(name='b')\n");
    assertThat(saved.stamp()).isNotEqualTo(loaded.stamp());
  }

  @Test
  @DisplayName("a save refuses to overwrite bytes changed outside the editor")
  void externalChangesWin(@TempDir Path directory) throws Exception {
    Path file = Files.writeString(directory.resolve("BUILD"), "before\n");
    TextFileDocument.Loaded loaded = TextFileDocument.load(file);
    Files.writeString(file, "newer work\n");

    assertThatThrownBy(() -> TextFileDocument.save(loaded, "my edit\n"))
        .hasMessageContaining("changed on disk")
        .hasMessageContaining("not overwritten");
    assertThat(Files.readString(file)).isEqualTo("newer work\n");
  }

  @Test
  @DisplayName("binary and over-limit files are refused rather than misrendered or truncated")
  void binaryAndLargeFilesAreRefused(@TempDir Path directory) throws Exception {
    Path binary = Files.write(directory.resolve("artifact.bin"), new byte[] {1, 0, 2});
    assertThatThrownBy(() -> TextFileDocument.load(binary)).hasMessageContaining("binary");

    Path large = directory.resolve("large.log");
    try (var channel =
        FileChannel.open(large, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
      channel.position(TextFileDocument.MAX_FILE_BYTES);
      channel.write(ByteBuffer.wrap(new byte[] {'x'}));
    }
    assertThatThrownBy(() -> TextFileDocument.load(large))
        .hasMessageContaining(Integer.toString(TextFileDocument.MAX_FILE_BYTES))
        .hasMessageContaining("bytes");
  }

  @Test
  @DisplayName("the editor document reads and saves through an execution filesystem")
  void executionFilesystemRoundTrip(@TempDir Path directory) throws Exception {
    Path file = Files.writeString(directory.resolve("remote.bzl"), "VALUE = 1\n");
    LocalExecutionFileSystem files = new LocalExecutionFileSystem("ssh-test-double");
    TextFileDocument.ExecutionLoaded loaded = TextFileDocument.load(files, files.path(file));

    TextFileDocument.ExecutionLoaded saved = TextFileDocument.save(files, loaded, "VALUE = 2\n");

    assertThat(saved.path().executionId()).isEqualTo("ssh-test-double");
    assertThat(saved.text()).isEqualTo("VALUE = 2\n");
    assertThat(Files.readString(file)).isEqualTo("VALUE = 2\n");
  }
}
