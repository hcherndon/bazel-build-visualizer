package com.holtherndon.bazelviz.ui.capture;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/**
 * Turns a build's raw console bytes into lines a text area can show.
 *
 * <h2>What it has to cope with</h2>
 *
 * <p>Bazel's console output is not a sequence of lines. It repaints a progress block using carriage
 * returns and ANSI cursor movement, so a naive appender produces a window full of half-overwritten
 * progress and a naive line reader blocks forever on a line that never ends. This model handles the
 * two cases that actually occur: a carriage return replaces the line being built, and ANSI escape
 * sequences are removed for display.
 *
 * <p>Removed <em>for display only</em>. {@code raw/stdout.log} and {@code raw/stderr.log} hold the
 * bytes exactly as they arrived (ADR-004), so nothing here loses information — it is a rendering,
 * and the original is on disk.
 *
 * <h2>Bounded, and honest about it</h2>
 *
 * <p>A large build's console output is unbounded, and this is a UI model, so it keeps the last
 * {@link #maxLines} lines and counts what it dropped. Plan section 3 requires every imposed display
 * limit to be visible, which is what {@link #droppedLines()} is for: the view shows it rather than
 * silently presenting a truncated log as the whole thing.
 *
 * <p>Not thread-safe. Owned by the EDT; the capture threads hand bytes to a controller that hops
 * here.
 */
public final class ConsoleModel {

  /** Lines retained by default. Roughly a very long build's tail. */
  public static final int DEFAULT_MAX_LINES = 20_000;

  /** The ASCII escape that starts an ANSI control sequence. */
  private static final char ESCAPE = 0x1B;

  private final int maxLines;
  private final Deque<String> lines = new ArrayDeque<>();
  private final StringBuilder current = new StringBuilder();
  private long droppedLines;
  private EscapeState escape = EscapeState.NONE;

  /**
   * Where the reader is inside an ANSI escape sequence.
   *
   * <p>Three states rather than a boolean, because the {@code [} that follows {@code ESC} in a CSI
   * sequence is itself inside the final-byte range {@code @}..{@code ~}. A single "in an escape"
   * flag ends the sequence on that very character and leaves the parameters — {@code 32m} — in the
   * output as text, which looks like a stripper that does not work.
   */
  private enum EscapeState {
    /** Ordinary text. */
    NONE,
    /** {@code ESC} seen; the next character says which kind of sequence this is. */
    AFTER_ESCAPE,
    /** Inside {@code ESC [ ... }; ends at a byte in {@code @}..{@code ~}. */
    IN_CSI
  }

  public ConsoleModel() {
    this(DEFAULT_MAX_LINES);
  }

  public ConsoleModel(int maxLines) {
    if (maxLines < 1) {
      throw new IllegalArgumentException("maxLines must be positive, got " + maxLines);
    }
    this.maxLines = maxLines;
  }

  /**
   * Appends a chunk of console bytes.
   *
   * <p>Decoded as UTF-8 per chunk. A multi-byte character split across two chunks renders as a
   * replacement character rather than being buffered: holding bytes back to wait for a continuation
   * would stall the display of a line that is already complete, and the raw log has the true bytes.
   */
  public void append(byte[] data, int offset, int length) {
    Objects.checkFromIndexSize(offset, length, data.length);
    append(new String(data, offset, length, StandardCharsets.UTF_8));
  }

  /** Appends already-decoded text. */
  public void append(String text) {
    Objects.requireNonNull(text, "text");
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      switch (escape) {
        case AFTER_ESCAPE -> {
          // ESC [ opens a control sequence with parameters; anything
          // else is a two-character escape that ends here.
          escape = c == '[' ? EscapeState.IN_CSI : EscapeState.NONE;
          continue;
        }
        case IN_CSI -> {
          if (c >= '@' && c <= '~') {
            escape = EscapeState.NONE;
          }
          continue;
        }
        case NONE -> {
          // Fall through to the text handling below.
        }
      }
      switch (c) {
        case ESCAPE -> escape = EscapeState.AFTER_ESCAPE;
        case '\n' -> commitLine();
        case '\r' -> current.setLength(0);
        default -> {
          if (c >= ' ' || c == '\t') {
            current.append(c);
          }
        }
      }
    }
  }

  /** Commits whatever is in the partial line, as if a newline had arrived. */
  public void flushPartialLine() {
    if (current.length() > 0) {
      commitLine();
    }
  }

  private void commitLine() {
    lines.addLast(current.toString());
    current.setLength(0);
    while (lines.size() > maxLines) {
      lines.removeFirst();
      droppedLines++;
    }
  }

  /** The retained lines, oldest first. */
  public List<String> lines() {
    return List.copyOf(lines);
  }

  /**
   * Lines committed since the caller last asked, and how many were dropped from the front in the
   * meantime.
   *
   * <p>Exists so a view can append rather than re-render. Rebuilding the whole document on every
   * progress repaint is quadratic in the log's length, and Bazel repaints several times a second.
   *
   * @param alreadySeen how many lines the caller has already rendered, counted in the same total
   *     this returns
   */
  public Delta since(long alreadySeen) {
    long available = droppedLines + lines.size();
    if (alreadySeen < droppedLines) {
      // The cap discarded lines the caller had not rendered yet, so the
      // document it holds no longer corresponds to anything. It has to
      // start again, and is told so rather than silently losing the
      // middle of the log.
      return new Delta(List.copyOf(lines), droppedLines, available, true);
    }
    int skip = (int) (alreadySeen - droppedLines);
    List<String> fresh =
        skip >= lines.size()
            ? List.of()
            : List.copyOf(new ArrayList<>(lines).subList(skip, lines.size()));
    return new Delta(fresh, droppedLines, available, false);
  }

  /**
   * What changed since a caller last rendered.
   *
   * @param lines the lines to append, in order
   * @param dropped how many lines the retention cap has discarded in total
   * @param total how many lines have existed in total, dropped ones included
   * @param resetRequired true when the caller's rendered text is no longer a prefix of the model
   *     and must be rebuilt
   */
  public record Delta(List<String> lines, long dropped, long total, boolean resetRequired) {
    public Delta {
      lines = List.copyOf(lines);
    }
  }

  /** The line still being built, which has not ended in a newline yet. */
  public String partialLine() {
    return current.toString();
  }

  /** How many lines the cap discarded. Shown, never hidden. */
  public long droppedLines() {
    return droppedLines;
  }

  public int retainedLines() {
    return lines.size();
  }

  public void clear() {
    lines.clear();
    current.setLength(0);
    droppedLines = 0;
    escape = EscapeState.NONE;
  }

  /** Everything retained, as one string, for the text area. */
  public String text() {
    StringBuilder out = new StringBuilder();
    for (String line : lines) {
      out.append(line).append('\n');
    }
    out.append(current);
    return out.toString();
  }
}
