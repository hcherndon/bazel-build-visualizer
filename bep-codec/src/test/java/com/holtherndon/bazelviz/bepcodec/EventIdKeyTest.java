package com.holtherndon.bazelviz.bepcodec;

import static com.holtherndon.bazelviz.bepcodec.WireBytes.stringField;
import static com.holtherndon.bazelviz.bepcodec.WireBytes.varintField;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildEventId;
import com.google.protobuf.ByteString;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class EventIdKeyTest {

    /** A field number no BEP schema declares — a stand-in for a future Bazel. */
    private static final int FUTURE_ID_FIELD = 909;

    private static BuildEventId targetCompleted(String label, String configuration) {
        return BuildEventId.newBuilder()
                .setTargetCompleted(BuildEventId.TargetCompletedId.newBuilder()
                        .setLabel(label)
                        .setConfiguration(
                                BuildEventId.ConfigurationId.newBuilder().setId(configuration)))
                .build();
    }

    @Test
    void protobufEqualIdsProduceEqualKeys() {
        BuildEventId one = targetCompleted("//foo:bar", "abc123");
        BuildEventId other = targetCompleted("//foo:bar", "abc123");

        assertThat(one).isEqualTo(other);
        assertThat(EventIdKey.of(one)).isEqualTo(EventIdKey.of(other));
        assertThat(EventIdKey.of(one).eventIdHash()).isEqualTo(EventIdKey.of(other).eventIdHash());
    }

    @Test
    void idsDifferingOnlyInConfigurationAreDistinct() {
        EventIdKey one = EventIdKey.of(targetCompleted("//foo:bar", "abc123"));
        EventIdKey other = EventIdKey.of(targetCompleted("//foo:bar", "def456"));

        assertThat(one).isNotEqualTo(other);
        assertThat(one.eventIdHash()).isNotEqualTo(other.eventIdHash());
    }

    @Test
    void theKeyIsDerivedFromTheSuppliedBytesAndKeepsThemVerbatim() {
        BuildEventId id = targetCompleted("//foo:bar", "abc123");
        ByteString wire = id.toByteString();

        EventIdKey key = EventIdKey.fromSerialized(wire);

        assertThat(key.idBytes()).isEqualTo(wire);
        assertThat(key).isEqualTo(EventIdKey.of(id));
        assertThat(key.low64()).isEqualTo(Murmur3Hash128.hash(wire).low());
        assertThat(key.high64()).isEqualTo(Murmur3Hash128.hash(wire).high());
    }

    @Test
    void theArrayFactoryKeysASliceWithoutTheSurroundingBytes() {
        ByteString wire = targetCompleted("//foo:bar", "abc123").toByteString();
        byte[] embedded = new byte[wire.size() + 7];
        System.arraycopy(wire.toByteArray(), 0, embedded, 3, wire.size());

        assertThat(EventIdKey.fromSerialized(embedded, 3, wire.size()))
                .isEqualTo(EventIdKey.fromSerialized(wire));
    }

    @Test
    void idKindIsTheWireFieldNumberOfTheVariant() {
        assertThat(EventIdKey.of(targetCompleted("//foo:bar", "cfg")).idKind())
                .isEqualTo(BuildEventId.IdCase.TARGET_COMPLETED.getNumber())
                .isEqualTo(5);

        BuildEventId progress = BuildEventId.newBuilder()
                .setProgress(BuildEventId.ProgressId.newBuilder().setOpaqueCount(12))
                .build();
        assertThat(EventIdKey.of(progress).idKind())
                .isEqualTo(BuildEventId.IdCase.PROGRESS.getNumber());
    }

    @Test
    void everyKnownVariantReportsItsOwnKindAndItsOwnKey() {
        Map<Integer, EventIdKey> keysByKind = new HashMap<>();
        for (BuildEventId.IdCase idCase : BuildEventId.IdCase.values()) {
            if (idCase == BuildEventId.IdCase.ID_NOT_SET) {
                continue;
            }
            BuildEventId id = idOfCase(idCase);
            EventIdKey key = EventIdKey.of(id);

            assertThat(key.idKind()).as("kind of %s", idCase).isEqualTo(idCase.getNumber());
            assertThat(key.isKnownKind()).as("%s is known", idCase).isTrue();
            assertThat(key.knownKind()).as("case of %s", idCase).isEqualTo(idCase);
            assertThat(keysByKind.put(key.idKind(), key))
                    .as("no two variants share a kind")
                    .isNull();
        }
        assertThat(keysByKind).hasSize(BuildEventId.IdCase.values().length - 1);
        assertThat(keysByKind.values().stream().map(EventIdKey::eventIdHash).distinct().count())
                .isEqualTo(keysByKind.size());
    }

    /**
     * Builds a minimally-populated id of the given variant through reflection on
     * the descriptor, so this test covers variants added to the schema later
     * without anyone remembering to extend a hand-written list.
     */
    private static BuildEventId idOfCase(BuildEventId.IdCase idCase) {
        var field = BuildEventId.getDescriptor().findFieldByNumber(idCase.getNumber());
        return BuildEventId.newBuilder()
                .setField(field, BuildEventId.newBuilder()
                        .newBuilderForField(field)
                        .build())
                .build();
    }

    @Test
    void anEmptyIdIsKindNoneRatherThanAnInventedVariant() {
        EventIdKey key = EventIdKey.of(BuildEventId.getDefaultInstance());

        assertThat(key.idKind()).isEqualTo(EventIdKey.ID_KIND_NONE);
        assertThat(key.isKnownKind()).isFalse();
        assertThat(key.knownKind()).isNull();
        assertThat(key.idBytes()).isEqualTo(ByteString.EMPTY);
    }

    /**
     * The case the contract exists for: a Bazel newer than this build uses an id
     * variant nothing here declares. protobuf-java keeps it as an unknown field,
     * and the key has to stay stable and stay distinct rather than throwing or
     * collapsing every future id into "not set".
     */
    @Test
    void anIdVariantThisBuildDoesNotKnowStillKeysStably() {
        ByteString future = stringField(FUTURE_ID_FIELD, "//foo:bar");

        assertThatCode(() -> EventIdKey.fromSerialized(future)).doesNotThrowAnyException();
        EventIdKey key = EventIdKey.fromSerialized(future);

        assertThat(key.idKind()).isEqualTo(FUTURE_ID_FIELD);
        assertThat(key.isKnownKind()).isFalse();
        assertThat(key.knownKind()).isNull();
        assertThat(key.idBytes()).isEqualTo(future);
        assertThat(key).isEqualTo(EventIdKey.fromSerialized(future));

        // Parsing it first must not change the identity: protobuf retains the
        // unknown field, so the round trip is byte-identical.
        BuildEventId parsed = parse(future);
        assertThat(parsed.getIdCase()).isEqualTo(BuildEventId.IdCase.ID_NOT_SET);
        assertThat(parsed.getUnknownFields().asMap()).containsOnlyKeys(FUTURE_ID_FIELD);
        assertThat(EventIdKey.of(parsed)).isEqualTo(key);
    }

    @Test
    void twoDifferentFutureVariantsDoNotShareAKey() {
        EventIdKey one = EventIdKey.fromSerialized(stringField(FUTURE_ID_FIELD, "//foo:bar"));
        EventIdKey other = EventIdKey.fromSerialized(stringField(FUTURE_ID_FIELD + 1, "//foo:bar"));

        assertThat(one.idKind()).isEqualTo(FUTURE_ID_FIELD);
        assertThat(other.idKind()).isEqualTo(FUTURE_ID_FIELD + 1);
        assertThat(one).isNotEqualTo(other);
        assertThat(one.eventIdHash()).isNotEqualTo(other.eventIdHash());
    }

    @Test
    void aKnownVariantWinsOverAnUnknownFieldOnTheSameId() {
        // A future Bazel adding an annotation alongside a variant we do know:
        // the kind must stay the variant, not the annotation's field number.
        ByteString wire = varintField(FUTURE_ID_FIELD, 1)
                .concat(targetCompleted("//foo:bar", "cfg").toByteString());

        EventIdKey key = EventIdKey.fromSerialized(wire);

        assertThat(key.idKind()).isEqualTo(BuildEventId.IdCase.TARGET_COMPLETED.getNumber());
        assertThat(key.isKnownKind()).isTrue();
    }

    @Test
    void bytesThatAreNotValidProtobufStillKeyDeterministically() {
        ByteString garbage = ByteString.copyFrom(new byte[] {0x0A, 0x7F, 0x01});

        EventIdKey key = EventIdKey.fromSerialized(garbage);

        assertThat(key).isEqualTo(EventIdKey.fromSerialized(garbage));
        assertThat(key.idBytes()).isEqualTo(garbage);
    }

    /**
     * Only the low 64 bits reach {@code event_id_hash}, so the sample below
     * checks that half specifically, over ids shaped like the ones a real build
     * emits — long labels sharing prefixes, and configurations that differ in a
     * single character.
     */
    @Test
    void aLargeSampleOfDistinctIdsDoesNotCollideOnTheStoredHash() {
        Map<Long, ByteString> byHash = new HashMap<>();
        int expected = 0;
        for (int pkg = 0; pkg < 400; pkg++) {
            for (int target = 0; target < 400; target++) {
                String label = "//src/main/java/com/example/package" + pkg + ":target" + target;
                for (String configuration : new String[] {"k8-fastbuild-ST-1234", "k8-fastbuild-ST-1235"}) {
                    EventIdKey key = EventIdKey.of(targetCompleted(label, configuration));
                    expected++;
                    ByteString previous = byHash.put(key.eventIdHash(), key.idBytes());
                    assertThat(previous)
                            .as("collision on %s / %s", label, configuration)
                            .isNull();
                }
            }
        }
        assertThat(byHash).hasSize(expected);
    }

    private static BuildEventId parse(ByteString bytes) {
        try {
            return BuildEventId.parseFrom(bytes);
        } catch (com.google.protobuf.InvalidProtocolBufferException e) {
            throw new AssertionError(e);
        }
    }
}
