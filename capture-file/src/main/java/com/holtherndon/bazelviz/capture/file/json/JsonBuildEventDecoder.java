package com.holtherndon.bazelviz.capture.file.json;

import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildEvent;
import com.google.devtools.build.lib.exec.Protos;
import com.google.protobuf.TypeRegistry;
import com.google.protobuf.util.JsonFormat;
import com.holtherndon.bazelviz.core.event.DecodeStatus;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Decodes one raw JSON BEP record into a {@code BuildEvent}.
 *
 * <p>Bazel writes BEP JSON in protobuf's canonical JSON encoding, so decoding goes through {@link
 * JsonFormat} rather than any hand-rolled mapping.
 *
 * <h2>Why it parses twice</h2>
 *
 * <p>{@code JsonFormat.Parser.ignoringUnknownFields()} makes unknown fields disappear silently,
 * which is precisely the outcome plan 21.5 forbids: a newer Bazel adds a field, this build drops
 * it, and nothing tells the user. So the decoder first parses strictly. Only if that fails does it
 * retry leniently, and success on the retry is what proves the strict failure was an unknown field
 * rather than malformed input — no error-message sniffing involved. The record is then flagged
 * {@link DecodeStatus#UNKNOWN_FIELDS} with the strict parser's complaint attached, so the
 * unrecognized content is visible rather than lost. The second parse only happens for records that
 * actually carry unknown fields, so a file from a matching Bazel pays nothing.
 *
 * <p>Either way the caller keeps the raw bytes (ADR-004); this class never becomes the only
 * representation of a record.
 *
 * <h2>Type registry</h2>
 *
 * <p>{@code ActionExecuted.strategy_details} is a {@code google.protobuf.Any}, and protobuf-JSON
 * cannot decode an {@code Any} without a registry that can resolve its type URL. The registry below
 * covers {@code build_event_stream} and its transitive imports plus {@code tools.protos.SpawnExec},
 * which the proto documents as the default strategy-details type. An {@code Any} of some other type
 * still fails to decode, and correctly reports {@link DecodeStatus#FAILED} with the bytes
 * preserved, rather than pretending to have understood the record.
 *
 * <p>Instances are stateless and safe to share across threads.
 */
public final class JsonBuildEventDecoder {

  private static final TypeRegistry TYPE_REGISTRY =
      TypeRegistry.newBuilder()
          // add(Descriptor) registers the whole containing file and its
          // transitive imports, so this covers every BEP payload type.
          .add(BuildEvent.getDescriptor())
          .add(Protos.SpawnExec.getDescriptor())
          .build();

  private static final JsonFormat.Parser STRICT =
      JsonFormat.parser().usingTypeRegistry(TYPE_REGISTRY);

  private static final JsonFormat.Parser LENIENT =
      JsonFormat.parser().usingTypeRegistry(TYPE_REGISTRY).ignoringUnknownFields();

  /** Decodes the whole array. */
  public JsonDecodeResult decode(byte[] raw) {
    Objects.requireNonNull(raw, "raw");
    return decode(raw, 0, raw.length);
  }

  /**
   * Decodes {@code raw[offset, offset + length)}, which must be exactly one JSON object.
   *
   * <p>Never throws for bad content: a record this build cannot interpret is a reportable fact
   * about the file, not an exception the import should die on (plan 21.3, "catch parse errors per
   * frame where boundaries are known").
   */
  public JsonDecodeResult decode(byte[] raw, int offset, int length) {
    Objects.requireNonNull(raw, "raw");
    Objects.checkFromIndexSize(offset, length, raw.length);

    BuildEvent.Builder strict = BuildEvent.newBuilder();
    String strictComplaint;
    try {
      STRICT.merge(reader(raw, offset, length), strict);
      return new JsonDecodeResult(DecodeStatus.OK, strict.build(), null);
    } catch (IOException | RuntimeException e) {
      strictComplaint = describe(e);
    }

    // A fresh builder: the failed strict merge may have left the first one
    // partially populated.
    BuildEvent.Builder lenient = BuildEvent.newBuilder();
    try {
      LENIENT.merge(reader(raw, offset, length), lenient);
      return new JsonDecodeResult(DecodeStatus.UNKNOWN_FIELDS, lenient.build(), strictComplaint);
    } catch (IOException | RuntimeException e) {
      return new JsonDecodeResult(DecodeStatus.FAILED, null, describe(e));
    }
  }

  private static Reader reader(byte[] raw, int offset, int length) {
    // Streamed through a Reader rather than materialized as a String: the
    // record bytes already exist, and a String would double the footprint
    // of the largest record for no benefit.
    return new InputStreamReader(
        new ByteArrayInputStream(raw, offset, length), StandardCharsets.UTF_8);
  }

  private static String describe(Throwable t) {
    String message = t.getMessage();
    return message == null || message.isBlank() ? t.getClass().getName() : message;
  }
}
