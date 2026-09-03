package com.holtherndon.bazelviz.storage.events;

import java.util.Objects;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * One row of {@code import_diagnostics}: something the import needs the user to know about.
 *
 * <p>Every limit hit, every byte dropped and every record skipped becomes one of these. Plan rule
 * 12 — never silently truncate, sample, drop or override — is enforced by this table existing and
 * being shown, so a producer that discards data without writing a diagnostic has a defect.
 *
 * <p>{@code segmentIndex} and {@code byteOffset} are {@link OptionalInt} and {@link OptionalLong}
 * rather than {@code int}/{@code long} because a diagnostic about the session as a whole has no
 * position, and "offset 0" is a real, very different statement from "no offset" (plan 11.4).
 */
public record ImportDiagnostic(
    DiagnosticSeverity severity,
    String code,
    String message,
    OptionalInt segmentIndex,
    OptionalLong byteOffset,
    long atMicros) {

  public ImportDiagnostic {
    Objects.requireNonNull(severity, "severity");
    Objects.requireNonNull(code, "code");
    Objects.requireNonNull(message, "message");
    Objects.requireNonNull(segmentIndex, "segmentIndex");
    Objects.requireNonNull(byteOffset, "byteOffset");
    if (code.isBlank()) {
      throw new IllegalArgumentException("diagnostic code must not be blank");
    }
  }

  /** A diagnostic that is not tied to a position in a journal segment. */
  public static ImportDiagnostic general(
      DiagnosticSeverity severity, String code, String message, long atMicros) {
    return new ImportDiagnostic(
        severity, code, message, OptionalInt.empty(), OptionalLong.empty(), atMicros);
  }

  /** A diagnostic located at a byte offset inside a journal segment. */
  public static ImportDiagnostic at(
      DiagnosticSeverity severity,
      String code,
      String message,
      int segmentIndex,
      long byteOffset,
      long atMicros) {
    return new ImportDiagnostic(
        severity,
        code,
        message,
        OptionalInt.of(segmentIndex),
        OptionalLong.of(byteOffset),
        atMicros);
  }
}
