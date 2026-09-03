package com.holtherndon.bazelviz.ui.terminal;

import java.io.FilterReader;
import java.io.IOException;
import java.io.Reader;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Pass-through reader that rejects an overlong CSI sequence or OSC/DCS string.
 *
 * <p>JediTerm accumulates these strings until BEL or ST arrives. Counting while streaming preserves
 * ordinary terminal input without retaining another copy, while malformed terminal output cannot
 * grow JediTerm's accumulator forever.
 */
final class BoundedTerminalReader extends FilterReader {

  private static final char ESCAPE = 0x1b;
  private static final char BELL = 0x07;
  private static final char C1_DCS = 0x90;
  private static final char C1_CSI = 0x9b;
  private static final char C1_OSC = 0x9d;
  private static final char C1_STRING_TERMINATOR = 0x9c;

  private final int maximumCsiCharacters;
  private final int maximumControlStringCharacters;
  private final Consumer<IOException> failureListener;
  private final AtomicBoolean failureReported = new AtomicBoolean();
  private State state = State.TEXT;
  private int controlStringCharacters;

  BoundedTerminalReader(
      Reader source,
      int maximumCsiCharacters,
      int maximumControlStringCharacters,
      Consumer<IOException> failureListener) {
    super(Objects.requireNonNull(source, "source"));
    if (maximumCsiCharacters < 2) {
      throw new IllegalArgumentException("maximumCsiCharacters must be at least 2");
    }
    if (maximumControlStringCharacters < 2) {
      throw new IllegalArgumentException("maximumControlStringCharacters must be at least 2");
    }
    this.maximumCsiCharacters = maximumCsiCharacters;
    this.maximumControlStringCharacters = maximumControlStringCharacters;
    this.failureListener = Objects.requireNonNull(failureListener, "failureListener");
  }

  @Override
  public int read() throws IOException {
    int value = super.read();
    if (value >= 0) {
      inspect((char) value);
    }
    return value;
  }

  @Override
  public int read(char[] buffer, int offset, int length) throws IOException {
    Objects.checkFromIndexSize(offset, length, buffer.length);
    int count = super.read(buffer, offset, length);
    for (int index = 0; index < count; index++) {
      inspect(buffer[offset + index]);
    }
    return count;
  }

  private void inspect(char value) throws IOException {
    switch (state) {
      case TEXT -> inspectText(value);
      case AFTER_ESCAPE -> inspectAfterEscape(value);
      case CSI -> inspectCsi(value);
      case CONTROL_STRING -> inspectControlString(value);
      case CONTROL_STRING_AFTER_ESCAPE -> inspectControlStringAfterEscape(value);
    }
  }

  private void inspectText(char value) throws IOException {
    if (value == ESCAPE) {
      state = State.AFTER_ESCAPE;
    } else if (value == C1_CSI) {
      beginCsi(1);
    } else if (value == C1_OSC || value == C1_DCS) {
      beginControlString(1);
    }
  }

  private void inspectAfterEscape(char value) throws IOException {
    if (value == '[') {
      beginCsi(2);
    } else if (value == ']' || value == 'P') {
      beginControlString(2);
    } else {
      state = value == ESCAPE ? State.AFTER_ESCAPE : State.TEXT;
    }
  }

  private void beginCsi(int introducerCharacters) throws IOException {
    state = State.CSI;
    controlStringCharacters = introducerCharacters;
    enforceLimit(maximumCsiCharacters, "CSI sequence");
  }

  private void inspectCsi(char value) throws IOException {
    controlStringCharacters++;
    enforceLimit(maximumCsiCharacters, "CSI sequence");
    if (value >= 0x40 && value <= 0x7e) {
      finishControlSequence();
    }
  }

  private void beginControlString(int introducerCharacters) throws IOException {
    state = State.CONTROL_STRING;
    controlStringCharacters = introducerCharacters;
    enforceLimit(maximumControlStringCharacters, "control string");
  }

  private void inspectControlString(char value) throws IOException {
    controlStringCharacters++;
    enforceLimit(maximumControlStringCharacters, "control string");
    if (value == BELL || value == C1_STRING_TERMINATOR) {
      finishControlString();
    } else if (value == ESCAPE) {
      state = State.CONTROL_STRING_AFTER_ESCAPE;
    }
  }

  private void inspectControlStringAfterEscape(char value) throws IOException {
    controlStringCharacters++;
    enforceLimit(maximumControlStringCharacters, "control string");
    if (value == '\\' || value == BELL || value == C1_STRING_TERMINATOR) {
      finishControlSequence();
    } else {
      state = value == ESCAPE ? State.CONTROL_STRING_AFTER_ESCAPE : State.CONTROL_STRING;
    }
  }

  private void finishControlString() {
    finishControlSequence();
  }

  private void finishControlSequence() {
    state = State.TEXT;
    controlStringCharacters = 0;
  }

  private void enforceLimit(int maximumCharacters, String kind) throws IOException {
    if (controlStringCharacters <= maximumCharacters) {
      return;
    }
    IOException failure =
        new IOException(
            "terminal %s exceeded the %,d-character safety limit"
                .formatted(kind, maximumCharacters));
    if (failureReported.compareAndSet(false, true)) {
      failureListener.accept(failure);
    }
    throw failure;
  }

  private enum State {
    TEXT,
    AFTER_ESCAPE,
    CSI,
    CONTROL_STRING,
    CONTROL_STRING_AFTER_ESCAPE
  }
}
