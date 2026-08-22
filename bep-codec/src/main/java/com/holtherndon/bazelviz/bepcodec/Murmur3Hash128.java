package com.holtherndon.bazelviz.bepcodec;

import com.google.protobuf.ByteString;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * MurmurHash3, 128-bit x64 variant, seed 0.
 *
 * <p>This is the hash behind {@link EventIdKey}, and therefore behind the
 * {@code event_id_hash} column. Two properties matter and both are pinned by
 * tests:
 *
 * <ul>
 *   <li><b>Stability.</b> Hashes are written into session databases. A change
 *       to this algorithm silently invalidates every already-indexed session,
 *       so {@code Murmur3Hash128Test} pins concrete values; a failure there
 *       means a schema/format decision, not a test to update.
 *   <li><b>Dispersion.</b> Only the low 64 bits reach the {@code event_id_hash}
 *       column, so the low half alone must scatter well. The event-id key keeps
 *       the full serialized id alongside the hash precisely so that the residual
 *       birthday risk — around 7e-5 at fifty million distinct ids — can be
 *       resolved exactly rather than assumed away.
 * </ul>
 *
 * <p>A non-cryptographic hash is the right tool: this is an identity index over
 * locally produced data, not a defence against an adversary choosing colliding
 * ids, and it must run once per event on builds with tens of millions of them.
 */
public final class Murmur3Hash128 {

    private Murmur3Hash128() {}

    private static final long C1 = 0x87c37b91114253d5L;
    private static final long C2 = 0x4cf5ad432745937fL;

    /** A 128-bit digest. {@code low} is the half stored in {@code event_id_hash}. */
    public record Hash128(long high, long low) {}

    /** Hashes the whole array. */
    public static Hash128 hash(byte[] input) {
        return hash(ByteBuffer.wrap(input));
    }

    /** Hashes {@code length} bytes starting at {@code offset}. */
    public static Hash128 hash(byte[] input, int offset, int length) {
        return hash(ByteBuffer.wrap(input, offset, length));
    }

    /**
     * Hashes a {@link ByteString} without copying it when its backing storage
     * allows a direct view.
     */
    public static Hash128 hash(ByteString input) {
        return hash(input.asReadOnlyByteBuffer());
    }

    /**
     * Hashes the remaining bytes of {@code input}. The caller's buffer is not
     * modified — neither its position nor its byte order.
     */
    public static Hash128 hash(ByteBuffer input) {
        ByteBuffer buffer = input.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        final int base = buffer.position();
        final int length = buffer.remaining();
        final int blocks = length >>> 4;

        long h1 = 0L;
        long h2 = 0L;

        for (int i = 0; i < blocks; i++) {
            long k1 = buffer.getLong(base + (i << 4));
            long k2 = buffer.getLong(base + (i << 4) + 8);

            k1 *= C1;
            k1 = Long.rotateLeft(k1, 31);
            k1 *= C2;
            h1 ^= k1;
            h1 = Long.rotateLeft(h1, 27);
            h1 += h2;
            h1 = h1 * 5 + 0x52dce729L;

            k2 *= C2;
            k2 = Long.rotateLeft(k2, 33);
            k2 *= C1;
            h2 ^= k2;
            h2 = Long.rotateLeft(h2, 31);
            h2 += h1;
            h2 = h2 * 5 + 0x38495ab5L;
        }

        final int tail = base + (blocks << 4);
        long k1 = 0L;
        long k2 = 0L;
        switch (length & 15) {
            case 15:
                k2 ^= toLong(buffer.get(tail + 14)) << 48;
                // fall through
            case 14:
                k2 ^= toLong(buffer.get(tail + 13)) << 40;
                // fall through
            case 13:
                k2 ^= toLong(buffer.get(tail + 12)) << 32;
                // fall through
            case 12:
                k2 ^= toLong(buffer.get(tail + 11)) << 24;
                // fall through
            case 11:
                k2 ^= toLong(buffer.get(tail + 10)) << 16;
                // fall through
            case 10:
                k2 ^= toLong(buffer.get(tail + 9)) << 8;
                // fall through
            case 9:
                k2 ^= toLong(buffer.get(tail + 8));
                k2 *= C2;
                k2 = Long.rotateLeft(k2, 33);
                k2 *= C1;
                h2 ^= k2;
                // fall through
            case 8:
                k1 ^= toLong(buffer.get(tail + 7)) << 56;
                // fall through
            case 7:
                k1 ^= toLong(buffer.get(tail + 6)) << 48;
                // fall through
            case 6:
                k1 ^= toLong(buffer.get(tail + 5)) << 40;
                // fall through
            case 5:
                k1 ^= toLong(buffer.get(tail + 4)) << 32;
                // fall through
            case 4:
                k1 ^= toLong(buffer.get(tail + 3)) << 24;
                // fall through
            case 3:
                k1 ^= toLong(buffer.get(tail + 2)) << 16;
                // fall through
            case 2:
                k1 ^= toLong(buffer.get(tail + 1)) << 8;
                // fall through
            case 1:
                k1 ^= toLong(buffer.get(tail));
                k1 *= C1;
                k1 = Long.rotateLeft(k1, 31);
                k1 *= C2;
                h1 ^= k1;
                break;
            case 0:
            default:
                break;
        }

        h1 ^= length;
        h2 ^= length;
        h1 += h2;
        h2 += h1;
        h1 = mix(h1);
        h2 = mix(h2);
        h1 += h2;
        h2 += h1;

        return new Hash128(h2, h1);
    }

    private static long toLong(byte b) {
        return b & 0xffL;
    }

    private static long mix(long k) {
        long x = k;
        x ^= x >>> 33;
        x *= 0xff51afd7ed558ccdL;
        x ^= x >>> 33;
        x *= 0xc4ceb9fe1a85ec53L;
        x ^= x >>> 33;
        return x;
    }
}
