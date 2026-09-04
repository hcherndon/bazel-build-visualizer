package com.holtherndon.bazelviz.capture.bes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.grpc.KnownLength;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

final class RawBytesMarshallerTest {

  @Test
  @DisplayName("an unknown-length stream is read no farther than max plus one")
  void unknownLengthInputIsBoundedBeforeAllocation() {
    RawBytesMarshaller marshaller = new RawBytesMarshaller(4);
    CountingInputStream input = new CountingInputStream(100);

    assertThatThrownBy(() -> marshaller.parse(input))
        .isInstanceOfSatisfying(
            StatusRuntimeException.class,
            failure -> {
              assertThat(failure.getStatus().getCode()).isEqualTo(Status.Code.RESOURCE_EXHAUSTED);
              assertThat(failure.getStatus().getDescription())
                  .contains("at least 5 bytes")
                  .contains("4-byte limit");
            });
    assertThat(input.bytesRead).isEqualTo(5);
  }

  @Test
  @DisplayName("a payload exactly at the limit is preserved byte for byte")
  void exactLimitIsAccepted() {
    byte[] payload = {0, 1, 2, 3};

    assertThat(new RawBytesMarshaller(4).parse(new ByteArrayInputStream(payload)))
        .containsExactly(payload);
  }

  @Test
  @DisplayName("a known oversized stream is rejected before it is read")
  void knownOversizeIsRejectedBeforeRead() {
    KnownInputStream input = new KnownInputStream(new byte[5]);

    assertThatThrownBy(() -> new RawBytesMarshaller(4).parse(input))
        .isInstanceOfSatisfying(
            StatusRuntimeException.class,
            failure ->
                assertThat(failure.getStatus().getCode())
                    .isEqualTo(Status.Code.RESOURCE_EXHAUSTED));
    assertThat(input.readCalled).isFalse();
  }

  private static final class CountingInputStream extends InputStream {

    private final int length;
    private int bytesRead;

    private CountingInputStream(int length) {
      this.length = length;
    }

    @Override
    public int read() {
      if (bytesRead >= length) {
        return -1;
      }
      bytesRead++;
      return bytesRead & 0xff;
    }
  }

  private static final class KnownInputStream extends ByteArrayInputStream implements KnownLength {

    private boolean readCalled;

    private KnownInputStream(byte[] bytes) {
      super(bytes);
    }

    @Override
    public synchronized int read(byte[] bytes, int offset, int length) {
      readCalled = true;
      return super.read(bytes, offset, length);
    }

    @Override
    public synchronized int read() {
      readCalled = true;
      return super.read();
    }
  }
}
