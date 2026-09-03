package com.holtherndon.bazelviz.capture.live;

import com.holtherndon.bazelviz.runner.proc.ConsoleSink;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writes the build's console output to {@code raw/stdout.log} and {@code raw/stderr.log}, verbatim
 * (plan 10.2, ADR-004).
 *
 * <h2>Bytes, unmodified</h2>
 *
 * <p>Nothing is decoded, re-encoded, line-split or stripped of ANSI escapes. Bazel's progress
 * display is carriage returns and cursor movement, and a "helpful" cleanup here would destroy the
 * only record of what the user actually saw. Rendering is the console view's problem, from these
 * bytes.
 *
 * <h2>Two files, two threads</h2>
 *
 * <p>One writer per stream, each written from its own pump thread, so neither blocks the other.
 * That is also why the interleaving between the two files is not recoverable: they are separate
 * pipes drained by separate threads, and plan 4.1 says as much — ordering between the streams is
 * approximate, and this class does not pretend otherwise by merging them.
 */
public final class ConsoleCapture implements ConsoleSink, AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(ConsoleCapture.class);

  private final OutputStream stdout;
  private final OutputStream stderr;
  private final ConsoleSink downstream;
  private final AtomicLong stdoutBytes = new AtomicLong();
  private final AtomicLong stderrBytes = new AtomicLong();
  private volatile boolean closed;

  /**
   * Set when a write to either log failed.
   *
   * <p>The failure cannot be thrown — the thread that calls this is also what keeps the build's
   * pipe drained, and letting an exception out would stall the build over a log file. But
   * swallowing it and then recording the log as COMPLETE would make the session claim a console
   * capture it does not have, so the fact is kept and the manifest asks for it.
   */
  private volatile boolean writeFailed;

  private ConsoleCapture(OutputStream stdout, OutputStream stderr, ConsoleSink downstream) {
    this.stdout = stdout;
    this.stderr = stderr;
    this.downstream = downstream;
  }

  /**
   * Opens both log files under {@code rawDirectory}.
   *
   * @param downstream also receives every chunk, for a live console view; {@link
   *     ConsoleSink#discarding()} for a headless capture
   */
  public static ConsoleCapture open(Path rawDirectory, ConsoleSink downstream) throws IOException {
    Objects.requireNonNull(rawDirectory, "rawDirectory");
    Objects.requireNonNull(downstream, "downstream");
    Files.createDirectories(rawDirectory);
    OutputStream out = null;
    try {
      out = newLog(rawDirectory.resolve(ConsoleStream.STDOUT.fileName()));
      OutputStream err = newLog(rawDirectory.resolve(ConsoleStream.STDERR.fileName()));
      return new ConsoleCapture(out, err, downstream);
    } catch (IOException failure) {
      if (out != null) {
        try {
          out.close();
        } catch (IOException closeFailure) {
          failure.addSuppressed(closeFailure);
        }
      }
      throw failure;
    }
  }

  private static OutputStream newLog(Path file) throws IOException {
    return Files.newOutputStream(
        file,
        StandardOpenOption.CREATE,
        StandardOpenOption.WRITE,
        StandardOpenOption.TRUNCATE_EXISTING);
  }

  @Override
  public void accept(ConsoleStream stream, byte[] data, int offset, int length) {
    if (closed || length <= 0) {
      return;
    }
    OutputStream target = stream == ConsoleStream.STDOUT ? stdout : stderr;
    AtomicLong counter = stream == ConsoleStream.STDOUT ? stdoutBytes : stderrBytes;
    synchronized (target) {
      try {
        target.write(data, offset, length);
        counter.addAndGet(length);
      } catch (IOException failure) {
        // Logged, not thrown: the pump thread that calls this is also
        // what keeps the build's pipe drained, and letting an exception
        // out would stall the build over a console log.
        writeFailed = true;
        log.warn("could not write {} to the session log", stream, failure);
      }
    }
    try {
      downstream.accept(stream, data, offset, length);
    } catch (RuntimeException misbehaving) {
      log.warn("a console listener failed", misbehaving);
    }
  }

  public long stdoutBytes() {
    return stdoutBytes.get();
  }

  public long stderrBytes() {
    return stderrBytes.get();
  }

  /** True when any console bytes could not be written to their log. */
  public boolean hasWriteFailure() {
    return writeFailed;
  }

  @Override
  public void close() throws IOException {
    if (closed) {
      return;
    }
    closed = true;
    IOException failure = null;
    // A close that fails can leave buffered bytes unwritten, so it counts
    // as a write failure for the purpose of the manifest.

    synchronized (stdout) {
      try {
        stdout.close();
      } catch (IOException problem) {
        failure = problem;
      }
    }
    synchronized (stderr) {
      try {
        stderr.close();
      } catch (IOException problem) {
        if (failure == null) {
          failure = problem;
        } else {
          failure.addSuppressed(problem);
        }
      }
    }
    if (failure != null) {
      writeFailed = true;
      throw failure;
    }
  }
}
