package com.holtherndon.bazelviz.runner.runtime;

/** Positive terminal grid dimensions, in character cells. */
public record TerminalSize(int columns, int rows) {

  public TerminalSize {
    if (columns < 1) {
      throw new IllegalArgumentException("terminal columns must be positive");
    }
    if (rows < 1) {
      throw new IllegalArgumentException("terminal rows must be positive");
    }
  }
}
