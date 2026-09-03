package com.holtherndon.bazelviz.testsupport.bep;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.OptionalInt;

/**
 * Deliberate damage, applied to a known-good BEP file at a named byte offset.
 *
 * <p>Phase 1's exit criteria require that truncated files import and that corruption is reported
 * rather than guessed at, and plan 21.3 requires TRUNCATED (a clean short tail) to be
 * distinguishable from CORRUPT (a fully present frame that fails validation). Those two cases
 * cannot be tested with one fixture, so each helper here produces exactly one of them and reports
 * back precisely what it did:
 *
 * <table>
 *   <caption>Damage kinds and what they should provoke</caption>
 *   <tr><th>Helper</th><th>Expected classification</th></tr>
 *   <tr><td>{@link #truncateAt}</td><td>TRUNCATED — arbitrary short tail</td></tr>
 *   <tr><td>{@link #truncateMidVarint}</td><td>TRUNCATED — a partial length prefix</td></tr>
 *   <tr><td>{@link #truncateMidPayload}</td><td>TRUNCATED — a complete length, short payload</td></tr>
 *   <tr><td>{@link #flipPayloadByte}</td><td>CORRUPT — every byte present, content wrong</td></tr>
 *   <tr><td>{@link #appendTrailingGarbage}</td><td>CORRUPT/TRUNCATED tail after the last valid frame</td></tr>
 * </table>
 *
 * <p>Every helper returns a {@link Damage} naming the original size, the result size and the offset
 * touched, so a test asserts against the claim rather than against a re-derivation of it. The
 * source file is never modified.
 */
public final class BepDamage {

  /** What was done to the file. */
  public enum Kind {
    TRUNCATED_AT_OFFSET,
    TRUNCATED_MID_VARINT,
    TRUNCATED_MID_PAYLOAD,
    PAYLOAD_BYTE_FLIPPED,
    TRAILING_GARBAGE
  }

  /**
   * A description of one applied damage.
   *
   * @param damageOffset first byte affected: the truncation point, the flipped byte's offset, or
   *     the offset where garbage begins
   * @param intactFrames frames that remain fully present and unmodified before the damage; empty
   *     {@link OptionalInt} when the helper does not know (never 0 as a stand-in for unknown — plan
   *     11.4)
   * @param originalByte the byte value replaced, for a flip; empty otherwise
   * @param replacementByte the byte value written, for a flip; empty otherwise
   */
  public record Damage(
      Path path,
      Kind kind,
      long originalBytes,
      long resultBytes,
      long damageOffset,
      OptionalInt intactFrames,
      OptionalInt originalByte,
      OptionalInt replacementByte) {

    /** Bytes removed relative to the source file; negative when bytes were appended. */
    public long bytesRemoved() {
      return originalBytes - resultBytes;
    }
  }

  private BepDamage() {}

  /** Copies {@code source} to {@code dest} keeping only the first {@code keepBytes} bytes. */
  public static Damage truncateAt(Path source, Path dest, long keepBytes) throws IOException {
    long originalBytes = Files.size(source);
    if (keepBytes < 0 || keepBytes > originalBytes) {
      throw new IllegalArgumentException(
          "keepBytes " + keepBytes + " outside [0, " + originalBytes + "]");
    }
    copy(source, dest);
    try (FileChannel channel = FileChannel.open(dest, StandardOpenOption.WRITE)) {
      channel.truncate(keepBytes);
    }
    OptionalInt intact = countFullyPresentFramesBefore(source, keepBytes);
    return new Damage(
        dest,
        Kind.TRUNCATED_AT_OFFSET,
        originalBytes,
        keepBytes,
        keepBytes,
        intact,
        OptionalInt.empty(),
        OptionalInt.empty());
  }

  /**
   * Truncates one byte into a multi-byte length varint, so the file ends with a length prefix that
   * cannot be completed. Uses the last frame whose length prefix occupies more than one byte.
   *
   * @throws IllegalArgumentException if every frame's length fits in a single varint byte (payloads
   *     under 128 bytes), in which case no such truncation point exists — generate a stream with
   *     larger events
   */
  public static Damage truncateMidVarint(Path source, Path dest) throws IOException {
    List<LengthDelimitedFrames.Frame> frames = LengthDelimitedFrames.index(source).frames();
    LengthDelimitedFrames.Frame target = null;
    for (LengthDelimitedFrames.Frame frame : frames) {
      if (frame.varintLength() > 1) {
        target = frame;
      }
    }
    if (target == null) {
      throw new IllegalArgumentException(
          "no frame in "
              + source
              + " has a multi-byte length varint; "
              + "there is no mid-varint truncation point in this file");
    }
    long keep = target.varintOffset() + 1;
    long originalBytes = Files.size(source);
    copy(source, dest);
    try (FileChannel channel = FileChannel.open(dest, StandardOpenOption.WRITE)) {
      channel.truncate(keep);
    }
    return new Damage(
        dest,
        Kind.TRUNCATED_MID_VARINT,
        originalBytes,
        keep,
        keep,
        OptionalInt.of(target.index()),
        OptionalInt.empty(),
        OptionalInt.empty());
  }

  /**
   * Truncates in the middle of the last frame's payload: the length prefix is complete and declares
   * more bytes than the file contains.
   */
  public static Damage truncateMidPayload(Path source, Path dest) throws IOException {
    List<LengthDelimitedFrames.Frame> frames = LengthDelimitedFrames.index(source).frames();
    if (frames.isEmpty()) {
      throw new IllegalArgumentException("no frames in " + source);
    }
    LengthDelimitedFrames.Frame last = frames.get(frames.size() - 1);
    if (last.payloadLength() < 2) {
      throw new IllegalArgumentException(
          "last frame of "
              + source
              + " has a "
              + last.payloadLength()
              + "-byte payload; there is no mid-payload truncation point");
    }
    long keep = last.payloadOffset() + last.payloadLength() / 2;
    long originalBytes = Files.size(source);
    copy(source, dest);
    try (FileChannel channel = FileChannel.open(dest, StandardOpenOption.WRITE)) {
      channel.truncate(keep);
    }
    return new Damage(
        dest,
        Kind.TRUNCATED_MID_PAYLOAD,
        originalBytes,
        keep,
        keep,
        OptionalInt.of(last.index()),
        OptionalInt.empty(),
        OptionalInt.empty());
  }

  /**
   * Flips every bit of one byte inside a frame's payload. The file length is unchanged and every
   * frame is fully present, so this is corruption, not truncation — a length-delimited reader will
   * still find all the frame boundaries, and only the decode (or a checksum) can notice.
   *
   * @param frameIndex 0-based frame to damage
   * @param offsetWithinPayload 0-based offset inside that frame's payload
   */
  public static Damage flipPayloadByte(
      Path source, Path dest, int frameIndex, int offsetWithinPayload) throws IOException {
    List<LengthDelimitedFrames.Frame> frames = LengthDelimitedFrames.index(source).frames();
    if (frameIndex < 0 || frameIndex >= frames.size()) {
      throw new IllegalArgumentException(
          "frameIndex " + frameIndex + " outside [0, " + frames.size() + ")");
    }
    LengthDelimitedFrames.Frame frame = frames.get(frameIndex);
    if (offsetWithinPayload < 0 || offsetWithinPayload >= frame.payloadLength()) {
      throw new IllegalArgumentException(
          "offsetWithinPayload "
              + offsetWithinPayload
              + " outside [0, "
              + frame.payloadLength()
              + ") for frame "
              + frameIndex);
    }
    long originalBytes = Files.size(source);
    copy(source, dest);
    long offset = frame.payloadOffset() + offsetWithinPayload;
    int original;
    int replacement;
    try (FileChannel channel =
        FileChannel.open(dest, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
      ByteBuffer one = ByteBuffer.allocate(1);
      channel.read(one, offset);
      original = one.get(0) & 0xFF;
      replacement = original ^ 0xFF;
      one.clear();
      one.put(0, (byte) replacement);
      channel.write(one, offset);
    }
    return new Damage(
        dest,
        Kind.PAYLOAD_BYTE_FLIPPED,
        originalBytes,
        originalBytes,
        offset,
        OptionalInt.of(frames.size()),
        OptionalInt.of(original),
        OptionalInt.of(replacement));
  }

  /**
   * Appends {@code byteCount} deterministic non-zero bytes after the last complete frame. The
   * prefix stays valid; the tail is junk, which is what a partially-flushed or concatenated file
   * looks like.
   */
  public static Damage appendTrailingGarbage(Path source, Path dest, int byteCount, long seed)
      throws IOException {
    if (byteCount <= 0) {
      throw new IllegalArgumentException("byteCount must be positive, got " + byteCount);
    }
    long originalBytes = Files.size(source);
    copy(source, dest);
    byte[] garbage = new byte[byteCount];
    long state = seed;
    for (int i = 0; i < byteCount; i++) {
      state = state * 6364136223846793005L + 1442695040888963407L;
      // Never 0x00: a zero byte is a legal empty frame, which would read as
      // valid data rather than as garbage.
      garbage[i] = (byte) (1 + Math.floorMod((int) (state >>> 33), 255));
    }
    try (OutputStream out =
        Files.newOutputStream(dest, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
      out.write(garbage);
    }
    int frameCount = LengthDelimitedFrames.index(source).frames().size();
    return new Damage(
        dest,
        Kind.TRAILING_GARBAGE,
        originalBytes,
        originalBytes + byteCount,
        originalBytes,
        OptionalInt.of(frameCount),
        OptionalInt.empty(),
        OptionalInt.empty());
  }

  private static void copy(Path source, Path dest) throws IOException {
    Path parent = dest.getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    Files.copy(source, dest, StandardCopyOption.REPLACE_EXISTING);
  }

  private static OptionalInt countFullyPresentFramesBefore(Path source, long keepBytes)
      throws IOException {
    int[] count = {0};
    LengthDelimitedFrames.scan(
        source,
        frame -> {
          if (frame.endOffset() <= keepBytes) {
            count[0]++;
            return true;
          }
          return false;
        });
    return OptionalInt.of(count[0]);
  }
}
