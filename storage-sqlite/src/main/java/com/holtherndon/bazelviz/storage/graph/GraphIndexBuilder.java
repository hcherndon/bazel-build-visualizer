package com.holtherndon.bazelviz.storage.graph;

import com.holtherndon.bazelviz.core.graph.EdgeDerivation;
import com.holtherndon.bazelviz.graph.CsrFile;
import com.holtherndon.bazelviz.graph.EdgeStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.util.Collections;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Builds and registers the forward and reverse CSR indexes for a derived edge set.
 *
 * <h2>Never an object per node or per edge</h2>
 *
 * <p>Plan 13.2. Each ordered SQL result is streamed straight to a CSR file through fixed buffers;
 * no graph-sized Java array or object-per-edge representation exists on this path.
 *
 * <h2>Both directions, from one edge set</h2>
 *
 * <p>Both ordered queries run in one stable SQLite transaction. Generation-unique files are fully
 * forced first, then their registry rows publish in the same transaction; a failure cannot leave
 * one new direction reachable through an old pair.
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

  private static final String ORDERED_NODE_INDICES =
      "SELECT node_index FROM declared_actions"
          + " WHERE node_index IS NOT NULL ORDER BY node_index";

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
  private static final String LABEL_NODE_COUNT =
      "SELECT count(*) FROM (SELECT DISTINCT label_id FROM configured_target_nodes)";

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
  private static final String LABEL_NODES_CTE =
      "WITH label_nodes(label_id, node_index) AS ("
          + " SELECT label_id, row_number() OVER (ORDER BY label_id) - 1"
          + " FROM (SELECT DISTINCT label_id FROM configured_target_nodes)) ";

  private static final String LABEL_EDGES_FORWARD =
      LABEL_NODES_CTE
          + "SELECT producer.node_index, consumer.node_index"
          + " FROM configured_target_edges e"
          + " JOIN configured_target_nodes n ON n.id = e.from_node_id"
          + " JOIN label_nodes consumer ON consumer.label_id = n.label_id"
          + " JOIN label_nodes producer ON producer.label_id = e.to_label_id"
          + " WHERE n.label_id <> e.to_label_id"
          + " GROUP BY producer.node_index, consumer.node_index"
          + " ORDER BY producer.node_index, consumer.node_index";

  private static final String LABEL_EDGES_REVERSE =
      LABEL_NODES_CTE
          + "SELECT consumer.node_index, producer.node_index"
          + " FROM configured_target_edges e"
          + " JOIN configured_target_nodes n ON n.id = e.from_node_id"
          + " JOIN label_nodes consumer ON consumer.label_id = n.label_id"
          + " JOIN label_nodes producer ON producer.label_id = e.to_label_id"
          + " WHERE n.label_id <> e.to_label_id"
          + " GROUP BY consumer.node_index, producer.node_index"
          + " ORDER BY consumer.node_index, producer.node_index";

  private static final String LABEL_EDGES_EXCLUDED =
      "SELECT count(*) FROM (SELECT DISTINCT n.label_id, e.to_label_id"
          + " FROM configured_target_edges e"
          + " JOIN configured_target_nodes n ON n.id = e.from_node_id"
          + " WHERE n.label_id <> e.to_label_id"
          + "   AND NOT EXISTS (SELECT 1 FROM configured_target_nodes t"
          + "                   WHERE t.label_id = e.to_label_id))";

  private static final String EDGES_FORWARD =
      "SELECT p.node_index, c.node_index FROM action_edges e"
          + " JOIN declared_actions p ON p.id = e.producer_id"
          + " JOIN declared_actions c ON c.id = e.consumer_id"
          + " WHERE e.derivation = ?"
          + "   AND p.node_index IS NOT NULL AND c.node_index IS NOT NULL"
          + " ORDER BY p.node_index, c.node_index";

  private static final String EDGES_REVERSE =
      "SELECT c.node_index, p.node_index FROM action_edges e"
          + " JOIN declared_actions p ON p.id = e.producer_id"
          + " JOIN declared_actions c ON c.id = e.consumer_id"
          + " WHERE e.derivation = ?"
          + "   AND p.node_index IS NOT NULL AND c.node_index IS NOT NULL"
          + " ORDER BY c.node_index, p.node_index";

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
    Optional<Result> built =
        withFileBackedTempStore(
            () ->
                inStableTransaction(
                    () -> {
                      int nodeCount = validateDenseActionNodeIndices();
                      if (nodeCount == 0) {
                        return Optional.empty();
                      }
                      return Optional.of(
                          buildAndRegister(
                              derivation.name(),
                              nodeCount,
                              actionEdgeStream(derivation, false),
                              actionEdgeStream(derivation, true),
                              0));
                    }));
    built.ifPresent(result -> pruneSuperseded(derivation.name(), result));
    return built;
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
    Optional<Result> built =
        withFileBackedTempStore(
            () ->
                inStableTransaction(
                    () -> {
                      int nodeCount = Math.toIntExact(scalar(LABEL_NODE_COUNT));
                      if (nodeCount == 0) {
                        return Optional.empty();
                      }
                      long excluded = scalar(LABEL_EDGES_EXCLUDED);
                      Result result =
                          buildAndRegister(
                              CONFIGURED_TARGETS_KIND,
                              nodeCount,
                              sqlEdgeStream(LABEL_EDGES_FORWARD),
                              sqlEdgeStream(LABEL_EDGES_REVERSE),
                              excluded);
                      return Optional.of(result);
                    }));
    built.ifPresent(result -> pruneSuperseded(CONFIGURED_TARGETS_KIND, result));
    return built;
  }

  /** Best-effort removal of now-unregistered generations after the pair transaction commits. */
  private void pruneSuperseded(String kind, Result current) {
    Path checkedDirectory;
    try {
      checkedDirectory = requireIndexDirectory(false);
    } catch (IOException unsafeDirectory) {
      return;
    }
    if (!Files.isDirectory(checkedDirectory, LinkOption.NOFOLLOW_LINKS)) {
      return;
    }
    String prefix = kind.toLowerCase(Locale.ROOT) + "-";
    Path keptForward = current.forwardFile().toAbsolutePath().normalize();
    Path keptReverse = current.reverseFile().toAbsolutePath().normalize();
    try (var files = Files.newDirectoryStream(checkedDirectory, prefix + "*.csr")) {
      for (Path file : files) {
        Path normalized = file.toAbsolutePath().normalize();
        if (!normalized.equals(keptForward)
            && !normalized.equals(keptReverse)
            && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
          try {
            Files.deleteIfExists(file);
          } catch (IOException inUseOrUnremovable) {
            // A live mapped lease can keep the superseded file open on some platforms. It is
            // unregistered and therefore harmless; a later successful rebuild retries cleanup.
          }
        }
      }
    } catch (IOException unavailableDirectory) {
      // Publication already committed. Cleanup must never make the valid new pair look failed.
    }
  }

  /** Forces any SQLite sort/group temporary b-tree to disk for the duration of index building. */
  private <T> T withFileBackedTempStore(TransactionWork<T> work) throws SQLException, IOException {
    if (!connection.getAutoCommit()) {
      throw new IOException(
          "graph index construction requires an idle writer connection so its bounded"
              + " file-backed temporary-store setting cannot alter a caller transaction");
    }
    int previous;
    try (Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery("PRAGMA temp_store")) {
      previous = rows.next() ? rows.getInt(1) : 0;
    }
    Throwable failure = null;
    try (Statement statement = connection.createStatement()) {
      statement.execute("PRAGMA temp_store=FILE");
      return work.run();
    } catch (SQLException | IOException | RuntimeException caught) {
      failure = caught;
      throw caught;
    } finally {
      try (Statement statement = connection.createStatement()) {
        statement.execute("PRAGMA temp_store=" + previous);
      } catch (SQLException restoreFailure) {
        if (failure != null) {
          failure.addSuppressed(restoreFailure);
        } else {
          throw restoreFailure;
        }
      }
    }
  }

  private Result buildAndRegister(
      String kind, int nodeCount, EdgeStream forwardEdges, EdgeStream reverseEdges, long excluded)
      throws SQLException, IOException {
    requireIndexDirectory(true);
    String generation = UUID.randomUUID().toString();
    Path forwardFile = generationFile(kind, "forward", generation);
    Path reverseFile = generationFile(kind, "reverse", generation);
    try {
      CsrFile.WriteResult forward =
          CsrFile.writeOrdered(nodeCount, forwardEdges, forwardFile, false);
      CsrFile.WriteResult reverse =
          CsrFile.writeOrdered(nodeCount, reverseEdges, reverseFile, true);
      if (forward.edgeCount() != reverse.edgeCount()) {
        throw new IOException(
            "forward and reverse graph streams disagree: "
                + forward.edgeCount()
                + " edges against "
                + reverse.edgeCount());
      }
      registerPair(kind, nodeCount, forwardFile, forward, reverseFile, reverse);
      return new Result(nodeCount, forward.edgeCount(), forwardFile, reverseFile, excluded);
    } catch (SQLException | IOException | RuntimeException failure) {
      deleteFailedWrite(forwardFile, failure);
      deleteFailedWrite(reverseFile, failure);
      throw failure;
    }
  }

  private static void deleteFailedWrite(Path file, Throwable failure) {
    try {
      Files.deleteIfExists(file);
    } catch (IOException cleanupFailure) {
      failure.addSuppressed(cleanupFailure);
    }
  }

  private EdgeStream sqlEdgeStream(String sql) {
    return visitor -> {
      try (PreparedStatement statement = connection.prepareStatement(sql);
          ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          visitor.edge(rows.getInt(1), rows.getInt(2));
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
  private EdgeStream actionEdgeStream(EdgeDerivation derivation, boolean reverse) {
    return visitor -> {
      try (PreparedStatement statement =
          connection.prepareStatement(reverse ? EDGES_REVERSE : EDGES_FORWARD)) {
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

  private void registerPair(
      String kind,
      int nodeCount,
      Path forwardFile,
      CsrFile.WriteResult forward,
      Path reverseFile,
      CsrFile.WriteResult reverse)
      throws SQLException, IOException {
    Long sourceId = sourceIdFor(kind);
    if (sourceId == null) {
      throw new IOException(
          "graph index publication requires a successful registered source for " + kind);
    }
    try (PreparedStatement statement = connection.prepareStatement(REGISTER)) {
      long builtMicros = clock.getAsLong();
      for (RegisteredWrite write :
          new RegisteredWrite[] {
            new RegisteredWrite("FORWARD", forwardFile, forward),
            new RegisteredWrite("REVERSE", reverseFile, reverse)
          }) {
        statement.setString(1, kind);
        statement.setString(2, write.direction());
        statement.setString(3, write.file().getFileName().toString());
        statement.setInt(4, CsrFile.FORMAT_VERSION);
        statement.setLong(5, nodeCount);
        statement.setLong(6, write.result().edgeCount());
        statement.setString(7, Long.toHexString(write.result().checksum()));
        statement.setLong(8, builtMicros);
        statement.setLong(9, sourceId);
        statement.addBatch();
      }
      statement.executeBatch();
    }
  }

  private Long sourceIdFor(String indexKind) throws SQLException {
    String sourceKind = sourceKindFor(indexKind);
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT id FROM graph_sources WHERE kind = ? AND state = 'SUCCEEDED'")) {
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

  /** Header-only descriptor for a registered action index. */
  public Optional<CsrFile.Descriptor> descriptor(EdgeDerivation derivation, String direction)
      throws SQLException, IOException {
    return descriptor(derivation.name(), direction);
  }

  /** Header-only descriptor for a registered configured-target label index. */
  public Optional<CsrFile.Descriptor> configuredTargetsDescriptor(String direction)
      throws SQLException, IOException {
    return descriptor(CONFIGURED_TARGETS_KIND, direction);
  }

  private Optional<CsrFile.Descriptor> descriptor(String kind, String direction)
      throws SQLException, IOException {
    if (!direction.equals("FORWARD") && !direction.equals("REVERSE")) {
      throw new IllegalArgumentException("unknown graph index direction " + direction);
    }
    RegisteredDescriptors registered = registeredDescriptors(kind);
    RegisteredDirection wanted =
        direction.equals("FORWARD") ? registered.forward() : registered.reverse();
    if (wanted == null) {
      return Optional.empty();
    }
    if (wanted.generation().isEmpty()) {
      // Legacy stable-name indexes cannot support pair-dependent answers, but one direction is
      // still an independently checksummed registered artifact.
      return Optional.of(wanted.descriptor());
    }
    // A generation-named direction is usable only when the same registry snapshot proves its
    // counterpart belongs to the same atomic publication.
    if (registered.forward() == null || registered.reverse() == null) {
      return Optional.empty();
    }
    return Optional.of(wanted.descriptor());
  }

  /** Reads both registry rows in one SQLite statement snapshot. */
  private RegisteredDescriptors registeredDescriptors(String kind)
      throws SQLException, IOException {
    RegisteredDirection forward = null;
    RegisteredDirection reverse = null;
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT gi.direction, gi.file_name, gi.format_version, gi.node_count,"
                + " gi.edge_count, gi.checksum, gi.source_id, gi.built_micros,"
                + " gs.kind AS source_kind, gs.state AS source_state"
                + " FROM graph_indexes gi"
                + " LEFT JOIN graph_sources gs ON gs.id = gi.source_id"
                + " WHERE gi.kind = ? AND gi.direction IN ('FORWARD', 'REVERSE')"
                + " ORDER BY gi.direction")) {
      statement.setString(1, kind);
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          String rowDirection = rows.getString("direction");
          Optional<CsrFile.Descriptor> descriptor = registeredDescriptor(rows, kind, rowDirection);
          if (descriptor.isEmpty()) {
            return new RegisteredDescriptors(null, null);
          }
          long rawSource = rows.getLong("source_id");
          Long source = rows.wasNull() ? null : rawSource;
          long rawBuilt = rows.getLong("built_micros");
          Long built = rows.wasNull() ? null : rawBuilt;
          RegisteredDirection found =
              new RegisteredDirection(
                  descriptor.get(),
                  generationOf(kind, rowDirection, rows.getString("file_name")),
                  source,
                  built);
          if ("FORWARD".equals(rowDirection)) {
            if (forward != null) {
              throw new IOException("duplicate forward graph index registry row");
            }
            forward = found;
          } else if ("REVERSE".equals(rowDirection)) {
            if (reverse != null) {
              throw new IOException("duplicate reverse graph index registry row");
            }
            reverse = found;
          } else {
            throw new IOException("registered graph index has unknown direction " + rowDirection);
          }
        }
      }
    }
    RegisteredDescriptors registered = new RegisteredDescriptors(forward, reverse);
    validatePublishedPair(registered);
    return registered;
  }

  /** Reads and validates both registry rows in one SQLite statement snapshot. */
  Optional<DescriptorPair> descriptorPair(String kind) throws SQLException, IOException {
    RegisteredDescriptors registered = registeredDescriptors(kind);
    if (registered.forward() == null || registered.reverse() == null) {
      return Optional.empty();
    }
    // Pre-v10 files used stable names and separately written registry rows, so there is no durable
    // evidence that a forward/reverse pair belongs to one build. Single-direction features remain
    // readable, but pair-dependent answers are unavailable rather than inferred or repaired.
    if (registered.forward().generation().isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(
        new DescriptorPair(registered.forward().descriptor(), registered.reverse().descriptor()));
  }

  private static void validatePublishedPair(RegisteredDescriptors registered) throws IOException {
    RegisteredDirection forward = registered.forward();
    RegisteredDirection reverse = registered.reverse();
    if (forward == null || reverse == null) {
      return;
    }
    if (forward.generation().isEmpty() != reverse.generation().isEmpty()) {
      throw new IOException(
          "forward and reverse graph registry rows are from different generations");
    }
    if (forward.generation().isPresent()
        && (!Objects.equals(forward.generation(), reverse.generation())
            || !Objects.equals(forward.sourceId(), reverse.sourceId())
            || !Objects.equals(forward.builtMicros(), reverse.builtMicros()))) {
      throw new IOException(
          "forward and reverse graph registry rows are from different generations");
    }
  }

  private static Optional<String> generationOf(String kind, String direction, String fileName)
      throws IOException {
    String prefix = kind.toLowerCase(Locale.ROOT) + "-" + direction.toLowerCase(Locale.ROOT) + "-";
    String legacy =
        kind.toLowerCase(Locale.ROOT) + "-" + direction.toLowerCase(Locale.ROOT) + ".csr";
    if (fileName.equals(legacy)) {
      return Optional.empty();
    }
    if (!fileName.startsWith(prefix)
        || !fileName.endsWith(".csr")
        || fileName.length() <= prefix.length() + ".csr".length()) {
      throw new IOException("registered graph index does not carry a valid generation name");
    }
    return Optional.of(fileName.substring(prefix.length(), fileName.length() - ".csr".length()));
  }

  private Optional<CsrFile.Descriptor> registeredDescriptor(
      ResultSet rows, String kind, String direction) throws SQLException, IOException {
    rows.getLong("source_id");
    if (rows.wasNull()
        || !sourceKindFor(kind).equals(rows.getString("source_kind"))
        || !"SUCCEEDED".equals(rows.getString("source_state"))) {
      return Optional.empty();
    }
    Path file = containedFile(rows.getString("file_name"));
    if (file == null) {
      return Optional.empty();
    }
    CsrFile.Descriptor descriptor = CsrFile.describe(file);
    CsrFile.Header header = descriptor.header();
    if (header.formatVersion() != rows.getInt("format_version")
        || header.nodeCount() != rows.getLong("node_count")
        || header.edgeCount() != rows.getLong("edge_count")
        || !Long.toHexString(header.checksum()).equals(rows.getString("checksum"))
        || header.reverseDirection() != "REVERSE".equals(direction)) {
      throw new StaleIndexException(file);
    }
    return Optional.of(descriptor);
  }

  private Path containedFile(String registeredName) throws IOException {
    Path name;
    try {
      name = Path.of(registeredName);
    } catch (RuntimeException malformed) {
      throw new IOException("registered graph index has an invalid file name", malformed);
    }
    if (name.isAbsolute()
        || name.getNameCount() != 1
        || registeredName.equals(".")
        || registeredName.equals("..")) {
      throw new IOException(
          "registered graph index file must be one direct child name, not " + registeredName);
    }
    Path normalizedDirectory = requireIndexDirectory(false);
    Path candidate = normalizedDirectory.resolve(name).normalize();
    if (!candidate.getParent().equals(normalizedDirectory) || Files.isSymbolicLink(candidate)) {
      throw new IOException(
          "registered graph index escapes its index directory: " + registeredName);
    }
    if (!Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
      return null;
    }
    return candidate;
  }

  private Path generationFile(String kind, String direction, String generation) throws IOException {
    Path file =
        requireIndexDirectory(false)
            .resolve(
                kind.toLowerCase(Locale.ROOT)
                    + "-"
                    + direction.toLowerCase(Locale.ROOT)
                    + "-"
                    + generation
                    + ".csr");
    if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException("new graph index generation already exists: " + file.getFileName());
    }
    return file;
  }

  private Path requireIndexDirectory(boolean create) throws IOException {
    Path normalized = directory.toAbsolutePath().normalize();
    if (create && !Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
      Files.createDirectories(normalized);
    }
    if (Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)
        && (Files.isSymbolicLink(normalized)
            || !Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS))) {
      throw new IOException("graph index directory must be a real directory, not a symlink");
    }
    return normalized;
  }

  private int validateDenseActionNodeIndices() throws SQLException, IOException {
    long expected = 0;
    try (PreparedStatement statement = connection.prepareStatement(ORDERED_NODE_INDICES);
        ResultSet rows = statement.executeQuery()) {
      while (rows.next()) {
        long actual = rows.getLong(1);
        if (actual != expected) {
          throw new IOException(
              "declared action node indices must be unique and dense 0..count-1; expected "
                  + expected
                  + " and read "
                  + actual);
        }
        expected++;
        if (expected > Integer.MAX_VALUE - 1L) {
          throw new IOException("declared action node count exceeds the dense int index format");
        }
      }
    }
    long counted = scalar(NODE_COUNT);
    if (counted != expected) {
      throw new IOException(
          "declared action node population changed while it was validated: "
              + expected
              + " ordered rows against "
              + counted);
    }
    return (int) expected;
  }

  private long scalar(String sql) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql);
        ResultSet rows = statement.executeQuery()) {
      return rows.next() ? rows.getLong(1) : 0;
    }
  }

  private <T> T inStableTransaction(TransactionWork<T> work) throws SQLException, IOException {
    boolean previousAutoCommit = connection.getAutoCommit();
    Savepoint savepoint = null;
    Throwable failure = null;
    try {
      if (previousAutoCommit) {
        connection.setAutoCommit(false);
      } else {
        savepoint = connection.setSavepoint();
      }
      T result = work.run();
      if (previousAutoCommit) {
        connection.commit();
      } else {
        connection.releaseSavepoint(savepoint);
      }
      return result;
    } catch (SQLException | IOException | RuntimeException caught) {
      failure = caught;
      try {
        if (previousAutoCommit) {
          connection.rollback();
        } else if (savepoint != null) {
          connection.rollback(savepoint);
        }
      } catch (SQLException rollbackFailure) {
        caught.addSuppressed(rollbackFailure);
      }
      throw caught;
    } finally {
      if (previousAutoCommit) {
        try {
          connection.setAutoCommit(true);
        } catch (SQLException restoreFailure) {
          if (failure != null) {
            failure.addSuppressed(restoreFailure);
          } else {
            throw restoreFailure;
          }
        }
      }
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

  /** Matching descriptors read from one registry snapshot. */
  record DescriptorPair(CsrFile.Descriptor forward, CsrFile.Descriptor reverse) {}

  private record RegisteredDescriptors(RegisteredDirection forward, RegisteredDirection reverse) {}

  private record RegisteredDirection(
      CsrFile.Descriptor descriptor,
      Optional<String> generation,
      Long sourceId,
      Long builtMicros) {}

  private record RegisteredWrite(String direction, Path file, CsrFile.WriteResult result) {}

  @FunctionalInterface
  private interface TransactionWork<T> {
    T run() throws SQLException, IOException;
  }

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
