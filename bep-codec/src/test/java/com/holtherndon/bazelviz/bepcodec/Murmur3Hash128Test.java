package com.holtherndon.bazelviz.bepcodec;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.ByteString;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Pins the hash behind {@code event_id_hash}.
 *
 * <p>The golden vectors below were verified against Guava's {@code Hashing.murmur3_128()} — the
 * canonical MurmurHash3 x64 128 with seed 0 — over these strings and over 200 random inputs of
 * every length from 0 to 199, both halves matching. Guava is not a declared dependency of this
 * module, so the agreed values are recorded here instead of re-deriving them at test time.
 *
 * <p>A failure here is not a test to update. These hashes are written into session databases, so
 * changing them orphans every already-indexed session; fixing a failure means a format-version
 * decision.
 */
final class Murmur3Hash128Test {

  private record Vector(String input, long high, long low) {}

  @Test
  void matchesGoldenVectors() {
    // Lengths chosen to exercise the empty input, every tail branch, an
    // exact 16-byte block, and a multi-block body.
    List<Vector> vectors =
        List.of(
            new Vector("", 0x0000000000000000L, 0x0000000000000000L),
            new Vector("a", 0xe6b53a48510e895aL, 0x85555565f6597889L),
            new Vector("abc", 0x3ba2744126ca2d52L, 0xb4963f3f3fad7867L),
            new Vector("hello world", 0xab97467d60eb63b1L, 0x533f6046eb7f610eL),
            new Vector("0123456789abcde", 0x4fccf50c7c544cf0L, 0xa62dd5f6c0bf2351L),
            new Vector("0123456789abcdef", 0x87c35b5c63a708daL, 0x4be06d94cf4ad1a7L),
            new Vector("0123456789abcdef0", 0x73fb68b3313128caL, 0xeb24ae8785a5c075L),
            new Vector(
                "the quick brown fox jumps over the lazy dog, twice, for good measure",
                0x595945c63e9ba790L,
                0x78df89669684a3dcL));

    for (Vector vector : vectors) {
      Murmur3Hash128.Hash128 hash =
          Murmur3Hash128.hash(vector.input().getBytes(StandardCharsets.UTF_8));

      assertThat(hash.high()).as("high half of \"%s\"", vector.input()).isEqualTo(vector.high());
      assertThat(hash.low()).as("low half of \"%s\"", vector.input()).isEqualTo(vector.low());
    }
  }

  @Test
  void arrayByteStringAndBufferOverloadsAgree() {
    byte[] input = "buildEventId bytes".getBytes(StandardCharsets.UTF_8);

    Murmur3Hash128.Hash128 fromArray = Murmur3Hash128.hash(input);

    assertThat(Murmur3Hash128.hash(ByteString.copyFrom(input))).isEqualTo(fromArray);
    assertThat(Murmur3Hash128.hash(ByteBuffer.wrap(input))).isEqualTo(fromArray);
  }

  @Test
  void hashesARangeWithinALargerArray() {
    byte[] payload = "abc".getBytes(StandardCharsets.UTF_8);
    byte[] embedded = new byte[16];
    System.arraycopy(payload, 0, embedded, 5, payload.length);

    assertThat(Murmur3Hash128.hash(embedded, 5, payload.length))
        .isEqualTo(Murmur3Hash128.hash(payload));
  }

  @Test
  void leavesTheCallersBufferUntouched() {
    ByteBuffer buffer =
        ByteBuffer.wrap("abcdefghijklmnopqrstuv".getBytes(StandardCharsets.UTF_8))
            .order(ByteOrder.BIG_ENDIAN);
    buffer.position(4);

    Murmur3Hash128.hash(buffer);

    assertThat(buffer.position()).isEqualTo(4);
    assertThat(buffer.order()).isEqualTo(ByteOrder.BIG_ENDIAN);
  }

  @Test
  void hashesOnlyTheRemainingBytesOfABuffer() {
    byte[] whole = "XXXXabc".getBytes(StandardCharsets.UTF_8);
    ByteBuffer buffer = ByteBuffer.wrap(whole);
    buffer.position(4);

    assertThat(Murmur3Hash128.hash(buffer))
        .isEqualTo(Murmur3Hash128.hash("abc".getBytes(StandardCharsets.UTF_8)));
  }

  /**
   * The low half alone carries the {@code event_id_hash} column, so dispersion of that half — not
   * of the full 128 bits — is what the schema relies on.
   */
  @Test
  void lowHalfDoesNotCollideAcrossAMillionShortInputs() {
    int count = 1_000_000;
    Set<Long> seen = new HashSet<>(count * 2);
    ByteBuffer scratch = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
    int collisions = 0;
    for (int i = 0; i < count; i++) {
      scratch.putLong(0, i);
      if (!seen.add(Murmur3Hash128.hash(scratch.array()).low())) {
        collisions++;
      }
    }
    assertThat(collisions).isZero();
  }

  @Test
  void singleBitFlipsChangeBothHalves() {
    byte[] base = new byte[24];
    new Random(4242L).nextBytes(base);
    Murmur3Hash128.Hash128 reference = Murmur3Hash128.hash(base);

    for (int bit = 0; bit < base.length * 8; bit++) {
      byte[] flipped = base.clone();
      flipped[bit / 8] ^= (byte) (1 << (bit % 8));
      Murmur3Hash128.Hash128 hash = Murmur3Hash128.hash(flipped);
      assertThat(hash.low()).as("low after flipping bit %d", bit).isNotEqualTo(reference.low());
      assertThat(hash.high()).as("high after flipping bit %d", bit).isNotEqualTo(reference.high());
    }
  }
}
