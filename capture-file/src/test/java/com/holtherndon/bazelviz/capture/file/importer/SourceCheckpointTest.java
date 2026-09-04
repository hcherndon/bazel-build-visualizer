package com.holtherndon.bazelviz.capture.file.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.holtherndon.bazelviz.capture.file.detect.DetectedFormat;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The source-side resume point: it must survive a round trip exactly, and it must refuse anything
 * it cannot read rather than repair it. A resume point that quietly defaults a missing field
 * resumes from the wrong offset, and an import that starts reading at the wrong offset loses
 * records without anyone finding out.
 */
class SourceCheckpointTest {

  @Test
  @DisplayName("a resume point round-trips through its file unchanged")
  void roundTrips(@TempDir Path temporary) throws Exception {
    SourceCheckpointStore store = new SourceCheckpointStore(temporary);
    SourceCheckpoint checkpoint =
        new SourceCheckpoint(
            SourceCheckpoint.FORMAT_VERSION,
            987_654_321L,
            4_242L,
            DetectedFormat.BEP_JSON,
            SourcePreservation.REFERENCE_ORIGINAL,
            "/builds/nightly/build.json",
            "b1946ac92492d2347c6235b4d2611184e0e5b0b6c5f2a08c8f8b7a9a5a9e8b7c",
            123_456_789L);

    store.write(checkpoint);
    assertThat(store.read()).contains(checkpoint);
  }

  @Test
  @DisplayName("an absent resume point is empty, not an error")
  void absentIsEmpty(@TempDir Path temporary) throws Exception {
    assertThat(new SourceCheckpointStore(temporary).read()).isEmpty();
  }

  @Test
  @DisplayName("the write replaces the file atomically, leaving no partial document")
  void writeIsAtomic(@TempDir Path temporary) throws Exception {
    SourceCheckpointStore store = new SourceCheckpointStore(temporary);
    store.write(sample(100, 1));
    store.write(sample(200, 2));

    assertThat(store.read()).map(SourceCheckpoint::sourceOffset).contains(200L);
    try (var entries = Files.list(temporary)) {
      assertThat(entries.map(path -> path.getFileName().toString()).toList())
          .as("no temporary sibling survives the write")
          .containsExactly(SourceCheckpointStore.FILE_NAME);
    }
  }

  @Test
  @DisplayName("a missing field is refused rather than defaulted")
  void refusesAnIncompleteDocument(@TempDir Path temporary) throws Exception {
    Path file = temporary.resolve(SourceCheckpointStore.FILE_NAME);
    Files.writeString(
        file,
        """
        {
          "formatVersion": 1,
          "framesWritten": 10,
          "format": "BEP_BINARY",
          "preservation": "COPY_INTO_SESSION",
          "originalPath": "/tmp/build.bep",
          "sha256": "abc",
          "byteSize": 10
        }
        """);

    assertThatThrownBy(() -> new SourceCheckpointStore(temporary).read())
        .isInstanceOf(ImportFormatException.class)
        .hasMessageContaining("sourceOffset");
  }

  @Test
  @DisplayName("a value this build does not know is refused, and says it may be newer")
  void refusesAnUnknownEnumValue(@TempDir Path temporary) throws Exception {
    Path file = temporary.resolve(SourceCheckpointStore.FILE_NAME);
    Files.writeString(
        file,
        """
        {
          "formatVersion": 1,
          "sourceOffset": 0,
          "framesWritten": 0,
          "format": "BEP_SOMETHING_NEW",
          "preservation": "COPY_INTO_SESSION",
          "originalPath": "/tmp/build.bep",
          "sha256": "abc",
          "byteSize": 10
        }
        """);

    assertThatThrownBy(() -> new SourceCheckpointStore(temporary).read())
        .isInstanceOf(ImportFormatException.class)
        .hasMessageContaining("newer version");
  }

  @Test
  @DisplayName("a checkpoint format version cannot wrap through a narrowing conversion")
  void refusesAnOverflowingFormatVersion(@TempDir Path temporary) throws Exception {
    Path file = temporary.resolve(SourceCheckpointStore.FILE_NAME);
    Files.writeString(
        file,
        """
        {
          "formatVersion": 4294967297,
          "sourceOffset": 0,
          "framesWritten": 0,
          "format": "BEP_BINARY",
          "preservation": "COPY_INTO_SESSION",
          "originalPath": "/tmp/build.bep",
          "sha256": "abc",
          "byteSize": 10
        }
        """);

    assertThatThrownBy(() -> new SourceCheckpointStore(temporary).read())
        .isInstanceOf(ImportFormatException.class)
        .hasMessageContaining("formatVersion")
        .hasMessageContaining("32-bit integer");
  }

  @Test
  @DisplayName("unreadable JSON is refused with the file named")
  void refusesMalformedJson(@TempDir Path temporary) throws Exception {
    Files.writeString(temporary.resolve(SourceCheckpointStore.FILE_NAME), "{ not json");

    assertThatThrownBy(() -> new SourceCheckpointStore(temporary).read())
        .isInstanceOf(ImportFormatException.class)
        .hasMessageContaining(SourceCheckpointStore.FILE_NAME);
  }

  private static SourceCheckpoint sample(long offset, long frames) {
    return new SourceCheckpoint(
        SourceCheckpoint.FORMAT_VERSION,
        offset,
        frames,
        DetectedFormat.BEP_BINARY,
        SourcePreservation.COPY_INTO_SESSION,
        "/tmp/build.bep",
        "abc",
        4096);
  }
}
