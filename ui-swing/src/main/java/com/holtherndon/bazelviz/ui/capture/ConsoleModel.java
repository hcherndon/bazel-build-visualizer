package com.holtherndon.bazelviz.ui.capture;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/**
 * Turns a build's raw console bytes into styled lines a Swing document can show.
 *
 * <h2>Terminal text without pretending this is a shell</h2>
 *
 * <p>Bazel's console output is not just plain lines. It repaints progress with carriage returns and
 * emits ANSI SGR attributes for colour and emphasis. This model collapses carriage-return repaints,
 * interprets standard, bright, 256-colour and true-colour SGR sequences, and discards terminal
 * control strings such as window titles. Unsupported control sequences do not become visible text.
 * The model deliberately remains a transcript rather than an interactive terminal: the separate
 * Terminal page owns full cursor-addressed shell emulation.
 *
 * <p>Rendering never changes the source. {@code raw/stdout.log} and {@code raw/stderr.log} keep the
 * bytes exactly as they arrived (ADR-004).
 *
 * <h2>Bounded, and honest about it</h2>
 *
 * <p>A large build's console output is unbounded, and this is a UI model, so it keeps the last
 * {@link #maxLines} lines and counts what it dropped. The view displays that count and points to
 * the complete raw files.
 *
 * <p>Not thread-safe. Owned by the EDT; the capture threads hand bytes to a controller that hops
 * here.
 */
public final class ConsoleModel {

  /** Lines retained by default. Roughly a very long build's tail. */
  public static final int DEFAULT_MAX_LINES = 20_000;

  /** Maximum parameter bytes retained while parsing one ANSI CSI sequence. */
  public static final int MAX_ANSI_SEQUENCE_CHARACTERS = 1_024;

  /** The ASCII escape that starts an ANSI control sequence. */
  private static final char ESCAPE = 0x1B;

  private static final int[] ANSI_COLOURS = {
    0x000000, 0xAA0000, 0x00AA00, 0xAA5500, 0x0000AA, 0xAA00AA, 0x00AAAA, 0xAAAAAA, 0x555555,
    0xFF5555, 0x55FF55, 0xFFFF55, 0x5555FF, 0xFF55FF, 0x55FFFF, 0xFFFFFF
  };

  private final int maxLines;
  private final Deque<StyledLine> styledLines = new ArrayDeque<>();
  private final StyledLineBuilder current = new StyledLineBuilder();
  private final AnsiState ansi = new AnsiState();
  private final StringBuilder csiParameters = new StringBuilder();
  private long droppedLines;
  private long changedFromLine = Long.MAX_VALUE;
  private boolean csiOverflow;
  private EscapeState escape = EscapeState.NONE;

  private enum EscapeState {
    NONE,
    AFTER_ESCAPE,
    IN_CSI,
    IN_CONTROL_STRING,
    CONTROL_STRING_AFTER_ESCAPE
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
   * <p>Decoded as UTF-8 per chunk, matching the previous console contract. The raw log retains the
   * exact bytes if a multibyte character is split between process-pump chunks.
   */
  public void append(byte[] data, int offset, int length) {
    Objects.checkFromIndexSize(offset, length, data.length);
    append(new String(data, offset, length, StandardCharsets.UTF_8));
  }

  /** Appends already-decoded text. */
  public void append(String text) {
    Objects.requireNonNull(text, "text");
    for (int index = 0; index < text.length(); index++) {
      char character = text.charAt(index);
      if (consumeEscape(character)) {
        continue;
      }
      switch (character) {
        case ESCAPE -> escape = EscapeState.AFTER_ESCAPE;
        case '\n' -> commitLine();
        case '\r' -> current.clear();
        case '\b' -> current.backspace();
        default -> {
          if (character >= ' ' || character == '\t') {
            current.append(character, ansi.snapshot());
          }
        }
      }
    }
  }

  /** Returns true when the character was consumed as part of an escape or control string. */
  private boolean consumeEscape(char character) {
    switch (escape) {
      case NONE -> {
        return false;
      }
      case AFTER_ESCAPE -> {
        if (character == '[') {
          csiParameters.setLength(0);
          csiOverflow = false;
          escape = EscapeState.IN_CSI;
        } else if (character == ']'
            || character == 'P'
            || character == 'X'
            || character == '^'
            || character == '_') {
          escape = EscapeState.IN_CONTROL_STRING;
        } else {
          escape = EscapeState.NONE;
        }
        return true;
      }
      case IN_CSI -> {
        if (character >= '@' && character <= '~') {
          if (!csiOverflow) {
            processCsi(character, csiParameters.toString());
          }
          csiParameters.setLength(0);
          csiOverflow = false;
          escape = EscapeState.NONE;
        } else if (csiParameters.length() < MAX_ANSI_SEQUENCE_CHARACTERS) {
          csiParameters.append(character);
        } else {
          csiOverflow = true;
        }
        return true;
      }
      case IN_CONTROL_STRING -> {
        if (character == '\u0007') {
          escape = EscapeState.NONE;
        } else if (character == ESCAPE) {
          escape = EscapeState.CONTROL_STRING_AFTER_ESCAPE;
        }
        return true;
      }
      case CONTROL_STRING_AFTER_ESCAPE -> {
        escape = character == '\\' ? EscapeState.NONE : EscapeState.IN_CONTROL_STRING;
        return true;
      }
    }
    throw new IllegalStateException("unknown ANSI parser state " + escape);
  }

  private void processCsi(char command, String rawParameters) {
    switch (command) {
      case 'm' -> applySgr(rawParameters);
      case 'A', 'F' -> rewindLines(countParameter(rawParameters));
      case 'K' -> {
        int mode = firstParameter(rawParameters);
        if (mode == 1 || mode == 2) {
          current.clear();
        }
      }
      default -> {
        // The Console is a transcript. Other cursor-addressed commands are
        // omitted from display and remain byte-exact in the raw logs.
      }
    }
  }

  /**
   * Moves a transcript cursor into its mutable tail.
   *
   * <p>Bazel emits cursor-up, erase-line, then a replacement progress block. Removing those recent
   * committed lines gives the same visible result without turning the whole Console into an
   * interactive terminal screen. The view receives the earliest changed line and truncates only
   * that document tail.
   */
  private void rewindLines(int requested) {
    int count = Math.min(requested, styledLines.size());
    if (count > 0) {
      long firstChanged = droppedLines + styledLines.size() - count;
      changedFromLine = Math.min(changedFromLine, firstChanged);
      for (int index = 0; index < count; index++) {
        styledLines.removeLast();
      }
    }
    current.clear();
  }

  private void applySgr(String rawParameters) {
    if (rawParameters.isEmpty()) {
      ansi.reset();
      return;
    }
    int[] parameters;
    try {
      parameters = parameters(rawParameters);
    } catch (NumberFormatException malformed) {
      return;
    }
    for (int index = 0; index < parameters.length; index++) {
      int parameter = parameters[index];
      if (parameter == 38 || parameter == 48) {
        int consumed = applyExtendedColour(parameter == 38, parameters, index);
        index += consumed;
      } else {
        ansi.apply(parameter);
      }
    }
  }

  /** Returns how many parameters after {@code index} were consumed. */
  private int applyExtendedColour(boolean foreground, int[] parameters, int index) {
    if (index + 1 >= parameters.length) {
      return 0;
    }
    if (parameters[index + 1] == 5) {
      if (index + 2 >= parameters.length) {
        return parameters.length - index - 1;
      }
      int colour = parameters[index + 2];
      if (colour >= 0 && colour <= 255) {
        ansi.setColour(foreground, indexedColour(colour));
      }
      return 2;
    }
    if (parameters[index + 1] == 2) {
      if (index + 4 >= parameters.length) {
        return parameters.length - index - 1;
      }
      int red = parameters[index + 2];
      int green = parameters[index + 3];
      int blue = parameters[index + 4];
      if (isByte(red) && isByte(green) && isByte(blue)) {
        ansi.setColour(foreground, (red << 16) | (green << 8) | blue);
      }
      return 4;
    }
    return 0;
  }

  private static int firstParameter(String rawParameters) {
    try {
      int[] parsed = parameters(rawParameters);
      return parsed.length == 0 ? 0 : parsed[0];
    } catch (NumberFormatException malformed) {
      return 0;
    }
  }

  private static int countParameter(String rawParameters) {
    int value = firstParameter(rawParameters);
    return value <= 0 ? 1 : value;
  }

  private static int[] parameters(String rawParameters) {
    if (rawParameters.isEmpty()) {
      return new int[0];
    }
    String[] fields = rawParameters.split(";", -1);
    int[] values = new int[fields.length];
    for (int index = 0; index < fields.length; index++) {
      if (fields[index].isEmpty()) {
        values[index] = 0;
        continue;
      }
      values[index] = Integer.parseInt(fields[index]);
    }
    return values;
  }

  private static int indexedColour(int index) {
    if (index < ANSI_COLOURS.length) {
      return ANSI_COLOURS[index];
    }
    if (index < 232) {
      int cube = index - 16;
      int red = cube / 36;
      int green = (cube / 6) % 6;
      int blue = cube % 6;
      return (cubeComponent(red) << 16) | (cubeComponent(green) << 8) | cubeComponent(blue);
    }
    int grey = 8 + (index - 232) * 10;
    return (grey << 16) | (grey << 8) | grey;
  }

  private static int cubeComponent(int value) {
    return value == 0 ? 0 : 55 + value * 40;
  }

  private static boolean isByte(int value) {
    return value >= 0 && value <= 255;
  }

  /** Commits whatever is in the partial line, as if a newline had arrived. */
  public void flushPartialLine() {
    if (!current.isEmpty()) {
      commitLine();
    }
  }

  private void commitLine() {
    StyledLine styled = current.snapshot();
    styledLines.addLast(styled);
    current.clear();
    while (styledLines.size() > maxLines) {
      styledLines.removeFirst();
      droppedLines++;
    }
  }

  /** The retained plain-text lines, oldest first. */
  public List<String> lines() {
    return styledLines.stream().map(StyledLine::text).toList();
  }

  /** The retained styled lines, oldest first. */
  public List<StyledLine> styledLines() {
    return List.copyOf(styledLines);
  }

  /** Lines committed since the caller last asked, plus the matching ANSI runs. */
  public Delta since(long alreadySeen) {
    return since(alreadySeen, droppedLines);
  }

  /** Marks pending tail mutations as represented after a complete document rebuild. */
  void acknowledgeChanges() {
    changedFromLine = Long.MAX_VALUE;
  }

  /**
   * Returns a document delta, including tail replacement caused by cursor-up progress redraws.
   *
   * @param alreadySeen global line index immediately after the last rendered committed line
   * @param renderedBase global line index represented by the first line still in the document
   */
  Delta since(long alreadySeen, long renderedBase) {
    long available = droppedLines + styledLines.size();
    boolean resetRequired = renderedBase < droppedLines || alreadySeen < droppedLines;
    long replaceFrom = resetRequired ? droppedLines : Math.min(alreadySeen, changedFromLine);
    if (replaceFrom < droppedLines) {
      replaceFrom = droppedLines;
      resetRequired = true;
    } else if (replaceFrom > available) {
      replaceFrom = available;
    }
    int skip = (int) (replaceFrom - droppedLines);
    List<StyledLine> styled = List.copyOf(styledLines);
    List<StyledLine> freshStyled =
        skip >= styled.size() ? List.of() : styled.subList(skip, styled.size());
    changedFromLine = Long.MAX_VALUE;
    return new Delta(freshStyled, droppedLines, replaceFrom, available, resetRequired);
  }

  /** What changed since a caller last rendered. */
  public record Delta(
      List<StyledLine> styledLines,
      long dropped,
      long replaceFrom,
      long total,
      boolean resetRequired) {
    public Delta {
      styledLines = List.copyOf(styledLines);
    }

    /** Plain-text compatibility view used by copy/search callers and focused tests. */
    public List<String> lines() {
      return styledLines.stream().map(StyledLine::text).toList();
    }
  }

  /** One displayed line split into adjacent ANSI style runs. */
  public record StyledLine(List<StyledRun> runs) {
    public StyledLine {
      runs = List.copyOf(runs);
    }

    public String text() {
      StringBuilder text = new StringBuilder();
      for (StyledRun run : runs) {
        text.append(run.text());
      }
      return text.toString();
    }
  }

  /** One non-empty run of text with the same ANSI attributes. */
  public record StyledRun(String text, AnsiStyle style) {
    public StyledRun {
      Objects.requireNonNull(text, "text");
      Objects.requireNonNull(style, "style");
      if (text.isEmpty()) {
        throw new IllegalArgumentException("styled run cannot be empty");
      }
    }
  }

  /** ANSI attributes. Null colours mean the current application-theme default. */
  public record AnsiStyle(
      Integer foregroundRgb,
      Integer backgroundRgb,
      boolean bold,
      boolean faint,
      boolean italic,
      boolean underline,
      boolean inverse,
      boolean concealed,
      boolean strikethrough) {}

  /** The line still being built, which has not ended in a newline yet. */
  public String partialLine() {
    return current.text();
  }

  /** The styled line still being built. */
  public StyledLine partialStyledLine() {
    return current.snapshot();
  }

  /** How many lines the cap discarded. Shown, never hidden. */
  public long droppedLines() {
    return droppedLines;
  }

  public int retainedLines() {
    return styledLines.size();
  }

  public void clear() {
    styledLines.clear();
    current.clear();
    ansi.reset();
    csiParameters.setLength(0);
    csiOverflow = false;
    droppedLines = 0;
    changedFromLine = Long.MAX_VALUE;
    escape = EscapeState.NONE;
  }

  /** Everything retained, as plain text, for copy/search and tests. */
  public String text() {
    StringBuilder out = new StringBuilder();
    for (StyledLine line : styledLines) {
      out.append(line.text()).append('\n');
    }
    out.append(current.text());
    return out.toString();
  }

  /** Immutable display state, safe to hand from a journal-reading worker to the EDT. */
  public Transcript transcript() {
    return new Transcript(List.copyOf(styledLines), partialStyledLine(), droppedLines);
  }

  /** Styled output after progress rewrites, with exact disclosure of evicted display lines. */
  public record Transcript(List<StyledLine> lines, StyledLine partialLine, long droppedLines) {
    public Transcript {
      lines = List.copyOf(lines);
      Objects.requireNonNull(partialLine, "partialLine");
    }

    public String text() {
      StringBuilder text = new StringBuilder();
      for (StyledLine line : lines) {
        text.append(line.text()).append('\n');
      }
      return text.append(partialLine.text()).toString();
    }
  }

  private static final class StyledLineBuilder {

    private final List<MutableRun> runs = new ArrayList<>();

    private void append(char character, AnsiStyle style) {
      if (runs.isEmpty() || !runs.getLast().style.equals(style)) {
        runs.add(new MutableRun(style));
      }
      runs.getLast().text.append(character);
    }

    private void backspace() {
      if (runs.isEmpty()) {
        return;
      }
      MutableRun last = runs.getLast();
      last.text.setLength(last.text.length() - 1);
      if (last.text.isEmpty()) {
        runs.removeLast();
      }
    }

    private void clear() {
      runs.clear();
    }

    private boolean isEmpty() {
      return runs.isEmpty();
    }

    private String text() {
      StringBuilder text = new StringBuilder();
      for (MutableRun run : runs) {
        text.append(run.text);
      }
      return text.toString();
    }

    private StyledLine snapshot() {
      List<StyledRun> copied = new ArrayList<>(runs.size());
      for (MutableRun run : runs) {
        copied.add(new StyledRun(run.text.toString(), run.style));
      }
      return new StyledLine(copied);
    }
  }

  private static final class MutableRun {

    private final AnsiStyle style;
    private final StringBuilder text = new StringBuilder();

    private MutableRun(AnsiStyle style) {
      this.style = style;
    }
  }

  private static final class AnsiState {

    private Integer foregroundRgb;
    private Integer backgroundRgb;
    private boolean bold;
    private boolean faint;
    private boolean italic;
    private boolean underline;
    private boolean inverse;
    private boolean concealed;
    private boolean strikethrough;

    private void reset() {
      foregroundRgb = null;
      backgroundRgb = null;
      bold = false;
      faint = false;
      italic = false;
      underline = false;
      inverse = false;
      concealed = false;
      strikethrough = false;
    }

    private void apply(int parameter) {
      switch (parameter) {
        case 0 -> reset();
        case 1 -> bold = true;
        case 2 -> faint = true;
        case 3 -> italic = true;
        case 4, 21 -> underline = true;
        case 7 -> inverse = true;
        case 8 -> concealed = true;
        case 9 -> strikethrough = true;
        case 22 -> {
          bold = false;
          faint = false;
        }
        case 23 -> italic = false;
        case 24 -> underline = false;
        case 27 -> inverse = false;
        case 28 -> concealed = false;
        case 29 -> strikethrough = false;
        case 30, 31, 32, 33, 34, 35, 36, 37 -> foregroundRgb = ANSI_COLOURS[parameter - 30];
        case 39 -> foregroundRgb = null;
        case 40, 41, 42, 43, 44, 45, 46, 47 -> backgroundRgb = ANSI_COLOURS[parameter - 40];
        case 49 -> backgroundRgb = null;
        case 90, 91, 92, 93, 94, 95, 96, 97 -> foregroundRgb = ANSI_COLOURS[8 + parameter - 90];
        case 100, 101, 102, 103, 104, 105, 106, 107 ->
            backgroundRgb = ANSI_COLOURS[8 + parameter - 100];
        default -> {
          // Unknown SGR attributes are harmless and remain preserved in the raw log.
        }
      }
    }

    private void setColour(boolean foreground, int rgb) {
      if (foreground) {
        foregroundRgb = rgb;
      } else {
        backgroundRgb = rgb;
      }
    }

    private AnsiStyle snapshot() {
      return new AnsiStyle(
          foregroundRgb,
          backgroundRgb,
          bold,
          faint,
          italic,
          underline,
          inverse,
          concealed,
          strikethrough);
    }
  }
}
