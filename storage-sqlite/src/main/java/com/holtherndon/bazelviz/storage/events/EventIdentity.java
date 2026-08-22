package com.holtherndon.bazelviz.storage.events;

import java.util.Arrays;
import java.util.Objects;

/**
 * A canonical BEP event identity: one row of {@code bep_event_ids}.
 *
 * <p>The key is a hash of the serialized {@code BuildEventId} rather than a
 * decoded variant, which is what lets parent/child linking keep working when a
 * future Bazel adds an id variant this build has never heard of. {@code
 * idBytes} is retained alongside the hash so a collision can be resolved
 * exactly instead of being assumed away.
 *
 * @param hash low 64 bits of the canonical 128-bit id hash
 * @param idKind the {@code BuildEventId} oneof case number
 * @param idBytes the serialized {@code BuildEventId}, verbatim
 * @param display a human-readable rendering for the UI
 */
public record EventIdentity(long hash, int idKind, byte[] idBytes, String display) {

    public EventIdentity {
        Objects.requireNonNull(idBytes, "idBytes");
        Objects.requireNonNull(display, "display");
        idBytes = idBytes.clone();
    }

    @Override
    public byte[] idBytes() {
        return idBytes.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof EventIdentity that
                && hash == that.hash
                && idKind == that.idKind
                && display.equals(that.display)
                && Arrays.equals(idBytes, that.idBytes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(hash, idKind, display, Arrays.hashCode(idBytes));
    }

    @Override
    public String toString() {
        return "EventIdentity[hash=" + hash + ", idKind=" + idKind
                + ", idBytes=" + idBytes.length + " bytes, display=" + display + ']';
    }
}
