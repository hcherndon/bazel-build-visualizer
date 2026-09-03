package com.holtherndon.bazelviz.ui.events;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildEvent;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildEventId;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildStarted;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.Progress;
import com.google.devtools.build.v1.BuildEvent.BuildComponentStreamFinished;
import com.google.devtools.build.v1.OrderedBuildEvent;
import com.google.devtools.build.v1.PublishBuildToolEventStreamRequest;
import com.google.devtools.build.v1.StreamId;
import com.google.protobuf.Any;
import com.holtherndon.bazelviz.core.event.DecodeStatus;
import com.holtherndon.bazelviz.core.journal.JournalFormat.SourceKind;
import com.holtherndon.bazelviz.ui.session.RawPayload;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The hex dump and the decoded pane, including the limits they disclose. */
class RawPayloadRendererTest {

  @Test
  @DisplayName("the hex dump shows offset, bytes and printable ASCII")
  void hexDumpLayout() {
    String dump = RawPayloadRenderer.hexDump("Hello".getBytes(StandardCharsets.US_ASCII));

    assertThat(dump).startsWith("00000000  48 65 6c 6c 6f");
    assertThat(dump).contains("|Hello|");
  }

  @Test
  @DisplayName("an empty payload says so rather than rendering nothing at all")
  void emptyPayload() {
    assertThat(RawPayloadRenderer.hexDump(new byte[0])).isEqualTo("(0 bytes)");
  }

  @Test
  @DisplayName("a dump that hits its limit says exactly how much it withheld")
  void truncationIsDisclosed() {
    byte[] bytes = new byte[100];

    String dump = RawPayloadRenderer.hexDump(bytes, 32);

    assertThat(dump).contains("68 of 100 bytes are not shown");
    assertThat(dump).contains("limited to 32 bytes");
    assertThat(dump).contains("stored complete in the journal");
  }

  @Test
  @DisplayName("undecodable bytes produce an explanation, a failure detail, and still the bytes")
  void undecodableBinaryRecord() {
    RawPayload payload = new RawPayload(new byte[] {0x08}, SourceKind.BEP_BINARY);

    RawPayloadRenderer.Rendered rendered = RawPayloadRenderer.render(payload, DecodeStatus.FAILED);

    assertThat(rendered.decodeFailure()).isPresent();
    assertThat(rendered.text()).contains("could not be decoded as a BuildEvent");
    assertThat(rendered.notices()).isEmpty();
  }

  @Test
  @DisplayName("a stored status that disagrees with a fresh decode is reported, not resolved")
  void statusDisagreementIsSurfaced() {
    RawPayload payload = new RawPayload(new byte[] {0x08}, SourceKind.BEP_BINARY);

    RawPayloadRenderer.Rendered rendered = RawPayloadRenderer.render(payload, DecodeStatus.OK);

    assertThat(rendered.notices())
        .anySatisfy(
            notice ->
                assertThat(notice).contains("recorded this record as OK").contains("says FAILED"));
  }

  @Test
  @DisplayName("a JSON record is shown as the text it is")
  void jsonRecordIsShownVerbatim() {
    String json = "{\"id\":{\"started\":{}}}";
    RawPayload payload =
        new RawPayload(json.getBytes(StandardCharsets.UTF_8), SourceKind.BEP_JSON_RECORD);

    RawPayloadRenderer.Rendered rendered = RawPayloadRenderer.render(payload, DecodeStatus.OK);

    assertThat(rendered.text()).isEqualTo(json);
    assertThat(rendered.decodeFailure()).isEmpty();
  }

  @Test
  @DisplayName("a BES envelope is unwrapped and shows the build event inside it")
  void besEnvelopeShowsTheInnerEvent() {
    BuildEvent inner =
        BuildEvent.newBuilder()
            .setId(
                BuildEventId.newBuilder()
                    .setStarted(BuildEventId.BuildStartedId.getDefaultInstance()))
            .setStarted(BuildStarted.newBuilder().setUuid("inv-1").setCommand("build"))
            .build();
    RawPayload payload = new RawPayload(toolEventEnvelope(inner, 7), SourceKind.BES_ENVELOPE);

    RawPayloadRenderer.Rendered rendered = RawPayloadRenderer.render(payload, DecodeStatus.OK);

    // The envelope's own facts, then the event the user actually selected.
    assertThat(rendered.text())
        .contains("BAZEL_EVENT")
        .contains("sequence 7")
        .contains("build-1234");
    assertThat(rendered.text()).contains("uuid: \"inv-1\"");
    assertThat(rendered.decodeFailure()).isEmpty();
    assertThat(rendered.notices()).isEmpty();
  }

  @Test
  @DisplayName(
      "a stream-control envelope says it holds no event, and does not call that a limitation")
  void streamControlEnvelopeIsNotReportedAsAShortfall() {
    PublishBuildToolEventStreamRequest request =
        PublishBuildToolEventStreamRequest.newBuilder()
            .setOrderedBuildEvent(
                OrderedBuildEvent.newBuilder()
                    .setStreamId(
                        StreamId.newBuilder().setBuildId("build-1234").setInvocationId("inv-1"))
                    .setSequenceNumber(42)
                    .setEvent(
                        com.google.devtools.build.v1.BuildEvent.newBuilder()
                            .setComponentStreamFinished(
                                BuildComponentStreamFinished.getDefaultInstance())))
            .build();
    RawPayload payload = new RawPayload(request.toByteArray(), SourceKind.BES_ENVELOPE);

    RawPayloadRenderer.Rendered rendered = RawPayloadRenderer.render(payload, DecodeStatus.OK);

    assertThat(rendered.text()).contains("COMPONENT_STREAM_FINISHED").contains("no build event");
    assertThat(rendered.decodeFailure()).isEmpty();
    // Nothing was withheld, so nothing is disclosed: this envelope has no
    // inner event by definition, which is a fact about the record and not a
    // display limit.
    assertThat(rendered.notices()).isEmpty();
  }

  @Test
  @DisplayName("bytes that are not a BES request still show, with the failure named")
  void malformedEnvelopeStillShowsItsBytes() {
    RawPayload payload = new RawPayload(new byte[] {1, 2, 3}, SourceKind.BES_ENVELOPE);

    RawPayloadRenderer.Rendered rendered = RawPayloadRenderer.render(payload, DecodeStatus.FAILED);

    assertThat(rendered.text()).contains("could not be read");
    assertThat(rendered.decodeFailure()).isPresent();
  }

  @Test
  @DisplayName("a progress event's console text is read structurally, not out of the rendering")
  void consoleTextIsStructural() {
    BuildEvent event =
        BuildEvent.newBuilder()
            .setProgress(
                Progress.newBuilder()
                    .setStderr("ERROR: BUILD.bazel:3:5: syntax error at 'outs'\n")
                    .setStdout("Loading: 0 packages loaded\n"))
            .build();

    RawPayloadRenderer.Console console =
        RawPayloadRenderer.console(new RawPayload(event.toByteArray(), SourceKind.BEP_BINARY));

    assertThat(console.absence()).isEmpty();
    assertThat(console.stderr()).isEqualTo("ERROR: BUILD.bazel:3:5: syntax error at 'outs'\n");
    assertThat(console.stdout()).isEqualTo("Loading: 0 packages loaded\n");
    assertThat(console.hasText()).isTrue();
  }

  @Test
  @DisplayName("a BES-transported progress event answers exactly as a file-imported one")
  void consoleTextThroughAnEnvelope() {
    BuildEvent inner =
        BuildEvent.newBuilder().setProgress(Progress.newBuilder().setStderr("boom\n")).build();

    RawPayloadRenderer.Console console =
        RawPayloadRenderer.console(
            new RawPayload(toolEventEnvelope(inner, 3), SourceKind.BES_ENVELOPE));

    assertThat(console.stderr()).isEqualTo("boom\n");
    assertThat(console.absence()).isEmpty();
  }

  @Test
  @DisplayName("a JSON record's console text is read too, rather than quietly skipped")
  void consoleTextFromJson() {
    String json = "{\"progress\":{\"stderr\":\"boom\\n\"}}";

    RawPayloadRenderer.Console console =
        RawPayloadRenderer.console(
            new RawPayload(json.getBytes(StandardCharsets.UTF_8), SourceKind.BEP_JSON_RECORD));

    assertThat(console.stderr()).isEqualTo("boom\n");
    assertThat(console.absence()).isEmpty();
  }

  @Test
  @DisplayName("an event that is not a progress event says so, rather than reporting no output")
  void nonProgressEventExplainsItself() {
    BuildEvent event =
        BuildEvent.newBuilder().setStarted(BuildStarted.newBuilder().setUuid("inv-1")).build();

    RawPayloadRenderer.Console console =
        RawPayloadRenderer.console(new RawPayload(event.toByteArray(), SourceKind.BEP_BINARY));

    assertThat(console.hasText()).isFalse();
    assertThat(console.absence())
        .hasValueSatisfying(why -> assertThat(why).contains("not a progress event"));
  }

  @Test
  @DisplayName("bytes that will not decode become a stated absence, never an exception")
  void undecodableBytesAreAnAbsence() {
    RawPayloadRenderer.Console console =
        RawPayloadRenderer.console(new RawPayload(new byte[] {0x08}, SourceKind.BEP_BINARY));

    assertThat(console.hasText()).isFalse();
    assertThat(console.absence())
        .hasValueSatisfying(why -> assertThat(why).contains("could not be decoded"));
  }

  private static byte[] toolEventEnvelope(BuildEvent inner, long sequence) {
    return PublishBuildToolEventStreamRequest.newBuilder()
        .setOrderedBuildEvent(
            OrderedBuildEvent.newBuilder()
                .setStreamId(
                    StreamId.newBuilder()
                        .setBuildId("build-1234")
                        .setInvocationId("inv-1")
                        .setComponent(StreamId.BuildComponent.TOOL))
                .setSequenceNumber(sequence)
                .setEvent(
                    com.google.devtools.build.v1.BuildEvent.newBuilder()
                        .setBazelEvent(
                            Any.newBuilder()
                                .setTypeUrl("type.googleapis.com/build_event_stream.BuildEvent")
                                .setValue(inner.toByteString()))))
        .build()
        .toByteArray();
  }
}
