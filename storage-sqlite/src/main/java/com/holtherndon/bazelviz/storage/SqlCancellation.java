package com.holtherndon.bazelviz.storage;

import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;

/** Dispatches JDBC statement cancellation without making the requesting thread perform I/O. */
public final class SqlCancellation {

  private SqlCancellation() {}

  /** Cancels the captured statement on a dedicated virtual thread, if one is active. */
  public static void request(Statement statement, String threadName) {
    Objects.requireNonNull(threadName, "threadName");
    if (statement == null) {
      return;
    }
    Thread.ofVirtual()
        .name(threadName)
        .start(
            () -> {
              try {
                statement.cancel();
              } catch (SQLException ignored) {
                // It completed after capture, or the driver declined cancellation. The caller's
                // generation or cancellation epoch still rejects the superseded result.
              }
            });
  }
}
