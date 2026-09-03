package com.holtherndon.bazelviz.capture.file.importer;

import com.holtherndon.bazelviz.format.session.AtomicFiles;
import com.holtherndon.bazelviz.format.session.json.JsonException;
import com.holtherndon.bazelviz.format.session.json.JsonReader;
import com.holtherndon.bazelviz.format.session.json.JsonValue;
import com.holtherndon.bazelviz.format.session.json.JsonWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Reads and writes {@code checkpoints/import-source.json} atomically.
 *
 * <p>Atomic replacement matters for the same reason it does for the manifest: the next process to
 * open this session reads this file to decide where to carry on reading, and a half-written resume
 * point is worse than none — it would resume from a plausible-looking wrong offset instead of
 * failing.
 */
final class SourceCheckpointStore {

  static final String FILE_NAME = "import-source.json";

  private final Path file;

  SourceCheckpointStore(Path checkpointDirectory) {
    this.file =
        Objects.requireNonNull(checkpointDirectory, "checkpointDirectory").resolve(FILE_NAME);
  }

  Path file() {
    return file;
  }

  void write(SourceCheckpoint checkpoint) throws IOException {
    Objects.requireNonNull(checkpoint, "checkpoint");
    Path parent = file.getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    AtomicFiles.writeString(file, JsonWriter.writePretty(checkpoint.toJson()) + "\n");
  }

  Optional<SourceCheckpoint> read() throws IOException {
    JsonValue parsed;
    try {
      parsed = JsonReader.parseFile(file);
    } catch (NoSuchFileException absent) {
      return Optional.empty();
    } catch (JsonException malformed) {
      throw new ImportFormatException(
          "source checkpoint " + file + " is not readable JSON: " + malformed.getMessage(),
          malformed);
    }
    return Optional.of(SourceCheckpoint.fromJson(parsed));
  }

  void delete() throws IOException {
    Files.deleteIfExists(file);
  }
}
