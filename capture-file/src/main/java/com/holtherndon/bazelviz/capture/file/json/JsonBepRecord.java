package com.holtherndon.bazelviz.capture.file.json;

import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildEvent;
import com.holtherndon.bazelviz.core.event.DecodeStatus;
import java.util.Objects;
import java.util.Optional;

/**
 * One top-level JSON object read out of a BEP JSON file, delivered with the exact bytes it occupied
 * and where it occupied them.
 *
 * <p>{@code rawBytes} is the verbatim slice of the source file, from the opening brace through the
 * closing brace inclusive. It is the record's primary representation, not a debugging aid: it is
 * what gets journaled, and re-serializing {@link #event()} instead would silently discard anything
 * this build's protos do not understand (ADR-004).
 *
 * <p>{@code byteOffset} and {@code byteLength} are exact, so {@code file[byteOffset, byteOffset +
 * byteLength)} is byte-for-byte {@code rawBytes} and re-parsing that slice alone yields the same
 * record.
 *
 * <p>For efficiency the array is handed over rather than copied on access. Callers must not mutate
 * it, and must copy it if they intend to retain it beyond the listener callback. Consequently
 * {@code equals} and {@code hashCode} compare the array by identity; compare {@link #rawBytes()}
 * with {@code java.util.Arrays#equals} when value semantics are wanted.
 *
 * @param ordinal 0-based index of this record among all structurally delimited records in the
 *     source, including any that were too large to deliver, so ordinals stay aligned with file
 *     order
 * @param byteOffset absolute offset of the opening brace in the source
 * @param byteLength number of bytes from the opening through the closing brace
 * @param rawBytes the record's bytes, verbatim and unmodified
 * @param decodeStatus what happened when the bytes were decoded
 * @param event the decoded event, or null when none is available
 * @param decodeMessage detail about a decode problem, or null when there was none
 */
public record JsonBepRecord(
    long ordinal,
    long byteOffset,
    int byteLength,
    byte[] rawBytes,
    DecodeStatus decodeStatus,
    BuildEvent event,
    String decodeMessage) {

  public JsonBepRecord {
    Objects.requireNonNull(rawBytes, "rawBytes");
    Objects.requireNonNull(decodeStatus, "decodeStatus");
    if (ordinal < 0) {
      throw new IllegalArgumentException("ordinal must not be negative: " + ordinal);
    }
    if (byteOffset < 0) {
      throw new IllegalArgumentException("byteOffset must not be negative: " + byteOffset);
    }
    if (byteLength != rawBytes.length) {
      throw new IllegalArgumentException(
          "byteLength " + byteLength + " disagrees with rawBytes.length " + rawBytes.length);
    }
    if (decodeStatus.hasEvent() == (event == null)) {
      throw new IllegalArgumentException(
          "decode status "
              + decodeStatus
              + " is inconsistent with "
              + (event == null ? "a missing" : "a present")
              + " event");
    }
  }

  /** The decoded event when one is available; empty is a real answer, not a failure to look. */
  public Optional<BuildEvent> decodedEvent() {
    return Optional.ofNullable(event);
  }

  /** Detail about a decode problem, when there was one. */
  public Optional<String> decodeDetail() {
    return Optional.ofNullable(decodeMessage);
  }

  /** Absolute offset one past this record's last byte. */
  public long endOffset() {
    return byteOffset + byteLength;
  }
}
