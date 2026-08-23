package com.holtherndon.bazelviz.graph;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.zip.CRC32C;

/**
 * Reads and writes a {@link CsrGraph} as a file.
 *
 * <h2>Layout</h2>
 *
 * <pre>
 *   magic          8 bytes   "BBVCSR01"
 *   formatVersion  4 bytes
 *   flags          4 bytes   reserved, zero
 *   nodeCount      8 bytes
 *   edgeCount      8 bytes
 *   checksum       8 bytes   CRC32C of everything after this field
 *   offsets        8 bytes x (nodeCount + 1)
 *   targets        4 bytes x edgeCount
 * </pre>
 *
 * <p>Little-endian throughout, because every machine this runs on is, and a
 * byte order that matches the hardware is what lets the arrays be read by bulk
 * copy rather than element by element.
 *
 * <h2>Why a file at all</h2>
 *
 * <p>Plan 13.2. Rebuilding the CSR from SQLite costs a full scan of the edge
 * table on every session open, which at a hundred million edges is not a thing
 * to do while a user waits. The file is the same two primitive arrays the
 * in-memory graph holds, so loading it is one read.
 *
 * <h2>Atomic rename, and why the checksum is not enough</h2>
 *
 * <p>Written to a sibling temporary name and moved into place with
 * {@code ATOMIC_MOVE}, so a crashed build leaves a stray temp file rather than
 * a half-written index under the name a reader trusts. The checksum catches
 * corruption after the fact; the rename stops a reader ever seeing a partial
 * file in the first place, which matters because a truncated CSR is not
 * detectably wrong — it is a smaller, perfectly valid graph.
 */
public final class CsrFile {

    /** Identifies the format and its version in one 8-byte word. */
    static final byte[] MAGIC = {'B', 'B', 'V', 'C', 'S', 'R', '0', '1'};

    /** The layout this build writes and reads. */
    public static final int FORMAT_VERSION = 1;

    static final int HEADER_BYTES = 8 + 4 + 4 + 8 + 8 + 8;

    private CsrFile() {}

    /**
     * Writes {@code graph} to {@code file}, atomically.
     *
     * @return the checksum recorded in the header, for the index registry
     */
    public static long write(CsrGraph graph, Path file) throws IOException {
        long nodeCount = graph.nodeCount();
        long edgeCount = graph.edgeCount();
        Path temporary = file.resolveSibling(file.getFileName() + ".building");

        CRC32C crc = new CRC32C();
        ByteBuffer body = ByteBuffer
                .allocate(Math.toIntExact((nodeCount + 1) * Long.BYTES + edgeCount * Integer.BYTES))
                .order(ByteOrder.LITTLE_ENDIAN);
        for (long node = 0; node <= nodeCount; node++) {
            body.putLong(node == nodeCount ? edgeCount : graph.neighborsBegin((int) node));
        }
        for (long edge = 0; edge < edgeCount; edge++) {
            body.putInt(graph.neighborAt(edge));
        }
        body.flip();
        crc.update(body.duplicate());
        long checksum = crc.getValue();

        ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        header.put(MAGIC);
        header.putInt(FORMAT_VERSION);
        header.putInt(0);
        header.putLong(nodeCount);
        header.putLong(edgeCount);
        header.putLong(checksum);
        header.flip();

        try (FileChannel channel = FileChannel.open(temporary,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE)) {
            channel.write(new ByteBuffer[] {header, body});
            // Forced before the rename: an atomic rename of unflushed bytes is
            // an atomic rename of nothing.
            channel.force(true);
        }
        Files.move(temporary, file,
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return checksum;
    }

    /**
     * Reads {@code file}, verifying its header and checksum.
     *
     * @throws CsrFormatException when the file is not a CSR index this build
     *     reads, or when its contents do not match its checksum
     */
    public static CsrGraph read(Path file) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            long size = channel.size();
            if (size < HEADER_BYTES) {
                throw new CsrFormatException(file, "shorter than a header");
            }
            ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN);
            channel.read(header);
            header.flip();

            byte[] magic = new byte[MAGIC.length];
            header.get(magic);
            if (!java.util.Arrays.equals(magic, MAGIC)) {
                throw new CsrFormatException(file, "not a CSR index");
            }
            int version = header.getInt();
            if (version != FORMAT_VERSION) {
                // Refused rather than guessed at. An index whose layout this
                // build does not know is rebuilt, which is cheap; reading it
                // wrongly produces a graph that answers.
                throw new CsrFormatException(file,
                        "format version " + version + ", and this build reads " + FORMAT_VERSION);
            }
            header.getInt();
            long nodeCount = header.getLong();
            long edgeCount = header.getLong();
            long expected = header.getLong();

            long bodyBytes = (nodeCount + 1) * Long.BYTES + edgeCount * Integer.BYTES;
            if (size - HEADER_BYTES != bodyBytes) {
                throw new CsrFormatException(file, "header promises " + bodyBytes
                        + " bytes of graph and the file holds " + (size - HEADER_BYTES));
            }

            ByteBuffer body = channel.map(FileChannel.MapMode.READ_ONLY, HEADER_BYTES, bodyBytes)
                    .order(ByteOrder.LITTLE_ENDIAN);
            CRC32C crc = new CRC32C();
            crc.update(body.duplicate());
            if (crc.getValue() != expected) {
                throw new CsrFormatException(file, "checksum does not match its contents");
            }

            long[] offsets = new long[Math.toIntExact(nodeCount + 1)];
            body.asLongBuffer().get(offsets);
            int[] targets = new int[Math.toIntExact(edgeCount)];
            body.position(Math.toIntExact((nodeCount + 1) * Long.BYTES));
            body.asIntBuffer().get(targets);
            return new CsrGraph(offsets, targets);
        }
    }

    /** The node and edge counts in a file's header, without reading its body. */
    public static Header headerOf(Path file) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN);
            channel.read(header);
            header.flip();
            byte[] magic = new byte[MAGIC.length];
            header.get(magic);
            if (!java.util.Arrays.equals(magic, MAGIC)) {
                throw new CsrFormatException(file, "not a CSR index");
            }
            int version = header.getInt();
            header.getInt();
            return new Header(version, header.getLong(), header.getLong(), header.getLong());
        }
    }

    /** What a file's header says about it. */
    public record Header(int formatVersion, long nodeCount, long edgeCount, long checksum) {}

    /** The file is not a CSR index this build can read. */
    public static final class CsrFormatException extends IOException {

        private static final long serialVersionUID = 1L;

        CsrFormatException(Path file, String why) {
            super(file + " is not a usable graph index: " + why);
        }
    }
}
