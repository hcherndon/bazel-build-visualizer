package com.holtherndon.bazelviz.testsupport.bep;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Each damage helper must do exactly what it claims and nothing else — a fixture that quietly
 * damages more than advertised turns a parser bug into a fixture bug. So every test here checks the
 * resulting byte length and the exact set of changed bytes, and confirms the source file is
 * untouched.
 */
final class BepDamageTest {

  @TempDir Path tempDir;

  private Path source;
  private byte[] sourceBytes;
  private SyntheticBepStream stream;

  @BeforeEach
  void writeGoodFile() throws IOException {
    stream = SyntheticBepStream.of(203);
    source = tempDir.resolve("build.bep");
    BepBinaryWriter.write(source, stream);
    sourceBytes = Files.readAllBytes(source);
  }

  @Test
  void truncateAtKeepsExactlyTheRequestedPrefix() throws IOException {
    long keep = sourceBytes.length / 3;
    BepDamage.Damage damage = BepDamage.truncateAt(source, tempDir.resolve("cut.bep"), keep);

    assertThat(damage.kind()).isEqualTo(BepDamage.Kind.TRUNCATED_AT_OFFSET);
    assertThat(damage.originalBytes()).isEqualTo(sourceBytes.length);
    assertThat(damage.resultBytes()).isEqualTo(keep);
    assertThat(damage.damageOffset()).isEqualTo(keep);
    assertThat(damage.bytesRemoved()).isEqualTo(sourceBytes.length - keep);

    byte[] result = Files.readAllBytes(damage.path());
    assertThat(result).hasSize((int) keep);
    assertThat(result).isEqualTo(Arrays.copyOf(sourceBytes, (int) keep));
    assertSourceUntouched();
  }

  @Test
  void truncateMidVarintEndsOneByteIntoAMultiByteLengthPrefix() throws IOException {
    BepDamage.Damage damage = BepDamage.truncateMidVarint(source, tempDir.resolve("varint.bep"));

    assertThat(damage.kind()).isEqualTo(BepDamage.Kind.TRUNCATED_MID_VARINT);
    assertThat(damage.intactFrames()).isPresent();

    List<LengthDelimitedFrames.Frame> frames = LengthDelimitedFrames.index(source).frames();
    LengthDelimitedFrames.Frame damaged = frames.get(damage.intactFrames().getAsInt());
    assertThat(damaged.varintLength()).isGreaterThan(1);
    assertThat(damage.resultBytes()).isEqualTo(damaged.varintOffset() + 1);
    assertThat(Files.size(damage.path())).isEqualTo(damage.resultBytes());

    // The last byte present has its continuation bit set: the reader can see
    // the varint is unfinished rather than mistaking it for a short frame.
    byte[] result = Files.readAllBytes(damage.path());
    assertThat(result[result.length - 1] & 0x80).isEqualTo(0x80);

    LengthDelimitedFrames.IndexResult scan = LengthDelimitedFrames.index(damage.path());
    assertThat(scan.tail()).isEqualTo(LengthDelimitedFrames.Tail.PARTIAL_VARINT);
    assertThat(scan.frames()).hasSize(damage.intactFrames().getAsInt());
    assertThat(scan.truncatedTailBytes()).isEqualTo(1);
    assertSourceUntouched();
  }

  @Test
  void truncateMidPayloadLeavesACompleteLengthAndAShortPayload() throws IOException {
    BepDamage.Damage damage = BepDamage.truncateMidPayload(source, tempDir.resolve("payload.bep"));

    assertThat(damage.kind()).isEqualTo(BepDamage.Kind.TRUNCATED_MID_PAYLOAD);

    List<LengthDelimitedFrames.Frame> frames = LengthDelimitedFrames.index(source).frames();
    LengthDelimitedFrames.Frame last = frames.get(frames.size() - 1);
    assertThat(damage.intactFrames()).hasValue(last.index());
    assertThat(damage.resultBytes()).isEqualTo(last.payloadOffset() + last.payloadLength() / 2);
    assertThat(Files.size(damage.path())).isEqualTo(damage.resultBytes());

    LengthDelimitedFrames.IndexResult scan = LengthDelimitedFrames.index(damage.path());
    assertThat(scan.tail()).isEqualTo(LengthDelimitedFrames.Tail.PARTIAL_PAYLOAD);
    assertThat(scan.frames()).hasSize(frames.size() - 1);
    assertThat(scan.cleanEndOffset()).isEqualTo(last.varintOffset());
    assertSourceUntouched();
  }

  @Test
  void flipPayloadByteChangesExactlyOneByteAndNoLength() throws IOException {
    int frameIndex = 12;
    int offsetWithin = 3;
    BepDamage.Damage damage =
        BepDamage.flipPayloadByte(source, tempDir.resolve("flip.bep"), frameIndex, offsetWithin);

    assertThat(damage.kind()).isEqualTo(BepDamage.Kind.PAYLOAD_BYTE_FLIPPED);
    assertThat(damage.resultBytes()).isEqualTo(damage.originalBytes());
    assertThat(damage.bytesRemoved()).isZero();
    assertThat(damage.originalByte()).isPresent();
    assertThat(damage.replacementByte()).isPresent();
    assertThat(damage.originalByte().getAsInt() ^ 0xFF)
        .isEqualTo(damage.replacementByte().getAsInt());

    byte[] result = Files.readAllBytes(damage.path());
    assertThat(result).hasSize(sourceBytes.length);

    List<Integer> differing = new ArrayList<>();
    for (int i = 0; i < result.length; i++) {
      if (result[i] != sourceBytes[i]) {
        differing.add(i);
      }
    }
    assertThat(differing).containsExactly((int) damage.damageOffset());
    assertThat(sourceBytes[(int) damage.damageOffset()] & 0xFF)
        .isEqualTo(damage.originalByte().getAsInt());
    assertThat(result[(int) damage.damageOffset()] & 0xFF)
        .isEqualTo(damage.replacementByte().getAsInt());

    // Corruption, not truncation: framing is intact, every frame is present.
    LengthDelimitedFrames.IndexResult scan = LengthDelimitedFrames.index(damage.path());
    assertThat(scan.tail()).isEqualTo(LengthDelimitedFrames.Tail.CLEAN);
    assertThat(scan.frames()).hasSize(stream.eventCount());

    LengthDelimitedFrames.Frame frame = scan.frames().get(frameIndex);
    assertThat(damage.damageOffset()).isEqualTo(frame.payloadOffset() + offsetWithin);
    assertSourceUntouched();
  }

  @Test
  void flipPayloadByteRejectsOffsetsOutsideTheChosenFrame() throws IOException {
    List<LengthDelimitedFrames.Frame> frames = LengthDelimitedFrames.index(source).frames();
    int payloadLength = frames.get(0).payloadLength();

    assertThatThrownBy(
            () -> BepDamage.flipPayloadByte(source, tempDir.resolve("x.bep"), 0, payloadLength))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> BepDamage.flipPayloadByte(source, tempDir.resolve("x.bep"), frames.size(), 0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void appendTrailingGarbageLeavesThePrefixIntact() throws IOException {
    BepDamage.Damage damage =
        BepDamage.appendTrailingGarbage(source, tempDir.resolve("garbage.bep"), 64, 0xC0FFEEL);

    assertThat(damage.kind()).isEqualTo(BepDamage.Kind.TRAILING_GARBAGE);
    assertThat(damage.resultBytes()).isEqualTo(damage.originalBytes() + 64);
    assertThat(damage.damageOffset()).isEqualTo(damage.originalBytes());
    assertThat(damage.bytesRemoved()).isEqualTo(-64);

    byte[] result = Files.readAllBytes(damage.path());
    assertThat(result).hasSize(sourceBytes.length + 64);
    assertThat(Arrays.copyOf(result, sourceBytes.length)).isEqualTo(sourceBytes);
    for (int i = sourceBytes.length; i < result.length; i++) {
      assertThat(result[i]).as("garbage byte %d must not be 0x00", i).isNotEqualTo((byte) 0);
    }
    assertSourceUntouched();
  }

  @Test
  void appendTrailingGarbageIsDeterministicForAFixedSeed() throws IOException {
    Path a = tempDir.resolve("g1.bep");
    Path b = tempDir.resolve("g2.bep");
    BepDamage.appendTrailingGarbage(source, a, 64, 7L);
    BepDamage.appendTrailingGarbage(source, b, 64, 7L);
    Path c = tempDir.resolve("g3.bep");
    BepDamage.appendTrailingGarbage(source, c, 64, 8L);

    assertThat(Files.readAllBytes(a)).isEqualTo(Files.readAllBytes(b));
    assertThat(Files.readAllBytes(a)).isNotEqualTo(Files.readAllBytes(c));
  }

  @Test
  void truncateAtRejectsOffsetsOutsideTheFile() {
    assertThatThrownBy(
            () -> BepDamage.truncateAt(source, tempDir.resolve("x.bep"), sourceBytes.length + 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> BepDamage.truncateAt(source, tempDir.resolve("x.bep"), -1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void truncationAndCorruptionAreDistinguishableFromTheBytesAlone() throws IOException {
    // Plan 21.3: TRUNCATED (clean short tail) must not be reported as CORRUPT.
    BepDamage.Damage truncated = BepDamage.truncateMidPayload(source, tempDir.resolve("t.bep"));
    BepDamage.Damage corrupt = BepDamage.flipPayloadByte(source, tempDir.resolve("c.bep"), 20, 5);

    assertThat(LengthDelimitedFrames.index(truncated.path()).tail())
        .isEqualTo(LengthDelimitedFrames.Tail.PARTIAL_PAYLOAD);
    assertThat(LengthDelimitedFrames.index(corrupt.path()).tail())
        .isEqualTo(LengthDelimitedFrames.Tail.CLEAN);
    assertThat(truncated.resultBytes()).isLessThan(truncated.originalBytes());
    assertThat(corrupt.resultBytes()).isEqualTo(corrupt.originalBytes());
  }

  private void assertSourceUntouched() throws IOException {
    assertThat(Files.readAllBytes(source)).isEqualTo(sourceBytes);
  }
}
