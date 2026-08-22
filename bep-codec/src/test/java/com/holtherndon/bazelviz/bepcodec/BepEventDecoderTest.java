package com.holtherndon.bazelviz.bepcodec;

import com.holtherndon.bazelviz.core.event.DecodeStatus;
import static com.holtherndon.bazelviz.bepcodec.WireBytes.bytesField;
import static com.holtherndon.bazelviz.bepcodec.WireBytes.stringField;
import static com.holtherndon.bazelviz.bepcodec.WireBytes.varintField;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildEvent;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildEventId;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.Progress;
import com.google.protobuf.ByteString;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Optional;
import java.util.Random;
import org.junit.jupiter.api.Test;

final class BepEventDecoderTest {

    /** Field numbers inside {@code BuildEvent}. */
    private static final int EVENT_ID_FIELD = 1;

    private static final int CHILDREN_FIELD = 2;

    private static final int EVENT_PROGRESS_FIELD = 3;

    /** A field number no BEP schema declares — a stand-in for a future Bazel. */
    private static final int FUTURE_FIELD = 909;

    private final BepEventDecoder decoder = BepEventDecoder.withDefaults();

    private static BuildEvent sampleEvent() {
        return BuildEvent.newBuilder()
                .setId(BuildEventId.newBuilder()
                        .setTargetCompleted(BuildEventId.TargetCompletedId.newBuilder()
                                .setLabel("//foo:bar")))
                .setProgress(Progress.newBuilder().setStdout("out").setStderr("err"))
                .setLastMessage(true)
                .build();
    }

    @Test
    void roundTripsACleanEvent() {
        BuildEvent original = sampleEvent();

        DecodeResult result = decoder.decode(original.toByteArray());

        assertThat(result.status()).isEqualTo(DecodeStatus.OK);
        assertThat(result.hasUnknownFields()).isFalse();
        assertThat(result.failureDetail()).isEmpty();
        assertThat(result.requireEvent()).isEqualTo(original);
    }

    @Test
    void decodesFromEveryInputShape() {
        BuildEvent original = sampleEvent();
        byte[] wire = original.toByteArray();

        assertThat(decoder.decode(ByteString.copyFrom(wire)).requireEvent()).isEqualTo(original);
        assertThat(decoder.decode(ByteBuffer.wrap(wire)).requireEvent()).isEqualTo(original);

        byte[] embedded = new byte[wire.length + 9];
        System.arraycopy(wire, 0, embedded, 4, wire.length);
        assertThat(decoder.decode(embedded, 4, wire.length).requireEvent()).isEqualTo(original);
    }

    @Test
    void leavesTheCallersBufferPositionAlone() {
        ByteBuffer buffer = ByteBuffer.wrap(sampleEvent().toByteArray());

        decoder.decode(buffer);

        assertThat(buffer.position()).isZero();
    }

    @Test
    void reportsAnOutOfRangeSliceRatherThanThrowing() {
        byte[] wire = sampleEvent().toByteArray();

        DecodeResult result = decoder.decode(wire, 4, wire.length);

        assertThat(result.status()).isEqualTo(DecodeStatus.FAILED);
        assertThat(result.failureDetail()).get().asString().contains("outside a");
    }

    @Test
    void aTruncatedPayloadFailsWithDetailAndNoException() {
        // Cut inside the length-delimited `id`: the declared length outruns the
        // bytes present, which is what a short journal tail looks like.
        byte[] truncated = Arrays.copyOf(sampleEvent().toByteArray(), 4);

        DecodeResult result = decoder.decode(truncated);

        assertThat(result.status()).isEqualTo(DecodeStatus.FAILED);
        assertThat(result.isFailed()).isTrue();
        assertThat(result.event()).isEmpty();
        assertThat(result.failureDetail()).get().asString()
                .contains("could not parse a BuildEvent from " + truncated.length + " bytes");
    }

    @Test
    void aLengthThatOverrunsTheBufferFailsWithDetail() {
        // Field 1 (id), length-delimited, declaring 127 bytes that are not there.
        byte[] corrupt = {0x0A, 0x7F};

        DecodeResult result = decoder.decode(corrupt);

        assertThat(result.status()).isEqualTo(DecodeStatus.FAILED);
        assertThat(result.failureDetail()).get().asString().isNotBlank();
    }

    @Test
    void anInvalidTagFailsWithDetail() {
        // Tag 0 is never legal; protobuf rejects it outright.
        byte[] corrupt = {0x00, 0x01, 0x02};

        DecodeResult result = decoder.decode(corrupt);

        assertThat(result.status()).isEqualTo(DecodeStatus.FAILED);
        assertThat(result.failureDetail()).get().asString().isNotBlank();
    }

    @Test
    void randomBytesNeverThrow() {
        Random random = new Random(20260821L);
        for (int i = 0; i < 5_000; i++) {
            byte[] garbage = new byte[1 + random.nextInt(64)];
            random.nextBytes(garbage);
            assertThatCode(() -> decoder.decode(garbage)).doesNotThrowAnyException();
        }
    }

    @Test
    void anEmptyPayloadIsAValidEmptyEventNotAFailure() {
        DecodeResult result = decoder.decode(new byte[0]);

        assertThat(result.status()).isEqualTo(DecodeStatus.OK);
        assertThat(result.requireEvent()).isEqualTo(BuildEvent.getDefaultInstance());
    }

    @Test
    void aPayloadOverTheSizeLimitIsRefusedWithoutParsing() {
        BepEventDecoder small = new BepEventDecoder(8, BepEventDecoder.UnknownFieldScan.DEEP);

        DecodeResult result = small.decode(sampleEvent().toByteArray());

        assertThat(result.status()).isEqualTo(DecodeStatus.FAILED);
        assertThat(result.failureDetail()).get().asString()
                .contains("exceeds the 8-byte protobuf message limit");
    }

    @Test
    void topLevelUnknownFieldsAreReportedAndRetained() {
        ByteString wire = sampleEvent().toByteString()
                .concat(varintField(FUTURE_FIELD, 7))
                .concat(stringField(FUTURE_FIELD + 1, "from a newer bazel"));

        DecodeResult result = decoder.decode(wire);

        assertThat(result.status()).isEqualTo(DecodeStatus.UNKNOWN_FIELDS);
        assertThat(result.hasUnknownFields()).isTrue();
        assertThat(result.isParsed()).isTrue();

        BuildEvent event = result.requireEvent();
        // Retained, not stripped (plan 21.5): the known fields still decode and
        // the unrecognised ones survive a re-serialization unchanged.
        assertThat(event.getProgress().getStdout()).isEqualTo("out");
        assertThat(event.getUnknownFields().asMap().keySet())
                .containsExactlyInAnyOrder(FUTURE_FIELD, FUTURE_FIELD + 1);
        assertThat(event.getUnknownFields().getField(FUTURE_FIELD).getVarintList())
                .containsExactly(7L);
        assertThat(event.toByteString()).isEqualTo(wire);
    }

    @Test
    void unknownFieldsNestedInAPayloadAreReportedByADeepScan() {
        ByteString futureProgress = Progress.newBuilder().setStdout("out").build().toByteString()
                .concat(varintField(FUTURE_FIELD, 1));
        ByteString wire = bytesField(EVENT_PROGRESS_FIELD, futureProgress);

        DecodeResult result = decoder.decode(wire);

        assertThat(result.status()).isEqualTo(DecodeStatus.UNKNOWN_FIELDS);
        assertThat(result.requireEvent().getUnknownFields().asMap()).isEmpty();
        assertThat(result.requireEvent().getProgress().getUnknownFields().asMap())
                .containsOnlyKeys(FUTURE_FIELD);
        assertThat(result.requireEvent().toByteString()).isEqualTo(wire);
    }

    @Test
    void unknownFieldsInsideARepeatedIdAreReportedByADeepScan() {
        ByteString futureChildId = BuildEventId.newBuilder()
                .setProgress(BuildEventId.ProgressId.newBuilder().setOpaqueCount(3))
                .build()
                .toByteString()
                .concat(varintField(FUTURE_FIELD, 1));
        ByteString wire = bytesField(EVENT_ID_FIELD, ByteString.EMPTY)
                .concat(bytesField(CHILDREN_FIELD, futureChildId));

        DecodeResult result = decoder.decode(wire);

        assertThat(result.status()).isEqualTo(DecodeStatus.UNKNOWN_FIELDS);
    }

    @Test
    void aTopLevelScanDoesNotClaimToHaveSeenNestedUnknownFields() {
        BepEventDecoder shallow =
                new BepEventDecoder(BepEventDecoder.DEFAULT_MAX_MESSAGE_BYTES,
                        BepEventDecoder.UnknownFieldScan.TOP_LEVEL);
        ByteString futureProgress = Progress.newBuilder().setStdout("out").build().toByteString()
                .concat(varintField(FUTURE_FIELD, 1));
        ByteString wire = bytesField(EVENT_PROGRESS_FIELD, futureProgress);

        DecodeResult shallowResult = shallow.decode(wire);
        DecodeResult deepResult = decoder.decode(wire);

        assertThat(shallowResult.status()).isEqualTo(DecodeStatus.OK);
        assertThat(deepResult.status()).isEqualTo(DecodeStatus.UNKNOWN_FIELDS);
        // Either way the bytes themselves are intact; only the diagnostic differs.
        assertThat(shallowResult.requireEvent().toByteString()).isEqualTo(wire);
    }

    @Test
    void decodeResultRejectsInconsistentCombinations() {
        assertThatThrownBy(() -> new DecodeResult(
                        DecodeStatus.FAILED,
                        Optional.of(sampleEvent()),
                        Optional.of("detail")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DecodeResult(DecodeStatus.OK, Optional.empty(), Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DecodeResult.failed("detail").requireEvent())
                .isInstanceOf(IllegalStateException.class);
    }

    /**
     * A {@code TargetComplete} event routinely announces thousands of children.
     * The scan has to reach the last of them, and has to stay quiet when they
     * are all recognised.
     */
    @Test
    void aWideEventIsScannedExhaustively() {
        BuildEvent.Builder clean = BuildEvent.newBuilder();
        for (int i = 0; i < 10_000; i++) {
            clean.addChildren(BuildEventId.newBuilder()
                    .setProgress(BuildEventId.ProgressId.newBuilder().setOpaqueCount(i)));
        }

        assertThat(decoder.decode(clean.build().toByteArray()).status()).isEqualTo(DecodeStatus.OK);

        ByteString lastChildFromTheFuture = BuildEventId.newBuilder()
                .setProgress(BuildEventId.ProgressId.newBuilder().setOpaqueCount(10_000))
                .build()
                .toByteString()
                .concat(varintField(FUTURE_FIELD, 1));
        ByteString wire = clean.build().toByteString()
                .concat(bytesField(CHILDREN_FIELD, lastChildFromTheFuture));

        assertThat(decoder.decode(wire).status()).isEqualTo(DecodeStatus.UNKNOWN_FIELDS);
    }
}
