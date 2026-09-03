package com.holtherndon.bazelviz.storage.schema;

import java.util.List;

/**
 * Schema version 3: name the test summary's timing columns for whose measurement they are.
 *
 * <h2>Why a version rather than an edit</h2>
 *
 * <p>Version 2 created {@code tests.first_start_micros} and {@code last_stop_micros} and wrote
 * {@code testSummary}'s figures into them. The names claimed the values came from the attempts, and
 * three comments and a UI label said so too — but the summary's window excludes failed retries and
 * understated real wall time by 13x on a measured six-attempt test (TS2). The elapsed time the
 * views show is now computed from {@code test_attempts}, and these columns keep Bazel's figures
 * under Bazel's name.
 *
 * <p>Renaming them in version 2's own DDL would have been quicker and would have broken the one
 * property {@link MigrationRunner} exists to guarantee: that the recorded version is a function of
 * the shape on disk. A session written before the change records version 2 and has the old columns;
 * a session written after would record version 2 and have the new ones, and nothing could tell them
 * apart.
 */
final class SchemaV3 {

  private SchemaV3() {}

  public static final int VERSION = 3;

  /**
   * {@code ALTER TABLE … RENAME COLUMN}, which SQLite has had since 3.25 and which rewrites the
   * referencing views and triggers there are none of.
   */
  public static final List<String> STATEMENTS =
      List.of(
          "ALTER TABLE tests RENAME COLUMN first_start_micros TO bazel_first_start_micros",
          "ALTER TABLE tests RENAME COLUMN last_stop_micros TO bazel_last_stop_micros");
}
