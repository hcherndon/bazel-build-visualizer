package com.holtherndon.bazelviz.storage.schema;

import java.io.Serial;
import java.sql.SQLException;

/**
 * The database on disk cannot be used by this build of the application.
 *
 * <p>The case that matters is a database written by a <em>newer</em> build. Downgrading it silently
 * is not an option: this build does not know which tables or columns the newer schema added, so
 * writing to it would produce a database that neither version can read. The session is refused with
 * an explicit message instead (plan 21.5 — unknown schema is surfaced, never guessed at).
 */
public final class SchemaVersionException extends SQLException {

  @Serial private static final long serialVersionUID = 1L;

  private final int foundVersion;
  private final int supportedVersion;

  private SchemaVersionException(String message, int foundVersion, int supportedVersion) {
    super(message);
    this.foundVersion = foundVersion;
    this.supportedVersion = supportedVersion;
  }

  static SchemaVersionException tooNew(int foundVersion, int supportedVersion) {
    return new SchemaVersionException(
        "session database schema version "
            + foundVersion
            + " was written by a newer build of Bazel Build Visualizer; this build"
            + " understands version "
            + supportedVersion
            + ". Refusing to open it — downgrading would corrupt data that this build"
            + " cannot see. Upgrade the application to open this session.",
        foundVersion,
        supportedVersion);
  }

  static SchemaVersionException unreadable(String detail, int supportedVersion) {
    return new SchemaVersionException(
        "session database schema version could not be determined: "
            + detail
            + ". Refusing to migrate it blindly.",
        -1,
        supportedVersion);
  }

  /** The version recorded in the database, or -1 when it could not be read. */
  public int foundVersion() {
    return foundVersion;
  }

  /** The newest version this build can apply. */
  public int supportedVersion() {
    return supportedVersion;
  }
}
