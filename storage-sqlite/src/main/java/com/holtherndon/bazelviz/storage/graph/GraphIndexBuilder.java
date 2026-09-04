package com.holtherndon.bazelviz.storage.graph;

import com.holtherndon.bazelviz.core.graph.EdgeDerivation;
import com.holtherndon.bazelviz.graph.CsrBuilder;
import com.holtherndon.bazelviz.graph.CsrFile;
import com.holtherndon.bazelviz.graph.CsrGraph;
import com.holtherndon.bazelviz.graph.EdgeStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * Builds and registers the forward and reverse CSR indexes for a derived edge set.
 *
 * <h2>Never an object per node or per edge</h2>
 *
 * <p>Plan 13.2. {@link CsrBuilder} reads the edge stream twice — once to count degrees, once to
 * fill — and the stream here is a SQL query replayed, so at no point does the edge set exist in
 * Java. The two primitive arrays the builder produces are the whole of the memory cost, and they go
 * straight to a file.
 *
 * <h2>Both directions, from one edge set</h2>
 *
 * <p>The reverse index is {@link CsrBuilder#reverse}, not a second query with the columns swapped.
 * They must agree — plan 24 makes "forward and reverse indexes are consistent" an exit criterion —
 * and deriving one from the other makes disagreement impossible rather than merely unlikely.
 */
public final class GraphIndexBuilder {

  private static final String DECLARED_ACTIONS_SOURCE_KIND = "DECLARED_ACTIONS";

  /**
   * The {@code graph_indexes.kind} under which the configured-target label graph is registered.
   *
   * <p>Distinct from the two {@link EdgeDerivation} kinds on purpose: the label graph's nodes are
   * labels, not actions, and a registry that let the two share a kind would let a loader answer a
   * question about one graph with the other's file.
   */
  public static final String CONFIGURED_TARGETS_KIND = "CONFIGURED_TARGETS";

  private static final String NODE_COUNT =
      "SELECT count(*) FROM declared_actions WHERE node_index IS NOT NULL";

  /**
   * The label-graph node universe: every distinct label some configured target was analysed under,
   * in {@code label_id} order.
   *
   * <p>This ordering <em>is</em> the node numbering. The label graph has no {@code node_index}
   * column the way {@code declared_actions} does; instead the dense index of a label is its
   * position in this sorted list, which is a pure function of the imported rows — so the builder
   * and every query compute the same numbering from the same tables, and there is no stored mapping
   * to go stale. Labels are interned append-only, so a label's id never changes underneath a
   * session.
   */
  private static final String LABEL_UNIVERSE =
      "SELECT DISTINCT label_id FROM configured_target_nodes ORDER BY label_id";

  /**
   * The label-graph edges, in dependency-to-depender order once mapped.
   *
   * <p>{@code configured_target_edges} points from a configured target to a label it names as a
   * rule input. Only edges whose target label is itself an analysed configured target become graph
   * edges: an edge to a source file or an unanalysed label has no node to land on, exactly as the
   * action graph excludes source artifacts with no producing action. The excluded edges are
   * counted, never silently dropped — the count travels in the build {@link Result}.
   *
   * <p>{@code DISTINCT} collapses the same dependency seen through several configurations or
   * attributes into one label-level edge, which is what makes this the <em>label</em> graph.
   */
  private static final String LABEL_EDGES =
      "SELECT DISTINCT n.label_id, e.to_label_id FROM configured_target_edges e"
          + " JOIN configured_target_nodes n ON n.id = e.from_node_id"
          + " WHERE n.label_id <> e.to_label_id"
          + "   AND EXISTS (SELECT 1 FROM configured_target_nodes t"
          + "               WHERE t.label_id = e.to_label_id)";

  private static final String LABEL_EDGES_EXCLUDED =
      "SELECT count(*) FROM (SELECT DISTINCT n.label_id, e.to_label_id"
          + " FROM configured_target_edges e"
          + " JOIN configured_target_nodes n ON n.id = e.from_node_id"
          + " WHERE n.label_id <> e.to_label_id"
          + "   AND NOT EXISTS (SELECT 1 FROM configured_target_nodes t"
          + "                   WHERE t.label_id = e.to_label_id))";

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
  private final LongSupplier clock;

  /**
   * @param directory where index files live; created if absent
   */
  public GraphIndexBuilder(Connection connection, Path directory) {
    this(connection, directory, () -> System.currentTimeMillis() * 1_000L);
  }

  GraphIndexBuilder(Connection connection, Path directory, LongSupplier clock) {
    this.connection = connection;
    this.directory = directory;
    this.clock = clock;
  }

  /**
   * Removes every index derived from the declared-action import.
   *
   * <p>Called before a replacement aquery file is parsed. The CSR files may remain until the next
   * atomic rebuild, but without registry rows no reader can mistake those files for the replacement
   * graph if parsing or rebuilding later fails.
   */
  public static void invalidateActionIndexes(Connection connection) throws SQLException {
    invalidate(connection, EdgeDerivation.DECLARED.name(), EdgeDerivation.OBSERVED.name());
  }

  /** See {@link #invalidateActionIndexes}; this is the cquery label-graph form. */
  public static void invalidateConfiguredTargetIndexes(Connection connection) throws SQLException {
    invalidate(connection, CONFIGURED_TARGETS_KIND);
  }

  private static void invalidate(Connection connection, String... kinds) throws SQLException {
    String placeholders = String.join(", ", Collections.nCopies(kinds.length, "?"));
    try (PreparedStatement statement =
        connection.prepareStatement(
            "DELETE FROM graph_indexes WHERE kind IN (" + placeholders + ")")) {
      for (int i = 0; i < kinds.length; i++) {
        statement.setString(i + 1, kinds[i]);
      }
      statement.executeUpdate();
    }
  }

  /**
   * Builds and registers both directions for one derivation.
   *
   * @return what was built, or empty when the graph has no nodes — a session with no imported
   *     action graph, which is not a failure
   */
  public Optional<Result> build(EdgeDerivation derivation) throws SQLException, IOException {
    int nodeCount = Math.toIntExact(scalar(NODE_COUNT));
    if (nodeCount == 0) {
      return Optional.empty();
    }
    return Optional.of(buildAndRegister(derivation.name(), nodeCount, edgeStream(derivation), 0));
  }

  /**
   * Builds and registers both directions of the configured-target label graph.
   *
   * <p>The graph the {@code cquery} import populates and nothing read until now: node = a label,
   * numbered by {@link #configuredLabelUniverse}'s ordering; edge = "some analysed configuration of
   * the consumer names the producer as a rule input", stored producer-to-consumer like the action
   * graph so the two indexes answer the same questions the same way round.
   *
   * @return what was built, or empty when no cquery output was ever imported — which is not a
   *     failure
   */
  public Optional<Result> buildConfiguredTargets() throws SQLException, IOException {
    long[] universe = configuredLabelUniverse(connection);
    if (universe.length == 0) {
      return Optional.empty();
    }
    long excluded = scalar(LABEL_EDGES_EXCLUDED);
    Result built =
        buildAndRegister(
            CONFIGURED_TARGETS_KIND, universe.length, labelEdgeStream(universe), excluded);
    return Optional.of(built);
  }

  private Result buildAndRegister(String kind, int nodeCount, EdgeStream edges, long excluded)
      throws SQLException, IOException {
    Files.createDirectories(directory);

    CsrGraph forward = CsrBuilder.build(nodeCount, edges);
    CsrGraph reverse = CsrBuilder.reverse(forward);

    Path forwardFile = directory.resolve(fileName(kind, "forward"));
    Path reverseFile = directory.resolve(fileName(kind, "reverse"));
    long forwardChecksum = CsrFile.write(forward, forwardFile);
    long reverseChecksum = CsrFile.write(reverse, reverseFile, true);

    register(kind, "FORWARD", forwardFile, forward, forwardChecksum);
    register(kind, "REVERSE", reverseFile, reverse, reverseChecksum);

    return new Result(nodeCount, forward.edgeCount(), forwardFile, reverseFile, excluded);
  }

  /**
   * The sorted distinct label ids behind the label graph's node numbering.
   *
   * <p>Static and public within the package's contract because {@link GraphQueries} must translate
   * the same way: a label's node index is its position in this array, found by binary search.
   */
  public static long[] configuredLabelUniverse(Connection connection) throws SQLException {
    ArrayList<Long> ids = new ArrayList<>();
    try (PreparedStatement statement = connection.prepareStatement(LABEL_UNIVERSE);
        ResultSet rows = statement.executeQuery()) {
      while (rows.next()) {
        ids.add(rows.getLong(1));
      }
    }
    long[] out = new long[ids.size()];
    for (int i = 0; i < out.length; i++) {
      out[i] = ids.get(i);
    }
    return out;
  }

  /**
   * The label edges as dense node indexes, replayable.
   *
   * <p>Emitted producer-to-consumer: the stored edge says "consumer names producer as an input", so
   * the pair is flipped here once, and the forward index means the same thing for both graphs.
   */
  private EdgeStream labelEdgeStream(long[] universe) {
    return visitor -> {
      try (PreparedStatement statement = connection.prepareStatement(LABEL_EDGES);
          ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          int consumer = Arrays.binarySearch(universe, rows.getLong(1));
          int producer = Arrays.binarySearch(universe, rows.getLong(2));
          if (consumer < 0 || producer < 0) {
            // Cannot happen while the query and the universe read
            // the same tables; refusing is better than a wrong
            // node id in a file that outlives this method.
            throw new IllegalStateException(
                "an edge names a label outside the"
                    + " configured-target universe; the tables changed while the"
                    + " index was being built");
          }
          visitor.edge(producer, consumer);
        }
      } catch (SQLException failure) {
        throw new UncheckedEdgeException(failure);
      }
    };
  }

  /**
   * The edges, as a stream the builder may replay.
   *
   * <p>Replayable is the contract {@link EdgeStream} states, and a fresh query satisfies it: the
   * two passes see the same rows because nothing writes between them. A materialised list would
   * satisfy it too, and would be the object-per-edge this design exists to avoid.
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

  private void register(String kind, String direction, Path file, CsrGraph graph, long checksum)
      throws SQLException {
    Long sourceId = sourceIdFor(kind);
    try (PreparedStatement statement = connection.prepareStatement(REGISTER)) {
      statement.setString(1, kind);
      statement.setString(2, direction);
      statement.setString(3, file.getFileName().toString());
      statement.setInt(4, CsrFile.FORMAT_VERSION);
      statement.setLong(5, graph.nodeCount());
      statement.setLong(6, graph.edgeCount());
      statement.setString(7, Long.toHexString(checksum));
      statement.setLong(8, clock.getAsLong());
      if (sourceId == null) {
        statement.setNull(9, Types.INTEGER);
      } else {
        statement.setLong(9, sourceId);
      }
      statement.executeUpdate();
    }
  }

  private Long sourceIdFor(String indexKind) throws SQLException {
    String sourceKind = sourceKindFor(indexKind);
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT id FROM graph_sources WHERE kind = ?")) {
      statement.setString(1, sourceKind);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next() ? rows.getLong(1) : null;
      }
    }
  }

  private static String sourceKindFor(String indexKind) {
    return CONFIGURED_TARGETS_KIND.equals(indexKind)
        ? CONFIGURED_TARGETS_KIND
        : DECLARED_ACTIONS_SOURCE_KIND;
  }

  /**
   * Loads a registered index, checking it against what the database says.
   *
   * <p>A file whose header disagrees with its row is refused rather than used. A stale index is
   * worse than none: it answers, and its answers look like the others.
   */
  public Optional<CsrGraph> load(EdgeDerivation derivation, String direction)
      throws SQLException, IOException {
    return load(derivation.name(), direction);
  }

  /** Loads the configured-target label graph's registered index. */
  public Optional<CsrGraph> loadConfiguredTargets(String direction)
      throws SQLException, IOException {
    return load(CONFIGURED_TARGETS_KIND, direction);
  }

  private Optional<CsrGraph> load(String kind, String direction) throws SQLException, IOException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT gi.file_name, gi.node_count, gi.edge_count, gi.checksum,"
                + " gi.source_id, gs.kind AS source_kind, gs.state AS source_state"
                + " FROM graph_indexes gi"
                + " LEFT JOIN graph_sources gs ON gs.id = gi.source_id"
                + " WHERE gi.kind = ? AND gi.direction = ?")) {
      statement.setString(1, kind);
      statement.setString(2, direction);
      try (ResultSet rows = statement.executeQuery()) {
        if (!rows.next()) {
          return Optional.empty();
        }
        rows.getLong("source_id");
        if (!rows.wasNull()
            && (!sourceKindFor(kind).equals(rows.getString("source_kind"))
                || !"SUCCEEDED".equals(rows.getString("source_state")))) {
          return Optional.empty();
        }
        Path file = directory.resolve(rows.getString("file_name"));
        if (!Files.exists(file)) {
          return Optional.empty();
        }
        CsrFile.Header header = CsrFile.headerOf(file);
        if (header.nodeCount() != rows.getLong("node_count")
            || header.edgeCount() != rows.getLong("edge_count")
            || !Long.toHexString(header.checksum()).equals(rows.getString("checksum"))
            || header.reverseDirection() != "REVERSE".equals(direction)) {
          throw new StaleIndexException(file);
        }
        return Optional.of(CsrFile.read(file));
      }
    }
  }

  private static String fileName(String kind, String direction) {
    return kind.toLowerCase(Locale.ROOT) + "-" + direction + ".csr";
  }

  private long scalar(String sql) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql);
        ResultSet rows = statement.executeQuery()) {
      return rows.next() ? rows.getLong(1) : 0;
    }
  }

  /**
   * What was built.
   *
   * @param excludedEdges edges the source stored that this graph has no node for — for the label
   *     graph, rule inputs that are source files or labels the analysis did not cover. Zero for the
   *     action graph, whose edge derivation excludes source artifacts before they reach here.
   *     Counted so a caller can state the omission rather than imply the stored edges all made it
   *     in.
   */
  public record Result(
      int nodeCount, long edgeCount, Path forwardFile, Path reverseFile, long excludedEdges) {}

  /** The file on disk is not the index the database registered. */
  public static final class StaleIndexException extends IOException {

    private static final long serialVersionUID = 1L;

    StaleIndexException(Path file) {
      super(
          file
              + " does not match the index this session registered. It is stale,"
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
