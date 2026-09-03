package com.holtherndon.bazelviz.ui.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.holtherndon.bazelviz.core.id.SessionId;
import com.holtherndon.bazelviz.format.portable.BvizLimits;
import com.holtherndon.bazelviz.format.portable.BvizWriter;
import com.holtherndon.bazelviz.format.session.ManagedSession;
import com.holtherndon.bazelviz.format.session.ManagedSessionLayout;
import com.holtherndon.bazelviz.format.session.SessionManager;
import com.holtherndon.bazelviz.storage.SessionDatabase;
import com.holtherndon.bazelviz.storage.schema.MigrationRunner;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Statement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Sessions that are incomplete, damaged, or somebody else's.
 *
 * <h2>What a release gate has to know</h2>
 *
 * <p>Plan 21.3 and plan 22.4 both come down to the same requirement: a session this application
 * cannot read must produce a sentence a user can act on, not a stack trace and not a view showing
 * zeros. The three ways it happens are an import that stopped early, a file that is not what it
 * claims to be, and a session written by a different build — and the last of those is the one that
 * arrives inside an archive somebody sent.
 *
 * <h2>An imported database is untrusted</h2>
 *
 * <p>Plan 22.4 says to treat an imported SQLite database as untrusted and to prefer rebuilding from
 * raw files unless the archive format and the database schema pass validation. The archive format
 * is validated by {@code BvizReader}; the schema is validated here, on open, by {@code
 * SqliteSessionSource.requireCurrentSchema}. What this application does <em>not</em> do is rebuild
 * silently — see {@code docs/phase10-audit.md} for why refusing with the remedy is the better
 * answer.
 */
final class DamagedSessionTest {

  private static final long CREATED = 1_700_000_000_000_000L;

  @TempDir Path tempDir;

  private SessionManager sessions;
  private Path root;

  @BeforeEach
  void createSession() throws Exception {
    sessions = new SessionManager(tempDir.resolve("sessions"), "0.1.0");
    ManagedSession session = sessions.create(SessionId.random());
    root = session.root();
  }

  private void writeDatabase() throws Exception {
    try (SessionDatabase database =
        SessionDatabase.open(ManagedSessionLayout.at(root).databaseFile())) {
      MigrationRunner.standard().migrate(database);
    }
  }

  @Test
  @DisplayName("a session whose import never created a database says so, and how to fix it")
  void noDatabase() {
    assertThatThrownBy(() -> SqliteSessionSource.open(sessions, root))
        .isInstanceOf(SessionDataException.class)
        .hasMessageContaining("has no session.sqlite")
        .hasMessageContaining("import was interrupted")
        .hasMessageContaining("Resume or re-import");
  }

  @Test
  @DisplayName("a database that is not a database is refused, not opened")
  void notADatabaseAtAll() throws Exception {
    // The shape a hostile or truncated archive delivers: the right name,
    // the wrong bytes.
    Files.write(
        ManagedSessionLayout.at(root).databaseFile(),
        "this is not a SQLite file, it is a note".getBytes(StandardCharsets.UTF_8));

    assertThatThrownBy(() -> SqliteSessionSource.open(sessions, root))
        .isInstanceOf(SessionDataException.class);
  }

  @Test
  @DisplayName("a database from an older build is refused with the remedy, not migrated")
  void olderSchema() throws Exception {
    writeDatabase();
    try (SessionDatabase database =
            SessionDatabase.open(ManagedSessionLayout.at(root).databaseFile());
        Statement statement = database.writerConnection().createStatement()) {
      statement.executeUpdate(
          "UPDATE schema_metadata SET value = '1' WHERE key = 'schema_version'");
    }

    // Opening is read-only: a view is not a licence to rewrite the file the
    // user opened, so this refuses and names the rebuild rather than
    // migrating in place.
    assertThatThrownBy(() -> SqliteSessionSource.open(sessions, root))
        .isInstanceOf(SessionDataException.class)
        .hasMessageContaining("indexed by an older build")
        .hasMessageContaining("Import its source again")
        .hasMessageContaining("nothing is lost");
  }

  @Test
  @DisplayName("a database from a newer build is refused rather than misread")
  void newerSchema() throws Exception {
    writeDatabase();
    try (SessionDatabase database =
            SessionDatabase.open(ManagedSessionLayout.at(root).databaseFile());
        Statement statement = database.writerConnection().createStatement()) {
      statement.executeUpdate(
          "UPDATE schema_metadata SET value = '9999' WHERE key = 'schema_version'");
    }

    assertThatThrownBy(() -> SqliteSessionSource.open(sessions, root))
        .isInstanceOf(SessionDataException.class)
        .hasMessageContaining("newer build")
        .hasMessageContaining("Upgrade, or open it with the build that wrote it");
  }

  @Test
  @DisplayName("an archive carrying an unreadable database imports, and refuses to open")
  void anArchiveIsValidatedAndItsDatabaseIsStillUntrusted() throws Exception {
    // The archive is well-formed -- correct checksums, no zip-slip, nothing
    // outside a session -- and its payload is still not something this
    // build can read. Both checks are needed and neither substitutes for
    // the other.
    writeDatabase();
    try (SessionDatabase database =
            SessionDatabase.open(ManagedSessionLayout.at(root).databaseFile());
        Statement statement = database.writerConnection().createStatement()) {
      statement.executeUpdate(
          "UPDATE schema_metadata SET value = '9999' WHERE key = 'schema_version'");
    }
    Path archive = tempDir.resolve("from-elsewhere.bviz");
    BvizWriter.write(root, archive, BvizWriter.Options.complete("t"), "0.1.0", CREATED);

    Path library = tempDir.resolve("library");
    ArchiveImport.Result imported = ArchiveImport.into(archive, library, BvizLimits.defaults());
    assertThat(Files.isDirectory(imported.sessionRoot())).isTrue();

    SessionManager other = new SessionManager(library, "0.1.0");
    assertThatThrownBy(() -> SqliteSessionSource.open(other, imported.sessionRoot()))
        .isInstanceOf(SessionDataException.class)
        .hasMessageContaining("newer build");
  }

  @Test
  @DisplayName("a session directory with no manifest is not a session")
  void noManifest() throws Exception {
    Files.delete(ManagedSessionLayout.at(root).manifestFile());

    assertThat(OpenRequest.classify(root).kind()).isEqualTo(OpenRequest.Kind.UNSUPPORTED);
    assertThat(OpenRequest.classify(root).describeUnsupported()).contains("not a session");
  }

  @Test
  @DisplayName("a truncated manifest is refused with the file named")
  void truncatedManifest() throws Exception {
    writeDatabase();
    Path manifest = ManagedSessionLayout.at(root).manifestFile();
    String text = Files.readString(manifest);
    Files.writeString(manifest, text.substring(0, text.length() / 2));

    assertThatThrownBy(() -> SqliteSessionSource.open(sessions, root))
        .isInstanceOf(SessionDataException.class)
        .hasMessageContaining("manifest");
  }
}
