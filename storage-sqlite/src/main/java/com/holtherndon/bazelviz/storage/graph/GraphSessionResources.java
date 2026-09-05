package com.holtherndon.bazelviz.storage.graph;

import com.holtherndon.bazelviz.graph.CsrFile;
import com.holtherndon.bazelviz.graph.GraphIndexCache;
import com.holtherndon.bazelviz.graph.GraphResourceBudget;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Shared graph budget, mapped-index cache and generation-bound integrity checks for one session.
 */
public final class GraphSessionResources implements AutoCloseable {

  private static final String ACTION_NODE_INDICES =
      "SELECT node_index FROM declared_actions"
          + " WHERE node_index IS NOT NULL ORDER BY node_index";

  private static final String LABEL_NODE_COUNT =
      "SELECT count(*) FROM (SELECT DISTINCT label_id FROM configured_target_nodes)";

  private final GraphResourceBudget budget;
  private final GraphIndexCache cache;
  private final Set<ValidationKey> validatedPopulations = new HashSet<>();
  private final Map<String, RetainedResult> retainedResults = new LinkedHashMap<>();
  private boolean closed;

  public GraphSessionResources() {
    this(new GraphResourceBudget());
  }

  public GraphSessionResources(GraphResourceBudget budget) {
    this.budget = budget;
    this.cache = new GraphIndexCache(budget);
  }

  public GraphResourceBudget budget() {
    return budget;
  }

  GraphIndexCache cache() {
    return cache;
  }

  synchronized <T> Optional<T> retainedResult(String slot, String generationKey, Class<T> type)
      throws SessionChangedException {
    RetainedResult retained = retainedResults.get(slot);
    if (retained == null) {
      return Optional.empty();
    }
    if (!retained.generationKey().equals(generationKey)) {
      throw new SessionChangedException(slot);
    }
    return Optional.of(type.cast(retained.value()));
  }

  synchronized <T> T retainResult(
      String slot,
      String generationKey,
      Class<T> type,
      T value,
      GraphResourceBudget.Reservation reservation)
      throws SessionChangedException {
    if (closed) {
      reservation.close();
      throw new IllegalStateException("graph session resources are closed");
    }
    RetainedResult existing = retainedResults.get(slot);
    if (existing != null && existing.generationKey().equals(generationKey)) {
      reservation.close();
      return type.cast(existing.value());
    }
    if (existing != null) {
      // Session data is immutable while open. Replacing this result would release the charge for
      // arrays an already-delivered UI result can still hold. Refuse the new generation instead;
      // reopening the session creates a fresh resource owner.
      reservation.close();
      throw new SessionChangedException(slot);
    }
    retainedResults.put(slot, new RetainedResult(generationKey, value, reservation));
    return value;
  }

  /** Validates imported action node ids before any node-indexed allocation or body mapping. */
  synchronized int validateActionNodes(Connection connection, long expectedNodes)
      throws SQLException, IOException {
    long expected = 0;
    try (PreparedStatement statement = connection.prepareStatement(ACTION_NODE_INDICES);
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
    requireExpected("action", expected, expectedNodes);
    return (int) expected;
  }

  /** Validates one immutable action-index generation once per open session. */
  synchronized int validateActionNodes(Connection connection, CsrFile.Descriptor descriptor)
      throws SQLException, IOException {
    ValidationKey key = ValidationKey.of("action", descriptor);
    if (validatedPopulations.contains(key)) {
      return Math.toIntExact(descriptor.header().nodeCount());
    }
    int count = validateActionNodes(connection, descriptor.header().nodeCount());
    validatedPopulations.add(key);
    return count;
  }

  /** Checks the SQL-derived dense label universe against an admitted label-index descriptor. */
  synchronized int validateLabelNodes(Connection connection, long expectedNodes)
      throws SQLException, IOException {
    if (expectedNodes > Integer.MAX_VALUE - 1L) {
      throw new IOException("configured-target label count exceeds the dense int index format");
    }
    return (int) expectedNodes;
  }

  /** Checks one immutable label-index generation once per open session. */
  synchronized int validateLabelNodes(Connection connection, CsrFile.Descriptor descriptor)
      throws SQLException, IOException {
    ValidationKey key = ValidationKey.of("configured-target label", descriptor);
    if (validatedPopulations.contains(key)) {
      return Math.toIntExact(descriptor.header().nodeCount());
    }
    long count;
    try (PreparedStatement statement = connection.prepareStatement(LABEL_NODE_COUNT);
        ResultSet rows = statement.executeQuery()) {
      count = rows.next() ? rows.getLong(1) : 0;
    }
    requireExpected("configured-target label", count, descriptor.header().nodeCount());
    int admitted = validateLabelNodes(connection, count);
    validatedPopulations.add(key);
    return admitted;
  }

  private static void requireExpected(String kind, long actual, long expected) throws IOException {
    if (actual != expected) {
      throw new IOException(
          kind
              + " node population does not match its graph index: "
              + actual
              + " rows against "
              + expected
              + " registered nodes");
    }
  }

  @Override
  public synchronized void close() {
    if (closed) {
      return;
    }
    closed = true;
    cache.close();
    for (RetainedResult retained : retainedResults.values()) {
      retained.reservation().close();
    }
    retainedResults.clear();
  }

  private record ValidationKey(
      String kind, Path path, long nodeCount, long edgeCount, long checksum, boolean reverse) {
    static ValidationKey of(String kind, CsrFile.Descriptor descriptor) {
      CsrFile.Header header = descriptor.header();
      return new ValidationKey(
          kind,
          descriptor.path(),
          header.nodeCount(),
          header.edgeCount(),
          header.checksum(),
          header.reverseDirection());
    }
  }

  private record RetainedResult(
      String generationKey, Object value, GraphResourceBudget.Reservation reservation) {}

  /** An immutable open session unexpectedly exposed another generation for one result slot. */
  public static final class SessionChangedException extends IOException {
    private static final long serialVersionUID = 1L;

    SessionChangedException(String slot) {
      super("graph session generation changed while open for " + slot + "; reopen the session");
    }
  }
}
