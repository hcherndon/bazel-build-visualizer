package com.holtherndon.bazelviz.storage.events;

import java.util.Objects;

/**
 * A stored {@code import_diagnostics} row with its id, so diagnostics can be paged with the same
 * keyset anchor everything else uses.
 */
public record DiagnosticEntry(long id, ImportDiagnostic diagnostic) {

  public DiagnosticEntry {
    Objects.requireNonNull(diagnostic, "diagnostic");
  }
}
