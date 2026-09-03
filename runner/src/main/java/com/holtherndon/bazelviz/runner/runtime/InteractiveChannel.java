package com.holtherndon.bazelviz.runner.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** A persistent, pull-based terminal channel. Hiding its UI must not close it. */
public interface InteractiveChannel extends AutoCloseable {

  /**
   * Reads the terminal's merged output stream.
   *
   * <p>Reads may block and must never run on the Swing EDT. The caller owns the single consumer of
   * this stream; closing the channel closes it.
   */
  InputStream input();

  void write(byte[] data, int offset, int length) throws IOException;

  default void write(String text) throws IOException {
    byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
    write(bytes, 0, bytes.length);
  }

  /** Updates the pseudo-terminal size reported to the interactive process. */
  void resize(TerminalSize size) throws IOException;

  boolean isOpen();

  int awaitExit() throws InterruptedException;

  @Override
  void close();
}
