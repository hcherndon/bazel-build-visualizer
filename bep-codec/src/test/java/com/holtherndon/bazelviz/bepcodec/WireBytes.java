package com.holtherndon.bazelviz.bepcodec;

import com.google.protobuf.ByteString;
import com.google.protobuf.CodedOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Hand-writes protobuf wire bytes so tests can express messages this build's generated code cannot
 * describe — most importantly a field number no schema here declares, which is what a future Bazel
 * looks like on arrival.
 */
final class WireBytes {

  private WireBytes() {}

  /** {@code fieldNumber: value} as a varint field. */
  static ByteString varintField(int fieldNumber, long value) {
    return write(out -> out.writeUInt64(fieldNumber, value));
  }

  /** {@code fieldNumber: value} as a length-delimited field. */
  static ByteString bytesField(int fieldNumber, ByteString value) {
    return write(out -> out.writeBytes(fieldNumber, value));
  }

  /** {@code fieldNumber: value} as a length-delimited string field. */
  static ByteString stringField(int fieldNumber, String value) {
    return write(out -> out.writeString(fieldNumber, value));
  }

  private interface Emit {
    void accept(CodedOutputStream out) throws IOException;
  }

  private static ByteString write(Emit emit) {
    ByteString.Output sink = ByteString.newOutput();
    CodedOutputStream out = CodedOutputStream.newInstance(sink);
    try {
      emit.accept(out);
      out.flush();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return sink.toByteString();
  }
}
