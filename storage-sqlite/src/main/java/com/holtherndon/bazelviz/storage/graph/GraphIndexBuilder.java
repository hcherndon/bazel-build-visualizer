package com.holtherndon.bazelviz.storage.graph;

import com.holtherndon.bazelviz.core.graph.EdgeDerivation;
import com.holtherndon.bazelviz.graph.CsrBuilder;
import com.holtherndon.bazelviz.graph.CsrFile;
import com.holtherndon.bazelviz.graph.CsrGraph;
import com.holtherndon.bazelviz.graph.EdgeStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

/**
 * Builds and registers the forward and reverse CSR indexes for a derived edge
 * set.
 *
 * <h2>Never an object per node or per edge</h2>
 *
 * <p>Plan 13.2. {@link CsrBuilder} reads the edge stream twice — once to count
 * degrees, once to fill — and the stream here is a SQL query replayed, so at no
 * point does the edge set exist in Java. The two primitive arrays the builder
 * produces are the whole of the memory cost, and they go straight to a file.
 *
 * <h2>Both directions, from one edge set</h2>
 *
 * <p>The reverse index is {@link CsrBuilder#reverse}, not a second query with
 * the columns swapped. They must agree — plan 24 makes "forward and reverse
 * indexes are consistent" an exit criterion — and deriving one from the other
 * makes disagreement impossible rather than merely unlikely.
 */
public final class GraphIndexBuilder {

    private static final String NODE_COUNT =
            "SELECT count(*) FROM declared_actions WHERE node_index IS NOT NULL";

    private static final String EDGES =
            "SELECT p.node_index, c.node_index FROM action_edges e"
                    + " JOIN declared_actions p ON p.id = e.producer_id"
                    + " JOIN declared_actions c ON c.id = e.consumer_id"
                    + " WHERE e.derivation = ?"
                    + "   AND p.node_index IS NOT NULL AND c.node_index IS NOT NULL";

    private static final String REGISTER =
            "INSERT INTO graph_indexes (kind, direction, file_name, format_version,"
                    + " node_count, edge_count, checksum, built_micros, source_id)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"
                    + " ON CONFLICT (kind, direction) DO UPDATE SET"
                    + " file_name = excluded.file_name,"
                    + " format_version = excluded.format_version,"
                    + " node_count = excluded.node_count, edge_count = excluded.edge_count,"
                    + " checksum = excluded.checksum, built_micros = excluded.built_micros,"
                    + " source_id = excluded.source_id";

    private final Connection connection;
    private final Path directory;
    private final java.util.function.LongSupplier clock;

    /**
     * @param directory where index files live; created if absent
     */
    public GraphIndexBuilder(Connection connection, Path directory) {
        this(connection, directory, () -> System.currentTimeMillis() * 1_000L);
    }

    GraphIndexBuilder(
            Connection connection, Path directory, java.util.function.LongSupplier clock) {
        this.connection = connection;
        this.directory = directory;
        this.clock = clock;
    }

    /**
     * Builds and registers both directions for one derivation.
     *
     * @return what was built, or empty when the graph has no nodes — a session
     *     with no imported action graph, which is not a failure
     */
    public Optional<Result> build(EdgeDerivation derivation) throws SQLException, IOException {
        int nodeCount = Math.toIntExact(scalar(NODE_COUNT));
        if (nodeCount == 0) {
            return Optional.empty();
        }
        Files.createDirectories(directory);

        CsrGraph forward = CsrBuilder.build(nodeCount, edgeStream(derivation));
        CsrGraph reverse = CsrBuilder.reverse(forward);

        Path forwardFile = directory.resolve(fileName(derivation, "forward"));
        Path reverseFile = directory.resolve(fileName(derivation, "reverse"));
        long forwardChecksum = CsrFile.write(forward, forwardFile);
        long reverseChecksum = CsrFile.write(reverse, reverseFile);

        register(derivation, "FORWARD", forwardFile, forward, forwardChecksum);
        register(derivation, "REVERSE", reverseFile, reverse, reverseChecksum);

        return Optional.of(new Result(
                nodeCount, forward.edgeCount(), forwardFile, reverseFile));
    }

    /**
     * The edges, as a stream the builder may replay.
     *
     * <p>Replayable is the contract {@link EdgeStream} states, and a fresh
     * query satisfies it: the two passes see the same rows because nothing
     * writes between them. A materialised list would satisfy it too, and would
     * be the object-per-edge this design exists to avoid.
     */
    private EdgeStream edgeStream(EdgeDerivation derivation) {
        return visitor -> {
            try (PreparedStatement statement = connection.prepareStatement(EDGES)) {
                statement.setString(1, derivation.name());
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        visitor.edge(rows.getInt(1), rows.getInt(2));
                    }
                }
            } catch (SQLException failure) {
                throw new UncheckedEdgeException(failure);
            }
        };
    }

    private void register(
            EdgeDerivation derivation, String direction, Path file, CsrGraph graph, long checksum)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(REGISTER)) {
            statement.setString(1, derivation.name());
            statement.setString(2, direction);
            statement.setString(3, file.getFileName().toString());
            statement.setInt(4, CsrFile.FORMAT_VERSION);
            statement.setLong(5, graph.nodeCount());
            statement.setLong(6, graph.edgeCount());
            statement.setString(7, Long.toHexString(checksum));
            statement.setLong(8, clock.getAsLong());
            statement.setNull(9, java.sql.Types.INTEGER);
            statement.executeUpdate();
        }
    }

    /**
     * Loads a registered index, checking it against what the database says.
     *
     * <p>A file whose header disagrees with its row is refused rather than
     * used. A stale index is worse than none: it answers, and its answers look
     * like the others.
     */
    public Optional<CsrGraph> load(EdgeDerivation derivation, String direction)
            throws SQLException, IOException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT file_name, node_count, edge_count, checksum FROM graph_indexes"
                        + " WHERE kind = ? AND direction = ?")) {
            statement.setString(1, derivation.name());
            statement.setString(2, direction);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return Optional.empty();
                }
                Path file = directory.resolve(rows.getString("file_name"));
                if (!Files.exists(file)) {
                    return Optional.empty();
                }
                CsrFile.Header header = CsrFile.headerOf(file);
                if (header.nodeCount() != rows.getLong("node_count")
                        || header.edgeCount() != rows.getLong("edge_count")
                        || !Long.toHexString(header.checksum()).equals(rows.getString("checksum"))) {
                    throw new StaleIndexException(file);
                }
                return Optional.of(CsrFile.read(file));
            }
        }
    }

    private static String fileName(EdgeDerivation derivation, String direction) {
        return derivation.name().toLowerCase(java.util.Locale.ROOT)
                + "-" + direction + ".csr";
    }

    private long scalar(String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet rows = statement.executeQuery()) {
            return rows.next() ? rows.getLong(1) : 0;
        }
    }

    /** What was built. */
    public record Result(int nodeCount, long edgeCount, Path forwardFile, Path reverseFile) {}

    /** The file on disk is not the index the database registered. */
    public static final class StaleIndexException extends IOException {

        private static final long serialVersionUID = 1L;

        StaleIndexException(Path file) {
            super(file + " does not match the index this session registered. It is stale,"
                    + " and a stale graph index answers questions wrongly rather than"
                    + " refusing them, so it is not used.");
        }
    }

    /** Carries a {@link SQLException} out of the edge stream. */
    static final class UncheckedEdgeException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        UncheckedEdgeException(SQLException cause) {
            super(cause.getMessage(), cause);
        }
    }
}
