package com.holtherndon.bazelviz.format.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

class ImportCheckpointTest {

    private static ImportCheckpoint sample() {
        return new ImportCheckpoint(1, 3, 12_345_678L, OptionalLong.of(987_654L), 987_655L, 987_600L,
                1_755_800_000_000_000L);
    }

    @Test
    void jsonRoundTripsExactly() throws Exception {
        ImportCheckpoint original = sample();

        ImportCheckpoint parsed = ImportCheckpoint.fromJson(original.toJson());

        assertThat(parsed).isEqualTo(original);
        assertThat(parsed.position()).isEqualTo(new JournalPosition(3, 12_345_678L));
        assertThat(parsed.normalizationBacklog()).isEqualTo(55L);
    }

    @Test
    void theSerializedShapeMatchesThePhaseOneContract() {
        String json = sample().toJson();

        assertThat(json)
                .contains("\"formatVersion\": 1")
                .contains("\"segmentIndex\": 3")
                .contains("\"segmentOffset\": 12345678")
                .contains("\"lastSequence\": 987654")
                .contains("\"framesWritten\": 987655")
                .contains("\"eventsNormalized\": 987600")
                .contains("\"updatedAtMicros\": 1755800000000000");
    }

    @Test
    void anAbsentSequenceIsWrittenAsNullNotZero() throws Exception {
        ImportCheckpoint nothingJournaledYet =
                ImportCheckpoint.at(JournalPosition.startOfSegment(0), OptionalLong.empty(), 0, 0, 7L);

        String json = nothingJournaledYet.toJson();
        assertThat(json).contains("\"lastSequence\": null");
        assertThat(ImportCheckpoint.fromJson(json).lastSequence()).isEmpty();
    }

    @Test
    void sequenceZeroSurvivesAsARealValue() throws Exception {
        ImportCheckpoint atZero =
                ImportCheckpoint.at(JournalPosition.startOfSegment(0), OptionalLong.of(0L), 1, 0, 7L);

        assertThat(ImportCheckpoint.fromJson(atZero.toJson()).lastSequence()).hasValue(0L);
    }

    @Test
    void moreEventsNormalizedThanFramesWrittenIsImpossibleAndRejected() {
        assertThatThrownBy(() -> new ImportCheckpoint(1, 0, 32L, OptionalLong.of(1L), 5L, 6L, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot exceed framesWritten");
    }

    @Test
    void anOffsetInsideTheSegmentHeaderIsRejected() {
        assertThatThrownBy(() -> new ImportCheckpoint(1, 0, 8L, OptionalLong.empty(), 0L, 0L, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("segmentOffset");
    }

    @Test
    void anUnknownFormatVersionIsRejected() {
        assertThatThrownBy(() -> new ImportCheckpoint(2, 0, 32L, OptionalLong.empty(), 0L, 0L, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("format version");
    }

    @Test
    void aHalfWrittenCheckpointIsRejectedRatherThanPartiallyBelieved() {
        String full = sample().toJson();
        // Every prefix short of the closing brace is a half-written file.
        int lastBrace = full.lastIndexOf('}');
        for (int cut = 1; cut <= lastBrace; cut++) {
            String partial = full.substring(0, cut);
            assertThatThrownBy(() -> ImportCheckpoint.fromJson(partial))
                    .as("prefix of length %d must not parse", cut)
                    .isInstanceOf(CheckpointFormatException.class);
        }
    }

    @Test
    void garbageIsRejected() {
        assertThatThrownBy(() -> ImportCheckpoint.fromJson("\0\0\0\0"))
                .isInstanceOf(CheckpointFormatException.class);
        assertThatThrownBy(() -> ImportCheckpoint.fromJson("[1,2,3]"))
                .isInstanceOf(CheckpointFormatException.class);
        assertThatThrownBy(() -> ImportCheckpoint.fromJson("{}"))
                .isInstanceOf(CheckpointFormatException.class)
                .hasMessageContaining("missing");
        assertThatThrownBy(() -> ImportCheckpoint.fromJson(sample().toJson() + "{trailing}"))
                .isInstanceOf(CheckpointFormatException.class)
                .hasMessageContaining("trailing content");
    }

    @Test
    void aNonNumericFieldIsRejectedRatherThanCoerced() {
        String json = """
                {
                  "formatVersion": 1,
                  "segmentIndex": "3",
                  "segmentOffset": 64,
                  "lastSequence": null,
                  "framesWritten": 0,
                  "eventsNormalized": 0,
                  "updatedAtMicros": 1
                }
                """;

        assertThatThrownBy(() -> ImportCheckpoint.fromJson(json))
                .isInstanceOf(CheckpointFormatException.class)
                .hasMessageContaining("expected a number");
    }

    @Test
    void aRequiredFieldWrittenAsNullIsRejected() {
        String json = """
                {
                  "formatVersion": 1,
                  "segmentIndex": null,
                  "segmentOffset": 64,
                  "lastSequence": null,
                  "framesWritten": 0,
                  "eventsNormalized": 0,
                  "updatedAtMicros": 1
                }
                """;

        assertThatThrownBy(() -> ImportCheckpoint.fromJson(json))
                .isInstanceOf(CheckpointFormatException.class)
                .hasMessageContaining("is null");
    }

    @Test
    void aDuplicatedKeyIsRejectedBecauseItsValueWouldBeAmbiguous() {
        String json = "{\"formatVersion\": 1, \"formatVersion\": 2, \"segmentIndex\": 0,"
                + " \"segmentOffset\": 64, \"lastSequence\": null, \"framesWritten\": 0,"
                + " \"eventsNormalized\": 0, \"updatedAtMicros\": 1}";

        assertThatThrownBy(() -> ImportCheckpoint.fromJson(json))
                .isInstanceOf(CheckpointFormatException.class)
                .hasMessageContaining("duplicate key");
    }

    @Test
    void anUnknownFieldFromANewerBuildIsIgnoredRatherThanFatal() throws Exception {
        String json = "{\"formatVersion\": 1, \"segmentIndex\": 0, \"segmentOffset\": 64,"
                + " \"lastSequence\": 5, \"framesWritten\": 6, \"eventsNormalized\": 6,"
                + " \"updatedAtMicros\": 1, \"somethingFromTheFuture\": 42}";

        ImportCheckpoint parsed = ImportCheckpoint.fromJson(json);

        assertThat(parsed.lastSequence()).hasValue(5L);
        assertThat(parsed.framesWritten()).isEqualTo(6L);
    }

    @Test
    void anOversizedSegmentIndexIsRejectedRatherThanNarrowed() {
        String json = "{\"formatVersion\": 1, \"segmentIndex\": 4294967296, \"segmentOffset\": 64,"
                + " \"lastSequence\": null, \"framesWritten\": 0, \"eventsNormalized\": 0,"
                + " \"updatedAtMicros\": 1}";

        assertThatThrownBy(() -> ImportCheckpoint.fromJson(json))
                .isInstanceOf(CheckpointFormatException.class)
                .hasMessageContaining("out of int range");
    }
}
