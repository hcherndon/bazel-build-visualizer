package com.holtherndon.bazelviz.testsupport.bep;

import static com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildEvent.PayloadCase.ACTION;
import static com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildEvent.PayloadCase.BUILD_METRICS;
import static com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildEvent.PayloadCase.BUILD_TOOL_LOGS;
import static com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildEvent.PayloadCase.COMPLETED;
import static com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildEvent.PayloadCase.CONFIGURATION;
import static com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildEvent.PayloadCase.CONFIGURED;
import static com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildEvent.PayloadCase.EXPANDED;
import static com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildEvent.PayloadCase.FINISHED;
import static com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildEvent.PayloadCase.NAMED_SET_OF_FILES;
import static com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildEvent.PayloadCase.OPTIONS_PARSED;
import static com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildEvent.PayloadCase.PROGRESS;
import static com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildEvent.PayloadCase.STARTED;
import static com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildEvent.PayloadCase.STRUCTURED_COMMAND_LINE;
import static com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildEvent.PayloadCase.TEST_RESULT;
import static com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildEvent.PayloadCase.TEST_SUMMARY;
import static com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildEvent.PayloadCase.WORKSPACE_STATUS;
import static org.assertj.core.api.Assertions.assertThat;

import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildEvent;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildEventId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Ties the catalog to a hand-written expectation and to the generator. Three
 * independent statements of the same structure must agree: the literal list
 * below, the catalog's arithmetic, and the events the generator actually
 * builds. Two agreeing would prove nothing much; three do.
 */
final class BepFixtureCatalogTest {

    /**
     * The canonical 21-event stream: prologue, one target unit, the test unit,
     * no padding, epilogue. Written out by hand from the documented layout.
     */
    private static final List<BuildEvent.PayloadCase> EXPECTED_21 = List.of(
            STARTED,                    // 0
            PROGRESS,                   // 1
            OPTIONS_PARSED,             // 2
            STRUCTURED_COMMAND_LINE,    // 3  "original"
            STRUCTURED_COMMAND_LINE,    // 4  "canonical"
            WORKSPACE_STATUS,           // 5
            CONFIGURATION,              // 6
            EXPANDED,                   // 7  pattern
            CONFIGURED,                 // 8  target unit 0
            PROGRESS,                   // 9
            NAMED_SET_OF_FILES,         // 10
            ACTION,                     // 11
            COMPLETED,                  // 12
            CONFIGURED,                 // 13 test unit
            NAMED_SET_OF_FILES,         // 14
            COMPLETED,                  // 15
            TEST_RESULT,                // 16
            TEST_SUMMARY,               // 17
            FINISHED,                   // 18
            BUILD_METRICS,              // 19
            BUILD_TOOL_LOGS);           // 20

    @Test
    void catalogMatchesTheHandWrittenExpectationForTheCanonicalStream() {
        BepFixtureCatalog catalog = BepFixtureCatalog.of(21);

        assertThat(catalog.eventCount()).isEqualTo(21);
        assertThat(catalog.targetUnitCount()).isEqualTo(1);
        assertThat(catalog.paddingProgressCount()).isZero();
        assertThat(catalog.progressEventCount()).isEqualTo(2);
        assertThat(catalog.lastMessageIndex()).isEqualTo(20);
        assertThat(catalog.payloadCases()).isEqualTo(EXPECTED_21);
    }

    @Test
    void generatorMatchesTheHandWrittenExpectationForTheCanonicalStream() {
        List<BuildEvent.PayloadCase> actual =
                SyntheticBepStream.of(21).events().map(BuildEvent::getPayloadCase).toList();

        assertThat(actual).isEqualTo(EXPECTED_21);
    }

    @ParameterizedTest
    @ValueSource(ints = {16, 17, 19, 21, 26, 100, 203})
    void catalogAndGeneratorAgreeAtEveryScale(int eventCount) {
        SyntheticBepStream stream = SyntheticBepStream.of(eventCount);
        BepFixtureCatalog catalog = BepFixtureCatalog.of(stream);

        assertThat(catalog.eventCount()).isEqualTo(stream.eventCount());
        assertThat(catalog.targetUnitCount()).isEqualTo(stream.targetUnitCount());
        assertThat(catalog.paddingProgressCount()).isEqualTo(stream.paddingProgressCount());
        assertThat(catalog.progressEventCount()).isEqualTo(stream.progressEventCount());
        assertThat(catalog.lastMessageIndex()).isEqualTo(stream.lastMessageIndex());

        assertThat(stream.events().map(BuildEvent::getPayloadCase).toList())
                .isEqualTo(catalog.payloadCases());
        assertThat(stream.events().map(e -> e.getId().getIdCase()).toList())
                .isEqualTo(catalog.idCases());
    }

    @ParameterizedTest
    @ValueSource(ints = {16, 19, 21, 100, 203})
    void catalogEdgesMatchTheChildrenTheGeneratorActuallyAnnounces(int eventCount) {
        SyntheticBepStream stream = SyntheticBepStream.of(eventCount);
        BepFixtureCatalog catalog = BepFixtureCatalog.of(stream);
        List<BuildEvent> events = stream.events().toList();

        List<BepFixtureCatalog.AnnouncedEdge> edges = catalog.announcedEdges();

        long declaredChildren = events.stream().mapToLong(BuildEvent::getChildrenCount).sum();
        assertThat(edges).hasSize((int) declaredChildren);

        for (BepFixtureCatalog.AnnouncedEdge edge : edges) {
            BuildEvent parent = events.get(edge.parentIndex());
            assertThat(parent.getChildrenCount())
                    .as("parent %d must have a child at ordinal %d", edge.parentIndex(), edge.ordinal())
                    .isGreaterThan(edge.ordinal());
            BuildEventId announced = parent.getChildren(edge.ordinal());
            assertThat(announced.getIdCase()).isEqualTo(edge.childIdCase());
            assertThat(edge.childArrives()).isTrue();
            assertThat(events.get(edge.childIndex()).getId()).isEqualTo(announced);
        }
    }

    @Test
    void catalogReportsTheDeliberatelyMissingChild() {
        SyntheticBepStream.Options options =
                SyntheticBepStream.Options.of(60).withAnnounceMissingTargetSummary(true);
        BepFixtureCatalog catalog = BepFixtureCatalog.of(options);
        SyntheticBepStream stream = new SyntheticBepStream(options);

        List<BepFixtureCatalog.AnnouncedEdge> missing = catalog.announcedButNeverArrived();
        assertThat(missing).hasSize(1);
        assertThat(missing.get(0).childIdCase()).isEqualTo(BuildEventId.IdCase.TARGET_SUMMARY);
        assertThat(missing.get(0).childIndex()).isEqualTo(BepFixtureCatalog.CHILD_NEVER_ARRIVES);

        long declaredChildren = stream.events().mapToLong(BuildEvent::getChildrenCount).sum();
        assertThat(catalog.announcedChildCount()).isEqualTo((int) declaredChildren);

        assertThat(BepFixtureCatalog.of(60).announcedButNeverArrived()).isEmpty();
    }

    @Test
    void progressEventIndexPointsAtTheRightEvents() {
        SyntheticBepStream stream = SyntheticBepStream.of(203);
        BepFixtureCatalog catalog = BepFixtureCatalog.of(stream);

        for (int opaque = 0; opaque < catalog.progressEventCount(); opaque++) {
            BuildEvent event = stream.eventAt(catalog.progressEventIndex(opaque));
            assertThat(event.getPayloadCase()).isEqualTo(PROGRESS);
            assertThat(event.getId().getProgress().getOpaqueCount()).isEqualTo(opaque);
        }
    }
}
