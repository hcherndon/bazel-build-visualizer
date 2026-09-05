package com.holtherndon.bazelviz.storage.enrich;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.storage.CountedPage;
import com.holtherndon.bazelviz.storage.SessionDatabase;
import com.holtherndon.bazelviz.storage.schema.MigrationRunner;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class EnrichmentQueriesTest {

  @TempDir Path temporary;

  private SessionDatabase database;
  private Connection connection;

  @BeforeEach
  void setUp() throws Exception {
    database = SessionDatabase.open(temporary.resolve("enrichment.db"));
    MigrationRunner.standard().migrate(database);
    connection = database.writerConnection();
    execute("INSERT INTO labels (id, value) VALUES (1, '//pkg:test')");
    execute(
        "INSERT INTO actions (id, primary_output, label_id, outcome)"
            + " VALUES (5, 'bazel-out/pkg/test', 1, 'SUCCEEDED')");
    execute(
        "INSERT INTO enrichment_tasks (id, kind, state) VALUES"
            + " (1, 'EXECUTION_LOG', 'SUCCEEDED'),"
            + " (2, 'PROFILE', 'SUCCEEDED')");
    // Log indexes are unique only inside one enrichment task. The duplicate 7s prove id is the
    // required tie-breaker rather than an incidental extra column.
    execute(
        "INSERT INTO action_attempts"
            + " (id, task_id, log_entry_index, action_id, label_id, correlation) VALUES"
            + " (10, 1, 7, 5, 1, 'MATCHED_BY_OUTPUT'),"
            + " (11, 2, 7, 5, 1, 'MATCHED_BY_OUTPUT'),"
            + " (12, 1, 8, 5, 1, 'MATCHED_BY_OUTPUT')");
  }

  @AfterEach
  void tearDown() throws Exception {
    database.close();
  }

  @Test
  @DisplayName("action attempts page through duplicate log indexes without gaps")
  void actionAttemptPagesUseIdAsATieBreaker() throws Exception {
    try (EnrichmentQueries queries = new EnrichmentQueries(database.newReadConnection())) {
      CountedPage<AttemptRow, EnrichmentQueries.AttemptAnchor> first =
          queries.attemptsForActionPage(5, Optional.empty(), 1);
      assertThat(first.rows()).extracting(AttemptRow::id).containsExactly(10L);
      assertThat(first.totalRows()).isEqualTo(3);
      assertThat(first.shownThrough()).isEqualTo(1);
      assertThat(first.remainingRows()).isEqualTo(2);

      CountedPage<AttemptRow, EnrichmentQueries.AttemptAnchor> second =
          queries.attemptsForActionPage(5, first.nextAnchor(), 1);
      assertThat(second.rows()).extracting(AttemptRow::id).containsExactly(11L);
      assertThat(second.totalRows()).isEqualTo(3);
      assertThat(second.remainingRows()).isEqualTo(1);

      CountedPage<AttemptRow, EnrichmentQueries.AttemptAnchor> third =
          queries.attemptsForActionPage(5, second.nextAnchor(), 1);
      assertThat(third.rows()).extracting(AttemptRow::id).containsExactly(12L);
      assertThat(third.remainingRows()).isZero();
      assertThat(third.nextAnchor()).isEmpty();
    }
  }

  @Test
  @DisplayName("label attempt pages report exact totals and empty labels honestly")
  void labelAttemptPagesAreCounted() throws Exception {
    try (EnrichmentQueries queries = new EnrichmentQueries(database.newReadConnection())) {
      CountedPage<AttemptRow, EnrichmentQueries.AttemptAnchor> first =
          queries.attemptsForLabelPage("//pkg:test", Optional.empty(), 2);
      assertThat(first.rows()).extracting(AttemptRow::id).containsExactly(10L, 11L);
      assertThat(first.totalRows()).isEqualTo(3);
      assertThat(first.remainingRows()).isEqualTo(1);

      CountedPage<AttemptRow, EnrichmentQueries.AttemptAnchor> missing =
          queries.attemptsForLabelPage("//pkg:missing", Optional.empty(), 2);
      assertThat(missing.rows()).isEmpty();
      assertThat(missing.totalRows()).isZero();
      assertThat(missing.remainingRows()).isZero();
      assertThat(missing.nextAnchor()).isEmpty();
    }
  }

  private void execute(String sql) throws Exception {
    try (Statement statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }
}
