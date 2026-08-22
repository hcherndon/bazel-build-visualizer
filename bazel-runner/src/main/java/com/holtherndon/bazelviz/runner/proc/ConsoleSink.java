package com.holtherndon.bazelviz.runner.proc;

/**
 * Receives the launched process's console output as it arrives.
 *
 * <h2>Bytes, not lines</h2>
 *
 * <p>The sink takes byte ranges rather than decoded strings because Bazel's
 * console output is not line-structured: it repaints a progress block with
 * carriage returns and ANSI cursor movement, and a line-splitting reader either
 * blocks until a newline that never comes or shreds the escape sequences. The
 * raw bytes go to {@code raw/stdout.log} and {@code raw/stderr.log} verbatim
 * (ADR-004), and rendering is the console view's problem, where the terminal
 * semantics belong.
 *
 * <h2>Threading</h2>
 *
 * <p>Called from the two pump threads, one per stream, never on the EDT. An
 * implementation that forwards to the UI must hop threads itself. Implementations
 * must not block for long: the pipe has a finite buffer, and a slow sink stalls
 * the build.
 */
@FunctionalInterface
public interface ConsoleSink {

    /**
     * @param stream which of the two streams this came from; their relative order
     *     is approximate, because they are separate pipes drained by separate
     *     threads (plan 4.1, "ordering between streams is approximate")
     * @param data buffer holding the bytes; <strong>not</strong> retained after
     *     this call returns, so an implementation that keeps them must copy
     * @param offset start of the valid range
     * @param length number of valid bytes
     */
    void accept(ConsoleStream stream, byte[] data, int offset, int length);

    /** Which console stream a chunk came from. */
    enum ConsoleStream {
        STDOUT,
        STDERR;

        /** The file this stream is journaled to, under the session's {@code raw/}. */
        public String fileName() {
            return this == STDOUT ? "stdout.log" : "stderr.log";
        }
    }

    /** A sink that discards everything, for callers that only want the exit code. */
    static ConsoleSink discarding() {
        return (stream, data, offset, length) -> {};
    }
}
