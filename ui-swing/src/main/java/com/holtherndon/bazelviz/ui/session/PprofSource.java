package com.holtherndon.bazelviz.ui.session;

import com.holtherndon.bazelviz.core.enrich.EnrichmentTask;
import com.holtherndon.bazelviz.enrich.starlark.StarlarkCpuProfileImporter;
import com.holtherndon.bazelviz.storage.SessionDatabase;
import com.holtherndon.bazelviz.storage.schema.MigrationRunner;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;
import org.slf4j.LoggerFactory;

/** A private, disposable, disk-backed profile; never attaches data to a build session. */
public final class PprofSource {

  /** Maximum original file copied into one private standalone profile workspace. */
  public static final long MAX_SOURCE_BYTES = 1L << 30;

  private PprofSource() {}

  /** Blocking: call from the profile worker, not the EDT. The reader owns all temporary files. */
  public static StarlarkProfileReader open(Path source) {
    return open(source, null);
  }

  static StarlarkProfileReader open(Path source, Path temporaryRoot) {
    Path directory = null;
    SessionDatabase database = null;
    try {
      directory =
          temporaryRoot == null
              ? Files.createTempDirectory("bazelviz-pprof-")
              : Files.createTempDirectory(temporaryRoot, "bazelviz-pprof-");
      Path snapshot = directory.resolve("source.pprof");
      copySource(source, snapshot);
      database = SessionDatabase.open(directory.resolve("profile.db"));
      MigrationRunner.standard().migrate(database);
      StarlarkCpuProfileImporter.Result result =
          new StarlarkCpuProfileImporter(database.writerConnection()).importPprof(snapshot);
      if (result.state() != EnrichmentTask.State.SUCCEEDED) {
        throw new IOException(result.error().orElse("The profile could not be imported."));
      }
      StarlarkProfileReader.SampleMetric metric = readMetric(database.writerConnection());
      SessionDatabase ownedDatabase = database;
      Path ownedDirectory = directory;
      return new SqliteStarlarkProfileReader(
          source.toString(),
          database.newReadConnection(),
          StarlarkProfileReader.Correlation.STANDALONE) {
        @Override
        public Optional<SampleMetric> sampleMetric() {
          return Optional.of(metric);
        }

        @Override
        public void close() {
          try {
            super.close();
          } finally {
            cleanup(ownedDatabase, ownedDirectory);
          }
        }
      };
    } catch (IOException | SQLException | RuntimeException failure) {
      cleanup(database, directory);
      throw new IllegalStateException("Could not open pprof: " + failure.getMessage(), failure);
    }
  }

  private static void copySource(Path source, Path snapshot) throws IOException {
    if (!Files.isRegularFile(source)) {
      throw new IOException("The selected path is not a regular profile file: " + source);
    }
    if (Files.size(source) > MAX_SOURCE_BYTES) {
      throw new IOException(
          "The profile exceeds the " + MAX_SOURCE_BYTES + "-byte source-file limit");
    }
    try (InputStream input = Files.newInputStream(source);
        OutputStream output = Files.newOutputStream(snapshot)) {
      byte[] buffer = new byte[64 * 1024];
      long total = 0;
      int read;
      while ((read = input.read(buffer)) != -1) {
        if (Thread.currentThread().isInterrupted()) {
          throw new InterruptedIOException("Profile import cancelled");
        }
        total += read;
        if (total > MAX_SOURCE_BYTES) {
          throw new IOException(
              "The profile exceeds the " + MAX_SOURCE_BYTES + "-byte source-file limit");
        }
        output.write(buffer, 0, read);
      }
    }
  }

  private static StarlarkProfileReader.SampleMetric readMetric(Connection connection)
      throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rows =
            statement.executeQuery(
                "SELECT t.value,u.value FROM starlark_profile_metadata m JOIN"
                    + " starlark_profile_sample_types s ON s.ordinal=m.selected_sample_type_ordinal"
                    + " JOIN starlark_profile_strings t ON t.string_index=s.type_string_index JOIN"
                    + " starlark_profile_strings u ON u.string_index=s.unit_string_index")) {
      if (!rows.next()) {
        throw new SQLException("Missing selected profile measurement");
      }
      return new StarlarkProfileReader.SampleMetric(rows.getString(1), rows.getString(2));
    }
  }

  private static void cleanup(SessionDatabase database, Path directory) {
    try {
      if (database != null) database.close();
      if (directory != null) {
        Files.deleteIfExists(directory.resolve("profile.db-wal"));
        Files.deleteIfExists(directory.resolve("profile.db-shm"));
        Files.deleteIfExists(directory.resolve("profile.db"));
        Files.deleteIfExists(directory.resolve("source.pprof"));
        Files.deleteIfExists(directory);
      }
    } catch (IOException | SQLException failure) {
      LoggerFactory.getLogger(PprofSource.class)
          .warn("Could not remove temporary pprof data at {}", directory, failure);
    }
  }
}
