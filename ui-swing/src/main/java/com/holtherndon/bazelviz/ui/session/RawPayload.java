package com.holtherndon.bazelviz.ui.session;

import com.holtherndon.bazelviz.core.journal.JournalFormat.SourceKind;
import java.util.Arrays;
import java.util.Objects;

/**
 * The verbatim bytes of one journaled record, together with what kind of record they are.
 *
 * <p>The {@link SourceKind} travels with the bytes because the inspector cannot otherwise know how
 * to read them: a binary BEP payload is a {@code BuildEvent}, a JSON import record is protobuf-JSON
 * text, and a BES envelope is neither. Guessing by sniffing the first byte is exactly the kind of
 * assumption the format detector exists to avoid, and the journal frame already records the answer
 * (contract §1, source kind field).
 *
 * @param bytes the payload, exactly as it was received
 * @param sourceKind what the journal frame says these bytes are
 */
public record RawPayload(byte[] bytes, SourceKind sourceKind) {

  public RawPayload {
    Objects.requireNonNull(bytes, "bytes");
    Objects.requireNonNull(sourceKind, "sourceKind");
    bytes = bytes.clone();
  }

  @Override
  public byte[] bytes() {
    return bytes.clone();
  }

  /** Length in bytes, without copying. */
  public int length() {
    return bytes.length;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof RawPayload that
        && sourceKind == that.sourceKind
        && Arrays.equals(bytes, that.bytes);
  }

  @Override
  public int hashCode() {
    return 31 * sourceKind.hashCode() + Arrays.hashCode(bytes);
  }

  @Override
  public String toString() {
    return "RawPayload[" + sourceKind + ", " + bytes.length + " bytes]";
  }
}
