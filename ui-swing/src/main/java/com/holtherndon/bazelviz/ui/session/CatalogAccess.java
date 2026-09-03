package com.holtherndon.bazelviz.ui.session;

import com.holtherndon.bazelviz.storage.catalog.SessionCatalog;
import java.nio.file.Path;
import java.util.Objects;
import javax.swing.SwingUtilities;

/** Serializes access to the one process-global session catalog. */
public final class CatalogAccess {

  private static final Object PROCESS_LOCK = new Object();

  private CatalogAccess() {}

  /**
   * Opens the catalog and runs one complete operation while excluding other windows' catalog work.
   * The operation is blocking and may not run on the EDT.
   */
  public static <T> T withCatalog(Path directory, Operation<T> operation) throws Exception {
    Objects.requireNonNull(directory, "directory");
    Objects.requireNonNull(operation, "operation");
    if (SwingUtilities.isEventDispatchThread()) {
      throw new IllegalStateException("session catalog I/O must not run on the EDT");
    }
    synchronized (PROCESS_LOCK) {
      try (SessionCatalog catalog = SessionCatalog.open(directory)) {
        return operation.run(catalog);
      }
    }
  }

  /** One complete catalog transaction from the application's point of view. */
  @FunctionalInterface
  public interface Operation<T> {
    T run(SessionCatalog catalog) throws Exception;
  }
}
