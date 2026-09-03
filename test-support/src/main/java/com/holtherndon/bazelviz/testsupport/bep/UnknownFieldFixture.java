package com.holtherndon.bazelviz.testsupport.bep;

import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildEvent;
import com.google.protobuf.CodedOutputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Iterator;

/**
 * Forward-compatibility fixtures: BEP records carrying fields this build's generated protos have
 * never heard of.
 *
 * <h2>Why the bytes are edited rather than faked</h2>
 *
 * Plan 21.5 requires unknown fields to survive: preserved in the raw journal, flagged in {@code
 * bep_events.has_unknown_fields}, and reindexable later by a newer app version. A fixture built
 * from a stand-in field the schema does know proves none of that. So the unknown field is appended
 * directly to the serialized message as a real wire-format record with a field number outside the
 * schema. Protobuf's concatenation property makes that a legal encoding of "the same message plus
 * one more field", which is exactly what a future Bazel emits, and a parser then genuinely lands it
 * in {@code Message.getUnknownFields()}.
 *
 * <p>Field numbers used are far above anything {@code BuildEvent} defines, and outside protobuf's
 * reserved 19000-19999 range.
 */
public final class UnknownFieldFixture {

  /** Unknown length-delimited (wire type 2) field number appended to {@code BuildEvent}. */
  public static final int UNKNOWN_STRING_FIELD_NUMBER = 9998;

  /** Unknown varint (wire type 0) field number appended to {@code BuildEvent}. */
  public static final int UNKNOWN_VARINT_FIELD_NUMBER = 9999;

  /** Value carried by the unknown length-delimited field. */
  public static final String UNKNOWN_STRING_VALUE = "field-from-a-newer-bazel";

  /** Value carried by the unknown varint field. */
  public static final long UNKNOWN_VARINT_VALUE = 20260821L;

  private static final int WIRE_TYPE_VARINT = 0;
  private static final int WIRE_TYPE_LENGTH_DELIMITED = 2;

  private UnknownFieldFixture() {}

  /**
   * Serializes {@code event} and appends both unknown fields, returning the message bytes (no
   * length prefix).
   */
  public static byte[] eventBytesWithUnknownFields(BuildEvent event) {
    byte[] bytes = event.toByteArray();
    bytes =
        withUnknownLengthDelimitedField(
            bytes,
            UNKNOWN_STRING_FIELD_NUMBER,
            UNKNOWN_STRING_VALUE.getBytes(StandardCharsets.UTF_8));
    return withUnknownVarintField(bytes, UNKNOWN_VARINT_FIELD_NUMBER, UNKNOWN_VARINT_VALUE);
  }

  /** Appends a wire-type-2 field to already-serialized message bytes. */
  public static byte[] withUnknownLengthDelimitedField(
      byte[] messageBytes, int fieldNumber, byte[] value) {
    ByteArrayOutputStream buffer =
        new ByteArrayOutputStream(messageBytes.length + value.length + 12);
    buffer.writeBytes(messageBytes);
    try {
      CodedOutputStream coded = CodedOutputStream.newInstance(buffer);
      coded.writeTag(fieldNumber, WIRE_TYPE_LENGTH_DELIMITED);
      coded.writeUInt32NoTag(value.length);
      coded.writeRawBytes(value);
      coded.flush();
    } catch (IOException e) {
      throw new IllegalStateException("in-memory encoding cannot fail", e);
    }
    return buffer.toByteArray();
  }

  /** Appends a wire-type-0 field to already-serialized message bytes. */
  public static byte[] withUnknownVarintField(byte[] messageBytes, int fieldNumber, long value) {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream(messageBytes.length + 16);
    buffer.writeBytes(messageBytes);
    try {
      CodedOutputStream coded = CodedOutputStream.newInstance(buffer);
      coded.writeTag(fieldNumber, WIRE_TYPE_VARINT);
      coded.writeUInt64NoTag(value);
      coded.flush();
    } catch (IOException e) {
      throw new IllegalStateException("in-memory encoding cannot fail", e);
    }
    return buffer.toByteArray();
  }

  /**
   * Writes a complete binary BEP file in which the event at {@code eventIndex} carries the unknown
   * fields and every other event is untouched.
   *
   * @return the byte offset of the damaged event's payload, so a test can name it
   */
  public static long writeBinaryStreamWithUnknownFieldEvent(
      Path dest, SyntheticBepStream stream, int eventIndex) throws IOException {
    if (eventIndex < 0 || eventIndex >= stream.eventCount()) {
      throw new IllegalArgumentException(
          "eventIndex " + eventIndex + " outside [0, " + stream.eventCount() + ")");
    }
    Path parent = dest.getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    long offset = 0;
    long unknownPayloadOffset = -1;
    try (OutputStream out =
        new BufferedOutputStream(
            Files.newOutputStream(
                dest,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE),
            1 << 16)) {
      Iterator<BuildEvent> events = stream.iterator();
      int index = 0;
      while (events.hasNext()) {
        BuildEvent event = events.next();
        byte[] payload =
            index == eventIndex ? eventBytesWithUnknownFields(event) : event.toByteArray();
        int prefixLength = LengthDelimitedFrames.varintLengthOf(payload.length);
        if (index == eventIndex) {
          unknownPayloadOffset = offset + prefixLength;
        }
        writeVarint32(out, payload.length);
        out.write(payload);
        offset += prefixLength + payload.length;
        index++;
      }
    }
    return unknownPayloadOffset;
  }

  /**
   * Inserts a member that no BEP schema defines into a protobuf-JSON object, for the JSON-side
   * forward-compatibility case. The insertion happens right after the opening brace, so it works
   * for both compact and pretty layouts.
   *
   * @param jsonValue a JSON value literal, e.g. {@code "\"abc\""} or {@code "17"}
   */
  public static String jsonWithUnknownMember(String json, String memberName, String jsonValue) {
    int brace = json.indexOf('{');
    if (brace < 0) {
      throw new IllegalArgumentException("not a JSON object: " + json);
    }
    return json.substring(0, brace + 1)
        + "\""
        + memberName
        + "\":"
        + jsonValue
        + ","
        + json.substring(brace + 1);
  }

  private static void writeVarint32(OutputStream out, int value) throws IOException {
    int v = value;
    while ((v & ~0x7F) != 0) {
      out.write((v & 0x7F) | 0x80);
      v >>>= 7;
    }
    out.write(v);
  }
}
