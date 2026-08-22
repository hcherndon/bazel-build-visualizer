package com.holtherndon.bazelviz.testsupport.bep;

import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildEvent;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Iterator;

/**
 * Writes a byte-exact {@code --build_event_binary_file}: each {@code BuildEvent}
 * serialized with a varint length prefix, concatenated, nothing else. That is
 * precisely {@link BuildEvent#writeDelimitedTo(OutputStream)}, which is the
 * same call Bazel's binary transport makes, so the fixture is the real layout
 * rather than an approximation of it.
 *
 * <p>Events are consumed from an iterator and written one at a time; no list of
 * events is ever held, so a 200,000-event fixture costs one message of memory.
 */
public final class BepBinaryWriter {

    private BepBinaryWriter() {}

    /**
     * Writes {@code events} to {@code dest}, creating or truncating it.
     *
     * @return the number of bytes written
     */
    public static long write(Path dest, Iterator<BuildEvent> events) throws IOException {
        Path parent = dest.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (OutputStream raw = Files.newOutputStream(
                        dest,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE);
                CountingOutputStream counting = new CountingOutputStream(raw);
                OutputStream out = new BufferedOutputStream(counting, 1 << 16)) {
            writeAll(out, events);
            out.flush();
            return counting.count();
        }
    }

    /** Convenience: writes the whole of {@code stream} to {@code dest}. */
    public static long write(Path dest, SyntheticBepStream stream) throws IOException {
        return write(dest, stream.iterator());
    }

    /** Writes {@code events} to an already-open stream. Does not close or flush it. */
    public static void writeAll(OutputStream out, Iterator<BuildEvent> events) throws IOException {
        while (events.hasNext()) {
            events.next().writeDelimitedTo(out);
        }
    }

    private static final class CountingOutputStream extends OutputStream {
        private final OutputStream delegate;
        private long count;

        CountingOutputStream(OutputStream delegate) {
            this.delegate = delegate;
        }

        long count() {
            return count;
        }

        @Override
        public void write(int b) throws IOException {
            delegate.write(b);
            count++;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            delegate.write(b, off, len);
            count += len;
        }

        @Override
        public void flush() throws IOException {
            delegate.flush();
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }
}
