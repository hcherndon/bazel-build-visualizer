package com.holtherndon.bazelviz.testsupport.bep;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildEvent;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.UnknownFieldSet;
import com.google.protobuf.util.JsonFormat;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The forward-compatibility fixture has to carry a field protobuf genuinely
 * does not recognize. A test that only checks the fixture "looks different"
 * would pass for a fake, so these assert on
 * {@link com.google.protobuf.Message#getUnknownFields()} directly.
 */
final class UnknownFieldFixtureTest {

    @TempDir
    Path tempDir;

    @Test
    void appendedFieldsLandInUnknownFieldsWithTheirValuesIntact() throws InvalidProtocolBufferException {
        BuildEvent original = SyntheticBepStream.of(21).eventAt(8);

        byte[] bytes = UnknownFieldFixture.eventBytesWithUnknownFields(original);
        BuildEvent parsed = BuildEvent.parseFrom(bytes);

        UnknownFieldSet unknown = parsed.getUnknownFields();
        assertThat(unknown.asMap()).isNotEmpty();
        assertThat(unknown.asMap().keySet())
                .containsExactlyInAnyOrder(
                        UnknownFieldFixture.UNKNOWN_STRING_FIELD_NUMBER,
                        UnknownFieldFixture.UNKNOWN_VARINT_FIELD_NUMBER);

        assertThat(unknown.getField(UnknownFieldFixture.UNKNOWN_STRING_FIELD_NUMBER)
                        .getLengthDelimitedList())
                .singleElement()
                .satisfies(bs -> assertThat(bs.toStringUtf8())
                        .isEqualTo(UnknownFieldFixture.UNKNOWN_STRING_VALUE));
        assertThat(unknown.getField(UnknownFieldFixture.UNKNOWN_VARINT_FIELD_NUMBER).getVarintList())
                .containsExactly(UnknownFieldFixture.UNKNOWN_VARINT_VALUE);
    }

    @Test
    void knownFieldsAreUnaffectedAndTheEventStillDecodesNormally() throws InvalidProtocolBufferException {
        BuildEvent original = SyntheticBepStream.of(21).eventAt(11);

        BuildEvent parsed = BuildEvent.parseFrom(UnknownFieldFixture.eventBytesWithUnknownFields(original));

        assertThat(parsed.getId()).isEqualTo(original.getId());
        assertThat(parsed.getPayloadCase()).isEqualTo(original.getPayloadCase());
        assertThat(parsed.getAction()).isEqualTo(original.getAction());
        assertThat(parsed.getChildrenList()).isEqualTo(original.getChildrenList());
        // Not equal, precisely because the unknown fields are part of the message.
        assertThat(parsed).isNotEqualTo(original);
        assertThat(parsed.toBuilder().setUnknownFields(com.google.protobuf.UnknownFieldSet.getDefaultInstance())
                        .build())
                .isEqualTo(original);
    }

    @Test
    void reserializingPreservesTheUnknownFieldsByteForByte() throws InvalidProtocolBufferException {
        BuildEvent original = SyntheticBepStream.of(21).eventAt(8);
        byte[] bytes = UnknownFieldFixture.eventBytesWithUnknownFields(original);

        BuildEvent parsed = BuildEvent.parseFrom(bytes);
        byte[] roundTripped = parsed.toByteArray();

        assertThat(BuildEvent.parseFrom(roundTripped)).isEqualTo(parsed);
        assertThat(roundTripped).hasSize(bytes.length);
    }

    @Test
    void onlyTheChosenEventInTheStreamCarriesUnknownFields() throws IOException {
        SyntheticBepStream stream = SyntheticBepStream.of(60);
        Path file = tempDir.resolve("unknown.bep");
        int damagedIndex = 9;

        long payloadOffset =
                UnknownFieldFixture.writeBinaryStreamWithUnknownFieldEvent(file, stream, damagedIndex);

        List<LengthDelimitedFrames.Frame> frames = LengthDelimitedFrames.index(file).frames();
        assertThat(frames).hasSize(stream.eventCount());
        assertThat(frames.get(damagedIndex).payloadOffset()).isEqualTo(payloadOffset);

        byte[] all = Files.readAllBytes(file);
        for (LengthDelimitedFrames.Frame frame : frames) {
            byte[] payload = new byte[frame.payloadLength()];
            System.arraycopy(all, (int) frame.payloadOffset(), payload, 0, payload.length);
            BuildEvent parsed = BuildEvent.parseFrom(payload);

            if (frame.index() == damagedIndex) {
                assertThat(parsed.getUnknownFields().asMap()).isNotEmpty();
                assertThat(parsed.getId()).isEqualTo(stream.eventAt(damagedIndex).getId());
            } else {
                assertThat(parsed.getUnknownFields().asMap())
                        .as("event %d must be untouched", frame.index())
                        .isEmpty();
                assertThat(parsed).isEqualTo(stream.eventAt(frame.index()));
            }
        }
    }

    @Test
    void theUnknownFieldStreamIsLongerThanTheCleanOneByExactlyTheAddedBytes() throws IOException {
        SyntheticBepStream stream = SyntheticBepStream.of(40);
        Path clean = tempDir.resolve("clean.bep");
        Path unknown = tempDir.resolve("unknown.bep");

        long cleanBytes = BepBinaryWriter.write(clean, stream);
        UnknownFieldFixture.writeBinaryStreamWithUnknownFieldEvent(unknown, stream, 8);

        int added = UnknownFieldFixture.eventBytesWithUnknownFields(stream.eventAt(8)).length
                - stream.eventAt(8).getSerializedSize();
        long expectedPrefixGrowth = LengthDelimitedFrames.index(unknown).frames().get(8).varintLength()
                - LengthDelimitedFrames.index(clean).frames().get(8).varintLength();

        assertThat(Files.size(unknown)).isEqualTo(cleanBytes + added + expectedPrefixGrowth);
    }

    @Test
    void jsonUnknownMemberIsRejectedStrictlyAndAcceptedLeniently() throws IOException {
        String json = BepJsonWriter.toJson(
                SyntheticBepStream.of(21).eventAt(1), BepJsonWriter.Layout.ONE_OBJECT_PER_LINE);
        String withUnknown =
                UnknownFieldFixture.jsonWithUnknownMember(json, "fieldFromANewerBazel", "\"surprise\"");

        assertThat(withUnknown).contains("\"fieldFromANewerBazel\":\"surprise\"");
        assertThat(withUnknown.getBytes(StandardCharsets.UTF_8).length)
                .isGreaterThan(json.getBytes(StandardCharsets.UTF_8).length);

        BuildEvent.Builder strictTarget = BuildEvent.newBuilder();
        assertThatThrownBy(() -> JsonFormat.parser().merge(withUnknown, strictTarget))
                .isInstanceOf(InvalidProtocolBufferException.class);

        BuildEvent.Builder lenientTarget = BuildEvent.newBuilder();
        BepJsonWriter.lenientParser().merge(withUnknown, lenientTarget);
        assertThat(lenientTarget.build()).isEqualTo(SyntheticBepStream.of(21).eventAt(1));
    }
}
