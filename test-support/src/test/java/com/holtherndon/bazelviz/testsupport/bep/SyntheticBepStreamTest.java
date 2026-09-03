package com.holtherndon.bazelviz.testsupport.bep;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildEvent;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildEventId;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The fixture's own correctness tests. Everything downstream trusts these properties, so they are
 * asserted rather than assumed: exact event counts, a closed announcement graph, and byte-level
 * determinism.
 */
final class SyntheticBepStreamTest {

  @ParameterizedTest
  @ValueSource(ints = {16, 17, 18, 19, 20, 21, 22, 55, 200, 1_001})
  void producesExactlyTheRequestedEventCount(int requested) {
    SyntheticBepStream stream = SyntheticBepStream.of(requested);

    assertThat(stream.events()).hasSize(requested);
    assertThat(stream.eventCount()).isEqualTo(requested);
    assertThat(
            SyntheticBepStream.PROLOGUE_EVENTS
                + SyntheticBepStream.EVENTS_PER_TARGET_UNIT * stream.targetUnitCount()
                + SyntheticBepStream.TEST_UNIT_EVENTS
                + stream.paddingProgressCount()
                + SyntheticBepStream.EPILOGUE_EVENTS)
        .isEqualTo(requested);
  }

  @Test
  void rejectsStreamsTooShortToBeAPlausibleInvocation() {
    assertThatThrownBy(() -> SyntheticBepStream.of(15))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("at least 16");
  }

  @Test
  void everyEventCarriesAnIdAndAPayload() {
    for (BuildEvent event : list(SyntheticBepStream.of(200))) {
      assertThat(event.hasId()).isTrue();
      assertThat(event.getId().getIdCase()).isNotEqualTo(BuildEventId.IdCase.ID_NOT_SET);
      assertThat(event.getPayloadCase()).isNotEqualTo(BuildEvent.PayloadCase.PAYLOAD_NOT_SET);
    }
  }

  @Test
  void onlyTheFinalEventIsMarkedLastMessage() {
    SyntheticBepStream stream = SyntheticBepStream.of(97);
    List<BuildEvent> events = list(stream);

    for (int i = 0; i < events.size() - 1; i++) {
      assertThat(events.get(i).getLastMessage()).as("event %d", i).isFalse();
    }
    assertThat(events.get(events.size() - 1).getLastMessage()).isTrue();
    assertThat(events.get(events.size() - 1).getPayloadCase())
        .isEqualTo(BuildEvent.PayloadCase.BUILD_TOOL_LOGS);
    assertThat(stream.lastMessageIndex()).isEqualTo(events.size() - 1);
  }

  @Test
  void everyEventExceptTheRootIsAnnouncedExactlyOnceByAnEarlierEvent() {
    List<BuildEvent> events = list(SyntheticBepStream.of(203));

    Map<BuildEventId, Integer> arrivalIndex = new HashMap<>();
    for (int i = 0; i < events.size(); i++) {
      BuildEventId id = events.get(i).getId();
      assertThat(arrivalIndex.put(id, i)).as("duplicate event id at index %d", i).isNull();
    }

    Map<BuildEventId, Integer> announcedBy = new HashMap<>();
    for (int i = 0; i < events.size(); i++) {
      for (BuildEventId child : events.get(i).getChildrenList()) {
        Integer previous = announcedBy.put(child, i);
        assertThat(previous).as("id announced twice, by %s and %s", previous, i).isNull();
      }
    }

    for (int i = 1; i < events.size(); i++) {
      BuildEventId id = events.get(i).getId();
      Integer parent = announcedBy.get(id);
      assertThat(parent)
          .as("event %d (%s) is not announced by anyone", i, events.get(i).getPayloadCase())
          .isNotNull();
      assertThat(parent).as("event %d announced by a later event", i).isLessThan(i);
    }

    // The root is announced by nobody, and by default nothing is announced
    // that does not arrive.
    assertThat(announcedBy).doesNotContainKey(events.get(0).getId());
    Set<BuildEventId> announced = new HashSet<>(announcedBy.keySet());
    announced.removeAll(arrivalIndex.keySet());
    assertThat(announced).isEmpty();
  }

  @Test
  void theOptionalMissingChildIsAnnouncedAndNeverArrives() {
    SyntheticBepStream stream =
        new SyntheticBepStream(
            SyntheticBepStream.Options.of(60).withAnnounceMissingTargetSummary(true));
    List<BuildEvent> events = list(stream);

    Set<BuildEventId> arrived = new HashSet<>();
    events.forEach(e -> arrived.add(e.getId()));

    List<BuildEventId> missing = new ArrayList<>();
    for (BuildEvent event : events) {
      for (BuildEventId child : event.getChildrenList()) {
        if (!arrived.contains(child)) {
          missing.add(child);
        }
      }
    }

    assertThat(missing).hasSize(1);
    assertThat(missing.get(0).getIdCase()).isEqualTo(BuildEventId.IdCase.TARGET_SUMMARY);
    assertThat(missing.get(0).getTargetSummary().getLabel())
        .isEqualTo(SyntheticBepStream.TEST_LABEL);

    // ...and the default stream has no such gap.
    SyntheticBepStream plain = SyntheticBepStream.of(60);
    Set<BuildEventId> plainArrived = new HashSet<>();
    list(plain).forEach(e -> plainArrived.add(e.getId()));
    for (BuildEvent event : list(plain)) {
      assertThat(plainArrived).containsAll(event.getChildrenList());
    }
  }

  @Test
  void announcedChildIdsAreByteIdenticalToTheIdsThatArrive() {
    // Parent/child linking hashes the serialized BuildEventId, so equality of
    // the decoded message is not enough — the bytes must match too.
    List<BuildEvent> events = list(SyntheticBepStream.of(120));
    Map<BuildEventId, byte[]> arrivedBytes = new HashMap<>();
    events.forEach(e -> arrivedBytes.put(e.getId(), e.getId().toByteArray()));

    for (BuildEvent event : events) {
      for (BuildEventId child : event.getChildrenList()) {
        byte[] arrived = arrivedBytes.get(child);
        assertThat(arrived).isNotNull();
        assertThat(child.toByteArray()).isEqualTo(arrived);
      }
    }
  }

  @Test
  void theProgressChainCoversEveryProgressEventInOrder() {
    SyntheticBepStream stream = SyntheticBepStream.of(203);
    List<BuildEvent> events = list(stream);

    List<Integer> opaqueCounts = new ArrayList<>();
    for (BuildEvent event : events) {
      if (event.getPayloadCase() == BuildEvent.PayloadCase.PROGRESS) {
        opaqueCounts.add(event.getId().getProgress().getOpaqueCount());
      }
    }

    assertThat(opaqueCounts).hasSize(stream.progressEventCount());
    for (int i = 0; i < opaqueCounts.size(); i++) {
      assertThat(opaqueCounts.get(i)).isEqualTo(i);
    }
  }

  @Test
  void eventAtIsAPureFunctionOfIndex() {
    SyntheticBepStream stream = SyntheticBepStream.of(64);
    for (int i = 0; i < stream.eventCount(); i++) {
      assertThat(stream.eventAt(i)).isEqualTo(stream.eventAt(i));
    }
    assertThatThrownBy(() -> stream.eventAt(stream.eventCount()))
        .isInstanceOf(IndexOutOfBoundsException.class);
    assertThatThrownBy(() -> stream.eventAt(-1)).isInstanceOf(IndexOutOfBoundsException.class);
  }

  @Test
  void sameSeedProducesByteIdenticalStreamsAndADifferentSeedDoesNot() throws IOException {
    byte[] a = serialize(SyntheticBepStream.of(42L, 137));
    byte[] b = serialize(SyntheticBepStream.of(42L, 137));
    byte[] other = serialize(SyntheticBepStream.of(43L, 137));

    assertThat(a).isEqualTo(b);
    assertThat(a).isNotEqualTo(other);
    // Structure is seed-independent; only the values inside differ.
    assertThat(BepFixtureCatalog.of(SyntheticBepStream.of(43L, 137)).payloadCases())
        .isEqualTo(BepFixtureCatalog.of(SyntheticBepStream.of(42L, 137)).payloadCases());
  }

  @Test
  void aTwoHundredThousandEventStreamIsGeneratedWithoutMaterializingIt() {
    // Bounded memory is a Phase 1 exit criterion; the generator must feed a
    // large stream one event at a time. Nothing is retained here but a
    // counter and the current event.
    SyntheticBepStream stream = SyntheticBepStream.of(200_000);
    Iterator<BuildEvent> events = stream.iterator();
    int seen = 0;
    int lastMessages = 0;
    long serializedBytes = 0;
    while (events.hasNext()) {
      BuildEvent event = events.next();
      if (event.getLastMessage()) {
        lastMessages++;
      }
      serializedBytes += event.getSerializedSize();
      seen++;
    }

    assertThat(seen).isEqualTo(200_000);
    assertThat(lastMessages).isEqualTo(1);
    assertThat(serializedBytes).isGreaterThan(1_000_000L);
    assertThat(stream.targetUnitCount()).isEqualTo((200_000 - 16) / 5);
  }

  @Test
  void labelsAndSetIdsAreDistinctAcrossTargetUnits() {
    SyntheticBepStream stream = SyntheticBepStream.of(500);
    Set<String> labels = new HashSet<>();
    Set<String> setIds = new HashSet<>();
    for (int u = 0; u < stream.targetUnitCount(); u++) {
      assertThat(labels.add(stream.targetLabel(u))).as("label for unit %d", u).isTrue();
      assertThat(setIds.add(stream.namedSetId(u))).as("named set for unit %d", u).isTrue();
    }
    assertThat(setIds).doesNotContain(stream.testNamedSetId());
    assertThat(labels).doesNotContain(SyntheticBepStream.TEST_LABEL);
  }

  private static List<BuildEvent> list(SyntheticBepStream stream) {
    return stream.events().toList();
  }

  private static byte[] serialize(SyntheticBepStream stream) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    BepBinaryWriter.writeAll(out, stream.iterator());
    return out.toByteArray();
  }
}
