package com.holtherndon.bazelviz.bepcodec;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.Aborted;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildEvent;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildFinished;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildStarted;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.NamedSetOfFiles;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.Progress;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.TargetComplete;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.TestResult;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class BepPayloadTypeTest {

    @Test
    void mapsPayloadsToTheirWireFieldNumbers() {
        assertThat(BepPayloadType.of(BuildEvent.newBuilder()
                        .setProgress(Progress.getDefaultInstance()).build()))
                .isEqualTo(3);
        assertThat(BepPayloadType.of(BuildEvent.newBuilder()
                        .setAborted(Aborted.getDefaultInstance()).build()))
                .isEqualTo(4);
        assertThat(BepPayloadType.of(BuildEvent.newBuilder()
                        .setStarted(BuildStarted.getDefaultInstance()).build()))
                .isEqualTo(5);
        assertThat(BepPayloadType.of(BuildEvent.newBuilder()
                        .setCompleted(TargetComplete.getDefaultInstance()).build()))
                .isEqualTo(8);
        assertThat(BepPayloadType.of(BuildEvent.newBuilder()
                        .setTestResult(TestResult.getDefaultInstance()).build()))
                .isEqualTo(10);
        assertThat(BepPayloadType.of(BuildEvent.newBuilder()
                        .setFinished(BuildFinished.getDefaultInstance()).build()))
                .isEqualTo(14);
        assertThat(BepPayloadType.of(BuildEvent.newBuilder()
                        .setNamedSetOfFiles(NamedSetOfFiles.getDefaultInstance()).build()))
                .isEqualTo(15);
    }

    @Test
    void anEventWithNoPayloadIsNoneNotAGuess() {
        assertThat(BepPayloadType.NONE).isZero();
        assertThat(BepPayloadType.of(BuildEvent.getDefaultInstance()))
                .isEqualTo(BepPayloadType.NONE);
        // The last_message sentinel legitimately carries no payload.
        assertThat(BepPayloadType.of(BuildEvent.newBuilder().setLastMessage(true).build()))
                .isEqualTo(BepPayloadType.NONE);
        assertThat(BepPayloadType.isKnown(BepPayloadType.NONE)).isFalse();
        assertThat(BepPayloadType.name(BepPayloadType.NONE)).isEqualTo("none");
    }

    @Test
    void theStoredNumberIsTheFieldNumberNotTheEnumOrdinal() {
        // If this ever reduced to an ordinal, PROGRESS would be 0 and every
        // already-indexed session would start meaning something else.
        for (BuildEvent.PayloadCase payloadCase : BuildEvent.PayloadCase.values()) {
            assertThat(BepPayloadType.of(payloadCase))
                    .as("number of %s", payloadCase)
                    .isEqualTo(payloadCase.getNumber());
        }
        assertThat(BuildEvent.PayloadCase.PROGRESS.getNumber())
                .isNotEqualTo(BuildEvent.PayloadCase.PROGRESS.ordinal());
    }

    @Test
    void everyPayloadFieldNumberIsDistinctAndRoundTrips() {
        Set<Integer> seen = new HashSet<>();
        for (BuildEvent.PayloadCase payloadCase : BuildEvent.PayloadCase.values()) {
            int eventType = BepPayloadType.of(payloadCase);
            assertThat(seen.add(eventType)).as("%s is distinct", payloadCase).isTrue();
            assertThat(BepPayloadType.caseOf(eventType)).isEqualTo(payloadCase);
            if (payloadCase != BuildEvent.PayloadCase.PAYLOAD_NOT_SET) {
                assertThat(BepPayloadType.isKnown(eventType)).isTrue();
                assertThat(BepPayloadType.name(eventType)).isNotBlank();
            }
        }
    }

    @Test
    void namesReadAsTheProtoFieldNames() {
        assertThat(BepPayloadType.name(3)).isEqualTo("progress");
        assertThat(BepPayloadType.name(10)).isEqualTo("testResult");
        assertThat(BepPayloadType.name(15)).isEqualTo("namedSetOfFiles");
    }

    @Test
    void aPayloadFromANewerBazelIsNamedByItsNumberNotGuessedAt() {
        int fromTheFuture = 909;

        assertThat(BepPayloadType.caseOf(fromTheFuture)).isNull();
        assertThat(BepPayloadType.isKnown(fromTheFuture)).isFalse();
        assertThat(BepPayloadType.name(fromTheFuture)).isEqualTo("payload#909");
    }

    @Test
    void theDecoderAndThePayloadAccessorAgreeOnARoundTrippedEvent() {
        BuildEvent event = BuildEvent.newBuilder()
                .setCompleted(TargetComplete.getDefaultInstance())
                .build();

        DecodeResult result = BepEventDecoder.withDefaults().decode(event.toByteArray());

        assertThat(BepPayloadType.of(result.requireEvent())).isEqualTo(8);
        assertThat(BepPayloadType.name(BepPayloadType.of(result.requireEvent())))
                .isEqualTo("completed");
    }
}
