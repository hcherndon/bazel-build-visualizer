package com.holtherndon.bazelviz.bepcodec;

import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildEventId;
import com.google.protobuf.ByteString;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.WireFormat;
import java.io.IOException;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The canonical identity of a {@code BuildEventId} (phase-1 contract section 5).
 *
 * <p>{@code BuildEventId} is a oneof over roughly thirty nested id messages, and
 * the set grows with every Bazel release. Rather than teach the importer each
 * variant, an identity is derived from the <em>serialized id bytes</em>: a
 * 128-bit hash whose low half becomes {@code bep_events.event_id_hash} and
 * {@code bep_event_ids.event_id_hash}, with the full bytes retained in
 * {@code bep_event_ids.id_bytes}.
 *
 * <p>That choice has two consequences the design depends on:
 *
 * <ul>
 *   <li>Parent/child linking works without decoding any variant. A parent
 *       announces children as {@code BuildEventId}s; keying both sides the same
 *       way matches them by bytes.
 *   <li>It keeps working when a future Bazel adds a variant this build has never
 *       heard of. protobuf-java parses such an id into an empty oneof plus
 *       retained unknown fields, which still serialize back to the original
 *       bytes, so the key is stable — and {@link #idKind()} still reports the
 *       wire field number, so the unrecognised variant is visibly a distinct
 *       kind rather than being folded into "no id".
 * </ul>
 *
 * <p>Equality is exact: two keys are equal only when their id bytes are equal,
 * so a hash collision between genuinely different ids can be resolved rather
 * than merging two events. Callers that index by hash alone must be prepared to
 * compare {@link #idBytes()} on a hit.
 *
 * @param high64 high half of the 128-bit hash; not stored, available to widen
 *     the key if collisions ever warrant it
 * @param low64 low half — the value written to {@code event_id_hash}
 * @param idKind wire field number of the id variant; see {@link #idKind()}
 * @param idBytes the serialized {@code BuildEventId}, retained verbatim
 */
public record EventIdKey(long high64, long low64, int idKind, ByteString idBytes) {

    /**
     * {@link #idKind()} for an id message that carries no fields at all —
     * neither a known variant nor an unrecognised one.
     */
    public static final int ID_KIND_NONE = 0;

    /** Wire field numbers of every id variant this build knows. */
    private static final Set<Integer> KNOWN_ID_FIELD_NUMBERS =
            Stream.of(BuildEventId.IdCase.values())
                    .map(BuildEventId.IdCase::getNumber)
                    .filter(number -> number != 0)
                    .collect(Collectors.toUnmodifiableSet());

    public EventIdKey {
        Objects.requireNonNull(idBytes, "idBytes");
    }

    /** Keys a parsed id. Serialization is deterministic for a given message. */
    public static EventIdKey of(BuildEventId id) {
        return fromSerialized(id.toByteString());
    }

    /**
     * Keys an id from its serialized form.
     *
     * <p>Preferred over {@link #of(BuildEventId)} whenever the original bytes
     * are at hand: hashing what was received, rather than what this build would
     * re-emit, is the raw-first rule (ADR-004) applied to identity.
     *
     * <p>Never throws. Bytes that are not valid protobuf still produce a key —
     * a deterministic one over exactly those bytes — because the caller is
     * importing data, not validating it, and a diagnostic is recorded elsewhere.
     */
    public static EventIdKey fromSerialized(ByteString idBytes) {
        Objects.requireNonNull(idBytes, "idBytes");
        Murmur3Hash128.Hash128 hash = Murmur3Hash128.hash(idBytes);
        return new EventIdKey(hash.high(), hash.low(), idKindOf(idBytes), idBytes);
    }

    /** Keys an id from serialized bytes held in an array. */
    public static EventIdKey fromSerialized(byte[] idBytes, int offset, int length) {
        return fromSerialized(ByteString.copyFrom(idBytes, offset, length));
    }

    /**
     * The value for {@code bep_events.event_id_hash} and the primary key of
     * {@code bep_event_ids}.
     */
    public long eventIdHash() {
        return low64;
    }

    /**
     * The value for {@code bep_event_ids.id_kind}: the wire field number of the
     * id variant, which is stable across protobuf and Bazel versions in a way
     * that an enum ordinal is not.
     *
     * <p>{@link #ID_KIND_NONE} means the id message was empty. A number that is
     * not among this build's known variants means a future Bazel used a variant
     * this build does not model — which is information, so it is preserved
     * rather than flattened to zero.
     */
    @Override
    public int idKind() {
        return idKind;
    }

    /** True when {@link #idKind()} names a variant this build models. */
    public boolean isKnownKind() {
        return KNOWN_ID_FIELD_NUMBERS.contains(idKind);
    }

    /** The known variant, or {@code null} when {@link #isKnownKind()} is false. */
    public BuildEventId.IdCase knownKind() {
        return isKnownKind() ? BuildEventId.IdCase.forNumber(idKind) : null;
    }

    /**
     * Determines the id variant straight from the wire, so an unrecognised
     * variant reports its own field number instead of collapsing to "not set".
     *
     * <p>A known field number wins over an unknown one, and the last known field
     * wins over an earlier one, matching how protobuf resolves a oneof when more
     * than one member appears on the wire. Malformed trailing bytes stop the
     * scan and keep whatever was already established, because this method's job
     * is to classify, not to validate.
     */
    private static int idKindOf(ByteString idBytes) {
        int knownKind = ID_KIND_NONE;
        int firstFieldNumber = ID_KIND_NONE;
        CodedInputStream input = idBytes.newCodedInput();
        try {
            while (true) {
                int tag = input.readTag();
                if (tag == 0) {
                    break;
                }
                int fieldNumber = WireFormat.getTagFieldNumber(tag);
                if (firstFieldNumber == ID_KIND_NONE) {
                    firstFieldNumber = fieldNumber;
                }
                if (KNOWN_ID_FIELD_NUMBERS.contains(fieldNumber)) {
                    knownKind = fieldNumber;
                }
                if (!input.skipField(tag)) {
                    break;
                }
            }
        } catch (IOException | RuntimeException e) {
            // Truncated or malformed: classify from what was readable.
        }
        return knownKind != ID_KIND_NONE ? knownKind : firstFieldNumber;
    }

    @Override
    public String toString() {
        return "EventIdKey[hash=" + Long.toHexString(low64)
                + ", kind=" + idKind
                + ", bytes=" + idBytes.size() + "]";
    }
}
