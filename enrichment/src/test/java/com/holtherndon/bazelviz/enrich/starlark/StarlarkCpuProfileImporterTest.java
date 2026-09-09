package com.holtherndon.bazelviz.enrich.starlark;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.perftools.profiles.ProfileProto;
import com.holtherndon.bazelviz.core.enrich.EnrichmentTask;
import com.holtherndon.bazelviz.storage.SessionDatabase;
import com.holtherndon.bazelviz.storage.enrich.EnrichmentTaskStore;
import com.holtherndon.bazelviz.storage.schema.MigrationRunner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class StarlarkCpuProfileImporterTest {

  @TempDir Path tempDir;

  private SessionDatabase database;
  private Connection connection;

  @BeforeEach
  void openDatabase() throws Exception {
    database = SessionDatabase.open(tempDir.resolve("session.db"));
    MigrationRunner.standard().migrate(database);
    connection = database.writerConnection();
  }

  @AfterEach
  void closeDatabase() throws Exception {
    database.close();
  }

  @Test
  void preservesRawRecordsAndBuildsExactIndexes() throws Exception {
    Path profile =
        StarlarkProfileFixture.writePacked(
            tempDir.resolve("starlark.pprof.gz"), StarlarkProfileFixture.standard());

    StarlarkCpuProfileImporter.Result result =
        new StarlarkCpuProfileImporter(connection).importFrom(profile);

    assertThat(result.state()).isEqualTo(EnrichmentTask.State.SUCCEEDED);
    assertThat(result.samplesRead()).isEqualTo(2);
    assertThat(result.totalCpuMicros()).isEqualTo(30);
    assertThat(result.error()).isEmpty();

    assertThat(number("SELECT count(*) FROM starlark_profile_strings")).isEqualTo(10);
    assertThat(number("SELECT count(*) FROM starlark_profile_samples")).isEqualTo(2);
    assertThat(number("SELECT count(*) FROM starlark_profile_sample_frames")).isEqualTo(5);
    assertThat(number("SELECT count(*) FROM starlark_profile_sample_labels")).isEqualTo(2);
    assertThat(
            text(
                "SELECT group_concat(value_kind, ',')"
                    + " FROM starlark_profile_sample_labels ORDER BY sample_id"))
        .isEqualTo("STRING,NUMERIC");
    assertThat(
            number(
                "SELECT location_id FROM starlark_profile_sample_frames"
                    + " WHERE sample_id=1 AND ordinal=0"))
        .isEqualTo(202);

    assertThat(text("SELECT format FROM starlark_profile_metadata")).isEqualTo("pprof-gzip");
    assertThat(text("SELECT validation_state FROM starlark_profile_metadata")).isEqualTo("VALID");
    assertThat(number("SELECT selected_sample_type_ordinal" + " FROM starlark_profile_metadata"))
        .isZero();
    assertThat(number("SELECT normalized_period_micros" + " FROM starlark_profile_metadata"))
        .isEqualTo(10_000);
    assertThat(number("SELECT max_stack_depth FROM starlark_profile_metadata")).isEqualTo(3);
    assertThat(number("SELECT function_attributed_value" + " FROM starlark_profile_metadata"))
        .isEqualTo(30);
    assertThat(number("SELECT function_attributed_samples" + " FROM starlark_profile_metadata"))
        .isEqualTo(2);
    assertThat(number("SELECT file_attributed_value" + " FROM starlark_profile_metadata"))
        .isEqualTo(30);
    assertThat(number("SELECT context_attributed_value" + " FROM starlark_profile_metadata"))
        .isEqualTo(30);

    // Synthetic root + both distinct root-to-leaf contexts.
    assertThat(number("SELECT count(*) FROM starlark_call_nodes")).isEqualTo(5);
    assertThat(number("SELECT inclusive_value FROM starlark_call_nodes WHERE node_id=1"))
        .isEqualTo(30);
    assertThat(
            number(
                "SELECT count(*) FROM starlark_call_nodes"
                    + " WHERE location_id=202 AND self_value>0"))
        .isEqualTo(2);

    // Function 101 occurs twice in the recursive sample, but receives that sample once.
    assertThat(
            number(
                "SELECT cumulative_value FROM starlark_function_metrics"
                    + " WHERE function_id=101"))
        .isEqualTo(30);
    assertThat(
            number(
                "SELECT cumulative_samples FROM starlark_function_metrics"
                    + " WHERE function_id=101"))
        .isEqualTo(2);
    assertThat(
            number(
                "SELECT context_count FROM starlark_function_metrics" + " WHERE function_id=101"))
        .isEqualTo(2);
    assertThat(
            number("SELECT self_value FROM starlark_function_metrics" + " WHERE function_id=102"))
        .isEqualTo(30);
    assertThat(
            number(
                "SELECT cumulative_value FROM starlark_file_metrics"
                    + " WHERE filename_string_index=5"))
        .isEqualTo(30);
    assertThat(
            number(
                "SELECT function_count FROM starlark_file_metrics"
                    + " WHERE filename_string_index=5"))
        .isEqualTo(1);
    assertThat(number("SELECT count(*) FROM starlark_call_edges")).isEqualTo(3);
    assertThat(
            number(
                "SELECT value FROM starlark_call_edges"
                    + " WHERE caller_location_id=201 AND callee_location_id=203"))
        .isEqualTo(20);

    assertThat(new EnrichmentTaskStore(connection).all())
        .singleElement()
        .extracting(EnrichmentTask::state)
        .isEqualTo(EnrichmentTask.State.SUCCEEDED);
  }

  @Test
  void standaloneKeepsNanosecondsAndAcceptsUncompressedProtobuf() throws Exception {
    ProfileProto.Profile profile =
        StarlarkProfileFixture.standard().toBuilder()
            .setStringTable(1, "cpu")
            .setStringTable(2, "nanoseconds")
            .build();
    Path file = tempDir.resolve("cpu.pprof");
    Files.write(file, profile.toByteArray());
    assertThat(new StarlarkCpuProfileImporter(connection).importPprof(file).state())
        .isEqualTo(EnrichmentTask.State.SUCCEEDED);
    assertThat(number("SELECT total_value FROM starlark_profile_metadata")).isEqualTo(30);
    assertThat(text("SELECT format FROM starlark_profile_metadata")).isEqualTo("pprof");
    assertThat(number("SELECT sum(self_value) FROM starlark_function_metrics")).isEqualTo(30);
    assertThat(Files.readAllBytes(file)).isEqualTo(profile.toByteArray());
    assertThat(new StarlarkCpuProfileImporter(connection).importFrom(file).state())
        .isEqualTo(EnrichmentTask.State.FAILED);
  }

  @Test
  void standaloneUsesLastSampleTypeUnlessAnExplicitDefaultExists() throws Exception {
    ProfileProto.Profile.Builder profile =
        StarlarkProfileFixture.standard().toBuilder()
            .clearDefaultSampleType()
            .addStringTable("inuse_space")
            .addStringTable("bytes")
            .addSampleType(ProfileProto.ValueType.newBuilder().setType(10).setUnit(11));
    for (int i = 0; i < profile.getSampleCount(); i++) {
      profile.setSample(i, profile.getSample(i).toBuilder().addValue(4096));
    }
    Path file =
        StarlarkProfileFixture.writePacked(tempDir.resolve("heap.pprof.gz"), profile.build());
    assertThat(new StarlarkCpuProfileImporter(connection).importPprof(file).state())
        .isEqualTo(EnrichmentTask.State.SUCCEEDED);
    assertThat(number("SELECT total_value FROM starlark_profile_metadata")).isEqualTo(8192);
    assertThat(number("SELECT selected_sample_type_ordinal FROM starlark_profile_metadata"))
        .isEqualTo(1);
    StarlarkProfileFixture.writePacked(file, profile.setDefaultSampleType(1).build());
    assertThat(new StarlarkCpuProfileImporter(connection).importPprof(file).state())
        .isEqualTo(EnrichmentTask.State.SUCCEEDED);
    assertThat(number("SELECT total_value FROM starlark_profile_metadata")).isEqualTo(30);
  }

  @Test
  void standaloneRejectsSignedProfilesRatherThanDroppingNegativeSamples() throws Exception {
    Path file =
        StarlarkProfileFixture.writePacked(
            tempDir.resolve("diff.pprof.gz"), StarlarkProfileFixture.oneSample(-1));
    var result = new StarlarkCpuProfileImporter(connection).importPprof(file);
    assertThat(result.state()).isEqualTo(EnrichmentTask.State.FAILED);
    assertThat(result.error())
        .hasValueSatisfying(message -> assertThat(message).contains("signed/difference"));
    assertThat(number("SELECT count(*) FROM starlark_profile_metadata")).isZero();
  }

  @Test
  void successfulRetryReplacesEveryRawAndDerivedRow() throws Exception {
    StarlarkCpuProfileImporter importer = new StarlarkCpuProfileImporter(connection);
    importer.importFrom(
        StarlarkProfileFixture.writePacked(
            tempDir.resolve("first.gz"), StarlarkProfileFixture.standard()));

    StarlarkCpuProfileImporter.Result result =
        importer.importFrom(
            StarlarkProfileFixture.writePacked(
                tempDir.resolve("second.gz"), StarlarkProfileFixture.oneSample(7)));

    assertThat(result.state()).isEqualTo(EnrichmentTask.State.SUCCEEDED);
    assertThat(result.totalCpuMicros()).isEqualTo(7);
    assertThat(number("SELECT count(*) FROM starlark_profile_samples")).isEqualTo(1);
    assertThat(number("SELECT total_value FROM starlark_profile_metadata")).isEqualTo(7);
    assertThat(number("SELECT count(*) FROM starlark_call_nodes")).isEqualTo(3);
  }

  @Test
  void boundedBatchesCrossTheirFlushBoundaryWithoutRepeatingRows() throws Exception {
    int samples = StarlarkProfileWriter.JDBC_BATCH_SIZE + 1;
    Path profile =
        StarlarkProfileFixture.writePacked(
            tempDir.resolve("batched.gz"), StarlarkProfileFixture.manySamples(samples));

    StarlarkCpuProfileImporter.Result result =
        new StarlarkCpuProfileImporter(connection).importFrom(profile);

    assertThat(result.state()).isEqualTo(EnrichmentTask.State.SUCCEEDED);
    assertThat(result.samplesRead()).isEqualTo(samples);
    assertThat(result.totalCpuMicros()).isEqualTo(samples);
    assertThat(number("SELECT count(*) FROM starlark_profile_samples")).isEqualTo(samples);
    assertThat(number("SELECT sample_count FROM starlark_call_nodes WHERE location_id=202"))
        .isEqualTo(samples);
  }

  @Test
  void materializedDisplayCountsUseSingleGroupedPlans() throws Exception {
    List<String> contextPlan =
        queryPlan(StarlarkProfileWriter.MATERIALIZE_FUNCTION_CONTEXT_COUNTS_SQL);
    assertThat(contextPlan).filteredOn(detail -> detail.contains("MATERIALIZE counts")).hasSize(1);
    assertThat(contextPlan).noneMatch(detail -> detail.contains("CORRELATED"));

    List<String> filePlan = queryPlan(StarlarkProfileWriter.MATERIALIZE_FILE_FUNCTION_COUNTS_SQL);
    assertThat(filePlan)
        .anyMatch(detail -> detail.contains("ix_starlark_profile_functions_filename"))
        .noneMatch(detail -> detail.contains("CORRELATED"));
  }

  @Test
  void normalizesPeriodFromItsOwnCpuUnit() throws Exception {
    Path profile =
        StarlarkProfileFixture.writePacked(
            tempDir.resolve("nanosecond-period.gz"), StarlarkProfileFixture.periodInNanoseconds());

    StarlarkCpuProfileImporter.Result result =
        new StarlarkCpuProfileImporter(connection).importFrom(profile);

    assertThat(result.state()).isEqualTo(EnrichmentTask.State.SUCCEEDED);
    assertThat(number("SELECT period FROM starlark_profile_metadata")).isEqualTo(10_000_000);
    assertThat(number("SELECT normalized_period_micros" + " FROM starlark_profile_metadata"))
        .isEqualTo(10_000);
    assertThat(text("SELECT validation_detail FROM starlark_profile_metadata"))
        .contains("normalized from 10000000 nanoseconds");
  }

  @Test
  void doesNotExposeAnUnconvertiblePeriodAsMicroseconds() throws Exception {
    Path profile =
        StarlarkProfileFixture.writePacked(
            tempDir.resolve("sub-microsecond-period.gz"),
            StarlarkProfileFixture.subMicrosecondPeriod());

    StarlarkCpuProfileImporter.Result result =
        new StarlarkCpuProfileImporter(connection).importFrom(profile);

    assertThat(result.state()).isEqualTo(EnrichmentTask.State.SUCCEEDED);
    assertThat(
            nullableNumber("SELECT normalized_period_micros" + " FROM starlark_profile_metadata"))
        .isNull();
    assertThat(text("SELECT validation_detail FROM starlark_profile_metadata"))
        .contains("not a whole microsecond");
  }

  @Test
  void reportsInlineMissingAndBlankFileAttributionWithoutOverclaiming() throws Exception {
    Path profile =
        StarlarkProfileFixture.writePacked(
            tempDir.resolve("partial-symbols.gz"), StarlarkProfileFixture.mixedSymbolAttribution());

    StarlarkCpuProfileImporter.Result result =
        new StarlarkCpuProfileImporter(connection).importFrom(profile);

    assertThat(result.state()).isEqualTo(EnrichmentTask.State.SUCCEEDED);
    assertThat(result.totalCpuMicros()).isEqualTo(150);
    assertThat(number("SELECT function_attributed_value" + " FROM starlark_profile_metadata"))
        .isEqualTo(80);
    assertThat(number("SELECT function_attributed_samples" + " FROM starlark_profile_metadata"))
        .isEqualTo(3);
    assertThat(number("SELECT file_attributed_value" + " FROM starlark_profile_metadata"))
        .isEqualTo(90);
    assertThat(number("SELECT file_attributed_samples" + " FROM starlark_profile_metadata"))
        .isEqualTo(3);
    assertThat(number("SELECT context_attributed_value" + " FROM starlark_profile_metadata"))
        .isEqualTo(70);
    assertThat(number("SELECT context_attributed_samples" + " FROM starlark_profile_metadata"))
        .isEqualTo(2);

    // The second inline symbol receives cumulative CPU, but not leaf self CPU.
    assertThat(
            number(
                "SELECT cumulative_value FROM starlark_function_metrics"
                    + " WHERE function_id=103"))
        .isEqualTo(10);
    assertThat(
            number("SELECT self_value FROM starlark_function_metrics" + " WHERE function_id=103"))
        .isZero();
    assertThat(
            number(
                "SELECT cumulative_value FROM starlark_file_metrics"
                    + " WHERE filename_string_index=11"))
        .isEqualTo(10);
    assertThat(
            number("SELECT self_value FROM starlark_function_metrics" + " WHERE function_id=105"))
        .isEqualTo(50);
    assertThat(
            number("SELECT count(*) FROM starlark_file_metrics" + " WHERE filename_string_index=0"))
        .isZero();
    assertThat(text("SELECT validation_detail FROM starlark_profile_metadata"))
        .contains("80 attributed / 70 unattributed CPU microseconds")
        .contains("90 attributed / 60 unattributed CPU microseconds")
        .contains("70 attributed / 80 unattributed CPU microseconds")
        .contains("Raw inline lines are preserved");
  }

  @Test
  void failedRetryRollsBackToTheLastCompleteRows() throws Exception {
    StarlarkCpuProfileImporter importer = new StarlarkCpuProfileImporter(connection);
    importer.importFrom(
        StarlarkProfileFixture.writePacked(
            tempDir.resolve("good.gz"), StarlarkProfileFixture.standard()));

    StarlarkCpuProfileImporter.Result result =
        importer.importFrom(
            StarlarkProfileFixture.writePacked(
                tempDir.resolve("bad.gz"), StarlarkProfileFixture.missingLocation()));

    assertThat(result.state()).isEqualTo(EnrichmentTask.State.FAILED);
    assertThat(result.error())
        .hasValueSatisfying(error -> assertThat(error).contains("missing location"));
    assertThat(number("SELECT total_value FROM starlark_profile_metadata")).isEqualTo(30);
    assertThat(number("SELECT count(*) FROM starlark_profile_samples")).isEqualTo(2);
    assertThat(new EnrichmentTaskStore(connection).all())
        .singleElement()
        .extracting(EnrichmentTask::state)
        .isEqualTo(EnrichmentTask.State.FAILED);
  }

  @Test
  void rejectsSampleValueCardinalityMismatchWithoutPartialRows() throws Exception {
    Path profile =
        StarlarkProfileFixture.writePacked(
            tempDir.resolve("cardinality.gz"), StarlarkProfileFixture.wrongValueCardinality());

    StarlarkCpuProfileImporter.Result result =
        new StarlarkCpuProfileImporter(connection).importFrom(profile);

    assertThat(result.state()).isEqualTo(EnrichmentTask.State.FAILED);
    assertThat(result.error())
        .hasValueSatisfying(error -> assertThat(error).contains("cardinality"));
    assertThat(number("SELECT count(*) FROM starlark_profile_metadata")).isZero();
    assertThat(number("SELECT count(*) FROM starlark_profile_samples")).isZero();
  }

  @Test
  void rejectsNonPositivePprofIdsWithAUsefulError() throws Exception {
    Path profile =
        StarlarkProfileFixture.writePacked(
            tempDir.resolve("id.gz"), StarlarkProfileFixture.invalidFunctionId());

    StarlarkCpuProfileImporter.Result result =
        new StarlarkCpuProfileImporter(connection).importFrom(profile);

    assertThat(result.state()).isEqualTo(EnrichmentTask.State.FAILED);
    assertThat(result.error())
        .hasValueSatisfying(error -> assertThat(error).contains("function id must be positive"));
    assertThat(number("SELECT count(*) FROM starlark_profile_functions")).isZero();
  }

  @Test
  void missingPlannedProfileIsRecordedAsAFailedImport() throws Exception {
    Path missing = tempDir.resolve("missing-starlark.pprof.gz");

    StarlarkCpuProfileImporter.Result result =
        new StarlarkCpuProfileImporter(connection).importFrom(missing);

    assertThat(result.state()).isEqualTo(EnrichmentTask.State.FAILED);
    assertThat(result.error())
        .hasValueSatisfying(error -> assertThat(error).contains("missing-starlark.pprof.gz"));
    assertThat(new EnrichmentTaskStore(connection).all())
        .singleElement()
        .satisfies(
            task -> {
              assertThat(task.state()).isEqualTo(EnrichmentTask.State.FAILED);
              assertThat(task.errorExcerpt())
                  .hasValueSatisfying(
                      error -> assertThat(error).contains("missing-starlark.pprof.gz"));
            });
  }

  private long number(String sql) throws Exception {
    try (Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery(sql)) {
      assertThat(rows.next()).isTrue();
      return rows.getLong(1);
    }
  }

  private String text(String sql) throws Exception {
    try (Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery(sql)) {
      assertThat(rows.next()).isTrue();
      return rows.getString(1);
    }
  }

  private Long nullableNumber(String sql) throws Exception {
    try (Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery(sql)) {
      assertThat(rows.next()).isTrue();
      long value = rows.getLong(1);
      return rows.wasNull() ? null : value;
    }
  }

  private List<String> queryPlan(String sql) throws Exception {
    try (PreparedStatement statement = connection.prepareStatement("EXPLAIN QUERY PLAN " + sql);
        ResultSet rows = statement.executeQuery()) {
      List<String> plan = new ArrayList<>();
      while (rows.next()) {
        plan.add(rows.getString("detail"));
      }
      return List.copyOf(plan);
    }
  }
}
