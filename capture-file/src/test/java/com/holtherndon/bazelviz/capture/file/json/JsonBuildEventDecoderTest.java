package com.holtherndon.bazelviz.capture.file.json;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildEvent;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import com.holtherndon.bazelviz.core.event.DecodeStatus;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

final class JsonBuildEventDecoderTest {

  private final JsonBuildEventDecoder decoder = new JsonBuildEventDecoder();

  @Test
  void decodesEveryFixtureEventBackToItself() throws InvalidProtocolBufferException {
    JsonFormat.Printer printer = JsonFormat.printer().omittingInsignificantWhitespace();

    for (BuildEvent expected : JsonFixtures.sampleEvents()) {
      JsonDecodeResult result = decoder.decode(utf8(printer.print(expected)));

      assertThat(result.status()).isEqualTo(DecodeStatus.OK);
      assertThat(result.event()).isEqualTo(expected);
      assertThat(result.detail()).isEmpty();
    }
  }

  @Test
  void acceptsBothProtoAndLowerCamelCaseFieldNames() {
    // Bazel emits lowerCamelCase, but protobuf-JSON also permits the
    // original proto names and older tooling has produced them.
    JsonDecodeResult camel =
        decoder.decode(utf8("{\"id\":{\"progress\":{\"opaqueCount\":3}},\"lastMessage\":true}"));
    JsonDecodeResult snake =
        decoder.decode(utf8("{\"id\":{\"progress\":{\"opaque_count\":3}},\"last_message\":true}"));

    assertThat(camel.status()).isEqualTo(DecodeStatus.OK);
    assertThat(snake.status()).isEqualTo(DecodeStatus.OK);
    assertThat(camel.event()).isEqualTo(snake.event());
    assertThat(camel.event().getLastMessage()).isTrue();
  }

  @Test
  void anUnknownFieldIsToleratedAndNamedRatherThanSilentlyDropped() {
    JsonDecodeResult result =
        decoder.decode(
            utf8(
                "{\"id\":{\"progress\":{\"opaqueCount\":1}},"
                    + "\"progress\":{\"stderr\":\"kept\"},"
                    + "\"fieldFromANewerBazel\":[1,2,3]}"));

    assertThat(result.status()).isEqualTo(DecodeStatus.UNKNOWN_FIELDS);
    assertThat(result.event().getProgress().getStderr()).isEqualTo("kept");
    // The point of the strict-then-lenient double parse: the field's name
    // survives in the diagnostic even though the value cannot be stored.
    assertThat(result.detail()).get().asString().contains("fieldFromANewerBazel");
  }

  @Test
  void anUnknownFieldNestedInsideAPayloadIsAlsoTolerated() {
    JsonDecodeResult result =
        decoder.decode(
            utf8(
                "{\"id\":{\"progress\":{\"opaqueCount\":1}},"
                    + "\"progress\":{\"stderr\":\"kept\",\"stderrDigest\":\"abc\"}}"));

    assertThat(result.status()).isEqualTo(DecodeStatus.UNKNOWN_FIELDS);
    assertThat(result.event().getProgress().getStderr()).isEqualTo("kept");
  }

  @Test
  void reportsFailureRatherThanThrowingForContentItCannotDecode() {
    List<String> undecodable =
        List.of(
            "{\"id\":{\"progress\":{\"opaqueCount\":\"seven\"}}}",
            "{\"id\":\"not an object\"}",
            "{\"id\":{\"progress\":{\"opaqueCount\":1}}",
            "not json");

    for (String json : undecodable) {
      JsonDecodeResult result = decoder.decode(utf8(json));

      assertThat(result.status()).as("decoding %s", json).isEqualTo(DecodeStatus.FAILED);
      assertThat(result.decodedEvent()).isEmpty();
      assertThat(result.detail()).isPresent();
    }
  }

  @Test
  void anEmptyObjectIsAValidBuildEventWithNoPayload() {
    JsonDecodeResult result = decoder.decode(utf8("{}"));

    assertThat(result.status()).isEqualTo(DecodeStatus.OK);
    assertThat(result.event().getPayloadCase()).isEqualTo(BuildEvent.PayloadCase.PAYLOAD_NOT_SET);
  }

  @Test
  void decodesASliceOfALargerArray() {
    byte[] backing = utf8("XXXX{\"id\":{\"progress\":{\"opaqueCount\":5}}}YYYY");

    JsonDecodeResult result = decoder.decode(backing, 4, backing.length - 8);

    assertThat(result.status()).isEqualTo(DecodeStatus.OK);
    assertThat(result.event().getId().getProgress().getOpaqueCount()).isEqualTo(5);
  }

  private static byte[] utf8(String s) {
    return s.getBytes(StandardCharsets.UTF_8);
  }
}
