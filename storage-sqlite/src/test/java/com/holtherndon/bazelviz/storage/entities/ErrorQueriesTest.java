package com.holtherndon.bazelviz.storage.entities;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.storage.SessionDatabase;
import com.holtherndon.bazelviz.storage.events.RawLocation;
import com.holtherndon.bazelviz.storage.schema.MigrationRunner;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The console-output half of the Errors read path.
 *
 * <p>The property under test is unglamorous and was the whole bug: the query selects each progress
 * event's journal address, and the address has to survive as far as the caller. {@code
 * progress.stderr} is the only copy of a compiler or parser diagnostic there is (finding X2), so a
 * {@code ProgressRef} that knows how many bytes exist and not where they are is a row that can
 * report a diagnostic's size and never its text.
 */
final class ErrorQueriesTest {

  @TempDir Path tempDir;

  private SessionDatabase database;
  private Connection connection;

  @BeforeEach
  void buildFixture() throws Exception {
    database = SessionDatabase.open(tempDir.resolve("errors.db"));
    MigrationRunner.standard().migrate(database);
    connection = database.writerConnection();
    exec("INSERT INTO event_streams (stream_key, state) VALUES ('s', 'OPEN')");
    // Three events: two carrying stderr, one carrying only stdout.
    event(1, 1, 0, 4_096, 512);
    event(2, 2, 1, 8_192, 128);
    event(3, 3, 0, 9_000, 64);
    exec(
        "INSERT INTO progress_output (bep_event_id, ordinal, stdout_bytes, stderr_bytes)"
            + " VALUES (1, 1, 0, 512), (2, 2, 12, 128), (3, 3, 40, 0)");
  }

  @AfterEach
  void closeDatabase() throws Exception {
    database.close();
  }

  @Test
  @DisplayName("each console event carries the journal address of its own bytes")
  void refsCarryTheirLocation() throws Exception {
    try (ErrorQueries queries = new ErrorQueries(database.newReadConnection())) {
      List<ErrorQueries.ProgressRef> refs = queries.progressOutputEvents(10);

      assertThat(refs).hasSize(2);
      assertThat(refs.get(0).rawLocation()).isEqualTo(new RawLocation(0, 4_096, 512));
      assertThat(refs.get(1).rawLocation()).isEqualTo(new RawLocation(1, 8_192, 128));
    }
  }

  @Test
  @DisplayName("only the events that wrote to stderr are listed, in stream order")
  void onlyStderrEvents() throws Exception {
    try (ErrorQueries queries = new ErrorQueries(database.newReadConnection())) {
      List<ErrorQueries.ProgressRef> refs = queries.progressOutputEvents(10);

      assertThat(refs).extracting(ErrorQueries.ProgressRef::bepEventId).containsExactly(1L, 2L);
      assertThat(refs).extracting(ErrorQueries.ProgressRef::stderrBytes).containsExactly(512, 128);
    }
  }

  @Test
  @DisplayName("the limit is honoured, so a noisy build cannot flood the card")
  void limitIsHonoured() throws Exception {
    try (ErrorQueries queries = new ErrorQueries(database.newReadConnection())) {
      assertThat(queries.progressOutputEvents(1)).hasSize(1);
    }
  }

  private void event(long id, long sequence, int segment, long offset, int length)
      throws Exception {
    exec(
        "INSERT INTO bep_events (id, stream_id, sequence, event_type, raw_segment,"
            + " raw_offset, raw_length, decode_status, receive_micros) VALUES ("
            + id
            + ", 1, "
            + sequence
            + ", 3, "
            + segment
            + ", "
            + offset
            + ", "
            + length
            + ", 'OK', "
            + (1_700_000_000_000_000L + id)
            + ")");
  }

  private void exec(String sql) throws Exception {
    try (Statement statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }
}
