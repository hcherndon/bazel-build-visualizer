package com.holtherndon.bazelviz.bepcodec;

import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildEvent;
import com.google.protobuf.ByteString;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Message;
import com.holtherndon.bazelviz.core.event.DecodeStatus;
import com.holtherndon.bazelviz.core.journal.JournalFormat;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Turns raw BEP payload bytes into a {@link BuildEvent}, reporting rather than throwing when the
 * bytes are unusable.
 *
 * <p>Three project rules shape this class:
 *
 * <ul>
 *   <li><b>A bad event is a diagnostic, not the end of the session</b> (plan 21.3). Every failure
 *       path returns {@link DecodeResult#failed} with an explanation the importer can write to
 *       {@code import_diagnostics}; nothing escapes as an exception.
 *   <li><b>Unknown fields are preserved, not stripped</b> (plan 21.5). protobuf-java retains
 *       unrecognised fields on the parsed message, so the message returned here still round-trips
 *       them. This class only <em>detects</em> them so the import can surface an "unknown event
 *       fields" diagnostic and mark the row for later reindexing.
 *   <li><b>Message size is bounded</b> (plan 21.3). A payload larger than the configured maximum is
 *       rejected as a failure rather than allocated.
 * </ul>
 *
 * <p>The decoder never re-serializes: the raw bytes remain the source of truth in the journal
 * (ADR-004), and this class only reads them.
 *
 * <p>Instances are immutable and stateless; a single decoder may be shared by any number of
 * threads.
 */
public final class BepEventDecoder {

  /**
   * Default ceiling on one decoded message, matched to the journal's frame ceiling so a payload
   * that the journal accepted is never rejected here for a different reason.
   */
  public static final int DEFAULT_MAX_MESSAGE_BYTES = JournalFormat.DEFAULT_MAX_PAYLOAD_BYTES;

  /** How hard the decoder looks for fields it does not recognise. */
  public enum UnknownFieldScan {
    /**
     * Inspect only the outermost {@code BuildEvent}. Cheap, but blind to the common case: a newer
     * Bazel adding a field inside a payload message.
     */
    TOP_LEVEL,

    /**
     * Walk the whole message tree, including repeated and map entries. The default, because a new
     * field almost always appears nested.
     */
    DEEP
  }

  private final int maxMessageBytes;
  private final UnknownFieldScan unknownFieldScan;

  /**
   * @param maxMessageBytes largest payload this decoder will parse; a larger payload is reported as
   *     a failure, never allocated
   * @param unknownFieldScan how deeply to look for unrecognised fields
   */
  public BepEventDecoder(int maxMessageBytes, UnknownFieldScan unknownFieldScan) {
    if (maxMessageBytes <= 0) {
      throw new IllegalArgumentException("maxMessageBytes must be positive: " + maxMessageBytes);
    }
    this.maxMessageBytes = maxMessageBytes;
    this.unknownFieldScan = Objects.requireNonNull(unknownFieldScan, "unknownFieldScan");
  }

  /** A decoder with the default size ceiling and a deep unknown-field scan. */
  public static BepEventDecoder withDefaults() {
    return new BepEventDecoder(DEFAULT_MAX_MESSAGE_BYTES, UnknownFieldScan.DEEP);
  }

  public int maxMessageBytes() {
    return maxMessageBytes;
  }

  public UnknownFieldScan unknownFieldScan() {
    return unknownFieldScan;
  }

  /** Decodes the whole array. */
  public DecodeResult decode(byte[] payload) {
    return decode(payload, 0, payload.length);
  }

  /**
   * Decodes {@code length} bytes of {@code payload} starting at {@code offset} — the shape a
   * journal reader hands over, so no copy is needed.
   */
  public DecodeResult decode(byte[] payload, int offset, int length) {
    // Written as a subtraction so a hostile offset+length cannot overflow
    // past the check and reach the parser as a negative range.
    if (offset < 0 || length < 0 || length > payload.length - offset) {
      return DecodeResult.failed(
          "payload range ["
              + offset
              + ", "
              + (offset + length)
              + ") lies outside a "
              + payload.length
              + "-byte array");
    }
    if (length > maxMessageBytes) {
      return DecodeResult.failed(tooLargeDetail(length));
    }
    return finish(
        () -> {
          CodedInputStream input = CodedInputStream.newInstance(payload, offset, length);
          input.setSizeLimit(maxMessageBytes);
          return BuildEvent.parseFrom(input);
        },
        length);
  }

  /** Decodes a payload already held as a {@link ByteString}. */
  public DecodeResult decode(ByteString payload) {
    int length = payload.size();
    if (length > maxMessageBytes) {
      return DecodeResult.failed(tooLargeDetail(length));
    }
    return finish(
        () -> {
          CodedInputStream input = payload.newCodedInput();
          input.setSizeLimit(maxMessageBytes);
          return BuildEvent.parseFrom(input);
        },
        length);
  }

  /**
   * Decodes the remaining bytes of {@code payload}. The buffer's position is not advanced, so a
   * caller mapping a journal segment can decode a frame without disturbing its own cursor.
   */
  public DecodeResult decode(ByteBuffer payload) {
    ByteBuffer view = payload.duplicate();
    int length = view.remaining();
    if (length > maxMessageBytes) {
      return DecodeResult.failed(tooLargeDetail(length));
    }
    return finish(
        () -> {
          CodedInputStream input = CodedInputStream.newInstance(view);
          input.setSizeLimit(maxMessageBytes);
          return BuildEvent.parseFrom(input);
        },
        length);
  }

  private String tooLargeDetail(int length) {
    return "payload of "
        + length
        + " bytes exceeds the "
        + maxMessageBytes
        + "-byte protobuf message limit";
  }

  @FunctionalInterface
  private interface Parse {
    BuildEvent run() throws IOException;
  }

  /**
   * Runs a parse and classifies the outcome. Every checked and unchecked failure below {@code
   * Error} becomes a {@link DecodeStatus#FAILED} result: a malformed payload can make protobuf-java
   * raise {@code InvalidProtocolBufferException}, but a truncated length-delimited field can also
   * surface as an {@code IndexOutOfBoundsException} or a {@code NegativeArraySizeException} from
   * the underlying buffer, and none of those may reach the import loop.
   */
  private DecodeResult finish(Parse parse, int length) {
    BuildEvent event;
    try {
      event = parse.run();
    } catch (IOException | RuntimeException e) {
      return DecodeResult.failed(describeFailure(e, length));
    }
    boolean unknown;
    try {
      unknown =
          unknownFieldScan == UnknownFieldScan.DEEP
              ? containsUnknownFieldsDeep(event)
              : !event.getUnknownFields().asMap().isEmpty();
    } catch (RuntimeException e) {
      // The message parsed, so the event itself is good; only the scan
      // failed. Say so rather than claiming a clean decode.
      return DecodeResult.failed(
          "payload of "
              + length
              + " bytes parsed, but the unknown-field scan failed: "
              + describeThrowable(e));
    }
    return unknown ? DecodeResult.withUnknownFields(event) : DecodeResult.ok(event);
  }

  private static String describeFailure(Throwable e, int length) {
    return "could not parse a BuildEvent from " + length + " bytes: " + describeThrowable(e);
  }

  private static String describeThrowable(Throwable e) {
    String message = e.getMessage();
    return e.getClass().getSimpleName()
        + (message == null || message.isBlank() ? "" : ": " + message);
  }

  /**
   * True when {@code root} or anything reachable from it carries an unrecognised field.
   *
   * <p>Iterative on purpose: a hostile payload can nest messages up to protobuf's recursion limit,
   * and a recursive walk would trade a bounded parse failure for an unbounded {@code
   * StackOverflowError}.
   */
  static boolean containsUnknownFieldsDeep(Message root) {
    Deque<Message> pending = new ArrayDeque<>();
    pending.push(root);
    while (!pending.isEmpty()) {
      Message message = pending.pop();
      if (!message.getUnknownFields().asMap().isEmpty()) {
        return true;
      }
      for (Map.Entry<FieldDescriptor, Object> entry : message.getAllFields().entrySet()) {
        FieldDescriptor field = entry.getKey();
        if (field.getJavaType() != FieldDescriptor.JavaType.MESSAGE) {
          continue;
        }
        Object value = entry.getValue();
        if (field.isRepeated()) {
          // Map fields arrive here too, as repeated MapEntry messages.
          for (Object element : (List<?>) value) {
            pending.push((Message) element);
          }
        } else {
          pending.push((Message) value);
        }
      }
    }
    return false;
  }
}
