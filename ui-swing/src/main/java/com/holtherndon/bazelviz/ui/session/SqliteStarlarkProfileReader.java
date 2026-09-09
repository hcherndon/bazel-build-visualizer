package com.holtherndon.bazelviz.ui.session;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.concurrent.Callable;

/** SQLite-backed, single-worker reader for one imported Starlark CPU profile. */
class SqliteStarlarkProfileReader implements StarlarkProfileReader {

  private static final String FUNCTION_FILTER =
      " WHERE (h.function_name LIKE ? ESCAPE '\\'" + " OR h.filename LIKE ? ESCAPE '\\')";
  private static final String FILE_FILTER = " WHERE h.filename <> ''";
  private static final String FILE_SEARCH_FILTER = " AND h.filename LIKE ? ESCAPE '\\'";

  private final String describedSession;
  private final Connection connection;
  private final Correlation correlation;
  private volatile Statement running;
  private volatile boolean closed;

  SqliteStarlarkProfileReader(String describedSession, Connection connection) {
    this(describedSession, connection, Correlation.CAPTURED_WITH_INVOCATION);
  }

  SqliteStarlarkProfileReader(
      String describedSession, Connection connection, Correlation correlation) {
    this.describedSession = Objects.requireNonNull(describedSession, "describedSession");
    this.connection = Objects.requireNonNull(connection, "connection");
    this.correlation = Objects.requireNonNull(correlation, "correlation");
  }

  @Override
  public Summary summary() {
    return call(
        "reading the Starlark profile summary",
        () -> {
          String sql =
              "SELECT m.total_value, m.duration_nanos, m.sample_count,"
                  + " m.normalized_period_micros,"
                  + " m.function_count, m.validation_state, m.validation_detail,"
                  + " t.state, t.exit_status, t.error_excerpt,"
                  + " (SELECT COUNT(*) FROM starlark_file_metrics)"
                  + ",m.function_attributed_value,m.function_attributed_samples"
                  + ",m.file_attributed_value,m.file_attributed_samples"
                  + ",m.context_attributed_value,m.context_attributed_samples"
                  + " FROM starlark_profile_metadata m"
                  + " JOIN enrichment_tasks t ON t.id = m.task_id WHERE m.id = 1";
          try (PreparedStatement statement = prepare(sql);
              ResultSet rows = execute(statement)) {
            if (rows.next()) {
              String validation = rows.getString(6);
              String taskState = rows.getString(8);
              String taskFailure =
                  firstText(
                      rows.getString(10),
                      skipDetail(rows.getString(9)),
                      rows.getString(7),
                      validation,
                      "The Starlark CPU profile has no queryable data.");
              if (!"SUCCEEDED".equals(taskState)) {
                return switch (taskState) {
                  case "FAILED", "PARTIAL" -> unavailable(Availability.IMPORT_FAILED, taskFailure);
                  case "SKIPPED" ->
                      unavailable(skippedAvailability(rows.getString(9)), taskFailure);
                  default -> unavailable(Availability.UNKNOWN, taskFailure);
                };
              }
              String detail = firstText(rows.getString(7), validation, "Validated gzip pprof data");
              return new Summary(
                  Availability.AVAILABLE,
                  detail,
                  correlation,
                  optionalLong(rows, 1),
                  nanosToMicros(optionalLong(rows, 2)),
                  optionalLong(rows, 3),
                  optionalLong(rows, 4),
                  optionalLong(rows, 5),
                  optionalLong(rows, 11),
                  coverage(rows, 1, 3, 12, 13, "function"),
                  coverage(rows, 1, 3, 14, 15, "file"),
                  coverage(rows, 1, 3, 16, 17, "context"));
            }
          }
          return missingSummary();
        });
  }

  private Summary missingSummary() throws SQLException {
    String sql =
        "SELECT state, exit_status, error_excerpt FROM enrichment_tasks"
            + " WHERE kind = 'STARLARK_CPU_PROFILE'";
    try (PreparedStatement statement = prepare(sql);
        ResultSet rows = execute(statement)) {
      if (!rows.next()) {
        return unavailable(
            Availability.NOT_CAPTURED,
            "Capture with the Performance or Full preset to collect sampled"
                + " Starlark CPU stacks.");
      }
      String state = rows.getString(1);
      String detail =
          firstText(
              rows.getString(3),
              skipDetail(rows.getString(2)),
              "The Starlark CPU profile has no queryable data.");
      return switch (state) {
        case "FAILED", "PARTIAL" -> unavailable(Availability.IMPORT_FAILED, detail);
        case "SKIPPED" -> unavailable(skippedAvailability(rows.getString(2)), detail);
        case "SUCCEEDED" ->
            unavailable(
                Availability.IMPORT_FAILED,
                "The import succeeded without profile metadata; re-import the session.");
        default -> unavailable(Availability.UNKNOWN, detail);
      };
    }
  }

  private static Availability skippedAvailability(String exitStatus) {
    if (exitStatus != null && exitStatus.startsWith("UNSUPPORTED: ")) {
      return Availability.UNSUPPORTED;
    }
    if (exitStatus != null && exitStatus.startsWith("UNKNOWN: ")) {
      return Availability.UNKNOWN;
    }
    return Availability.SKIPPED;
  }

  private static String skipDetail(String exitStatus) {
    if (exitStatus == null) {
      return null;
    }
    for (String prefix : List.of("SKIPPED: ", "UNSUPPORTED: ", "UNKNOWN: ")) {
      if (exitStatus.startsWith(prefix)) {
        return exitStatus.substring(prefix.length());
      }
    }
    return exitStatus;
  }

  private static Summary unavailable(Availability availability, String detail) {
    return new Summary(
        availability,
        detail,
        Correlation.UNKNOWN,
        OptionalLong.empty(),
        OptionalLong.empty(),
        OptionalLong.empty(),
        OptionalLong.empty(),
        OptionalLong.empty(),
        OptionalLong.empty(),
        AttributionCoverage.unavailable(),
        AttributionCoverage.unavailable(),
        AttributionCoverage.unavailable());
  }

  @Override
  public long hotFunctionCount(FunctionQuery query) {
    Objects.requireNonNull(query, "query");
    return call(
        "counting Starlark functions",
        () ->
            query.search().isEmpty()
                ? count("SELECT COUNT(*) FROM starlark_function_metrics")
                : count(
                    "SELECT COUNT(*) FROM starlark_hot_functions h" + FUNCTION_FILTER,
                    contains(query.search()),
                    contains(query.search())));
  }

  @Override
  public List<HotFunction> hotFunctions(FunctionQuery query, long offset, int limit) {
    Objects.requireNonNull(query, "query");
    requirePage(offset, limit);
    return call(
        "reading Starlark functions at offset " + offset,
        () -> {
          String sql = hotFunctionsSql(query);
          try (PreparedStatement statement = prepare(sql)) {
            int parameter = 1;
            if (!query.search().isEmpty()) {
              String search = contains(query.search());
              statement.setString(parameter++, search);
              statement.setString(parameter++, search);
            }
            statement.setInt(parameter++, limit);
            statement.setLong(parameter, offset);
            try (ResultSet rows = execute(statement)) {
              List<HotFunction> result = new ArrayList<>();
              while (rows.next()) {
                result.add(
                    new HotFunction(
                        rows.getLong(1),
                        displayName(rows.getString(2), rows.getLong(1)),
                        source(rows.getString(3), optionalLong(rows, 4)),
                        optionalLong(rows, 5),
                        optionalLong(rows, 6),
                        optionalLong(rows, 7),
                        optionalLong(rows, 8),
                        optionalLong(rows, 9)));
              }
              return List.copyOf(result);
            }
          }
        });
  }

  @Override
  public long sourceFileCount(FileQuery query) {
    Objects.requireNonNull(query, "query");
    return call(
        "counting Starlark source files",
        () ->
            query.search().isEmpty()
                ? count("SELECT COUNT(*) FROM starlark_hot_files h" + FILE_FILTER)
                : count(
                    "SELECT COUNT(*) FROM starlark_hot_files h" + FILE_FILTER + FILE_SEARCH_FILTER,
                    contains(query.search())));
  }

  @Override
  public List<SourceFile> sourceFiles(FileQuery query, long offset, int limit) {
    Objects.requireNonNull(query, "query");
    requirePage(offset, limit);
    return call(
        "reading Starlark source files at offset " + offset,
        () -> {
          String sql = sourceFilesSql(query);
          try (PreparedStatement statement = prepare(sql)) {
            int parameter = 1;
            if (!query.search().isEmpty()) {
              statement.setString(parameter++, contains(query.search()));
            }
            statement.setInt(parameter++, limit);
            statement.setLong(parameter, offset);
            try (ResultSet rows = execute(statement)) {
              List<SourceFile> result = new ArrayList<>();
              while (rows.next()) {
                result.add(
                    new SourceFile(
                        rows.getString(1),
                        optionalLong(rows, 2),
                        optionalLong(rows, 3),
                        optionalLong(rows, 4),
                        optionalLong(rows, 5)));
              }
              return List.copyOf(result);
            }
          }
        });
  }

  @Override
  public long callEdgeCount(long functionId, CallDirection direction) {
    requireId(functionId);
    Objects.requireNonNull(direction, "direction");
    return call(
        "counting Starlark " + direction.name().toLowerCase(),
        () -> {
          try (PreparedStatement statement =
              prepare("SELECT COUNT(*) FROM (" + edgeSelect(direction) + ")")) {
            statement.setLong(1, functionId);
            try (ResultSet rows = execute(statement)) {
              return rows.next() ? rows.getLong(1) : 0L;
            }
          }
        });
  }

  @Override
  public List<CallEdge> callEdges(
      long functionId, CallDirection direction, long offset, int limit) {
    requireId(functionId);
    Objects.requireNonNull(direction, "direction");
    requirePage(offset, limit);
    return call(
        "reading Starlark " + direction.name().toLowerCase() + " at offset " + offset,
        () -> {
          String sql =
              edgeSelect(direction)
                  + " ORDER BY cpu_value DESC, related_name COLLATE NOCASE, related_id"
                  + " LIMIT ? OFFSET ?";
          try (PreparedStatement statement = prepare(sql)) {
            statement.setLong(1, functionId);
            statement.setInt(2, limit);
            statement.setLong(3, offset);
            try (ResultSet rows = execute(statement)) {
              List<CallEdge> result = new ArrayList<>();
              while (rows.next()) {
                long id = rows.getLong("related_id");
                result.add(
                    new CallEdge(
                        id,
                        displayName(rows.getString("related_name"), id),
                        source(
                            rows.getString("related_file"),
                            optionalLong(rows, "related_start_line")),
                        optionalLong(rows, "cpu_value"),
                        optionalLong(rows, "sample_records")));
              }
              return List.copyOf(result);
            }
          }
        });
  }

  private static String edgeSelect(CallDirection direction) {
    String selected = direction == CallDirection.CALLERS ? "callee" : "caller";
    String related = direction == CallDirection.CALLERS ? "caller" : "callee";
    String selectedLocation = selected + "_location_id";
    String relatedLocation = related + "_location_id";
    return "SELECT related_fn.function_id AS related_id,"
        + " related_name.value AS related_name, related_file.value AS related_file,"
        + " related_fn.start_line AS related_start_line,"
        + " SUM(edge.value) AS cpu_value, SUM(edge.sample_count) AS sample_records"
        + " FROM starlark_call_edges edge"
        + " JOIN starlark_profile_location_lines selected_line"
        + " ON selected_line.location_id = edge."
        + selectedLocation
        + " AND selected_line.ordinal = 0"
        + " JOIN starlark_profile_location_lines related_line"
        + " ON related_line.location_id = edge."
        + relatedLocation
        + " AND related_line.ordinal = 0"
        + " JOIN starlark_profile_functions related_fn"
        + " ON related_fn.function_id = related_line.function_id"
        + " LEFT JOIN starlark_profile_strings related_name"
        + " ON related_name.string_index = related_fn.name_string_index"
        + " LEFT JOIN starlark_profile_strings related_file"
        + " ON related_file.string_index = related_fn.filename_string_index"
        + " WHERE selected_line.function_id = ?"
        + " GROUP BY related_fn.function_id, related_name.value, related_file.value,"
        + " related_fn.start_line";
  }

  /** Production page SQL exposed package-locally so its SQLite plan can be regression-tested. */
  static String hotFunctionsSql(FunctionQuery query) {
    String order =
        switch (query.sort()) {
          case SELF_CPU -> "h.self_value";
          case CUMULATIVE_CPU -> "h.cumulative_value";
          case NAME -> "h.function_name COLLATE NOCASE";
          case FILE -> "h.filename COLLATE NOCASE";
        };
    String filter = query.search().isEmpty() ? "" : FUNCTION_FILTER;
    return "SELECT h.function_id, h.function_name, h.filename, h.start_line,"
        + " h.self_value, h.cumulative_value, h.self_samples,"
        + " h.cumulative_samples, h.context_count"
        + " FROM starlark_hot_functions h"
        + filter
        + " ORDER BY "
        + order
        + (query.descending() ? " DESC" : " ASC")
        + ", h.function_id ASC LIMIT ? OFFSET ?";
  }

  /** Production page SQL exposed package-locally so its SQLite plan can be regression-tested. */
  static String sourceFilesSql(FileQuery query) {
    String order =
        switch (query.sort()) {
          case SELF_CPU -> "h.self_value";
          case CUMULATIVE_CPU -> "h.cumulative_value";
          case PATH -> "h.filename COLLATE NOCASE";
        };
    String filter = FILE_FILTER + (query.search().isEmpty() ? "" : FILE_SEARCH_FILTER);
    return "SELECT h.filename, h.self_value, h.cumulative_value,"
        + " h.function_count, h.cumulative_samples FROM starlark_hot_files h"
        + filter
        + " ORDER BY "
        + order
        + (query.descending() ? " DESC" : " ASC")
        + ", h.filename_string_index ASC LIMIT ? OFFSET ?";
  }

  @Override
  public DirectedCallGraph directedCallGraph(
      OptionalLong pinnedFunctionId, int maxNodes, int maxEdges) {
    Objects.requireNonNull(pinnedFunctionId, "pinnedFunctionId");
    if (pinnedFunctionId.isPresent()) {
      requireId(pinnedFunctionId.getAsLong());
    }
    if (maxNodes <= 0 || maxEdges <= 0) {
      throw new IllegalArgumentException("call-graph limits must be positive");
    }
    return call(
        "reading the directed Starlark call graph",
        () -> {
          long totalFunctions;
          OptionalLong totalCpu;
          try (PreparedStatement statement =
                  prepare(
                      "SELECT (SELECT COUNT(*) FROM starlark_function_metrics), total_value"
                          + " FROM starlark_profile_metadata WHERE id = 1");
              ResultSet rows = execute(statement)) {
            if (!rows.next()) {
              return new DirectedCallGraph(0, 0, 0, 0, OptionalLong.empty(), List.of(), List.of());
            }
            totalFunctions = rows.getLong(1);
            totalCpu = optionalLong(rows, 2);
          }

          List<CallGraphNode> nodes = readCallGraphNodes(pinnedFunctionId, maxNodes);
          if (nodes.isEmpty()) {
            return new DirectedCallGraph(
                totalFunctions, totalFunctions, 0, 0, totalCpu, List.of(), List.of());
          }
          long[] functionIds = nodes.stream().mapToLong(CallGraphNode::functionId).toArray();
          long visibleEdges = countDirectedCallEdges(functionIds);
          List<DirectedCallEdge> edges = readDirectedCallEdges(functionIds, maxEdges);
          return new DirectedCallGraph(
              totalFunctions,
              Math.max(0, totalFunctions - nodes.size()),
              visibleEdges,
              Math.max(0, visibleEdges - edges.size()),
              totalCpu,
              nodes,
              edges);
        });
  }

  private List<CallGraphNode> readCallGraphNodes(OptionalLong pinnedFunctionId, int maxNodes)
      throws SQLException {
    String sql =
        "SELECT h.function_id, h.function_name, h.filename, h.start_line,"
            + " h.self_value, h.cumulative_value, h.self_samples,"
            + " h.cumulative_samples, h.context_count"
            + " FROM starlark_hot_functions h"
            + " ORDER BY CASE WHEN h.function_id = ? THEN 0 ELSE 1 END,"
            + " h.cumulative_value DESC, h.self_value DESC, h.function_id"
            + " LIMIT ?";
    try (PreparedStatement statement = prepare(sql)) {
      statement.setLong(1, pinnedFunctionId.orElse(-1));
      statement.setInt(2, maxNodes);
      try (ResultSet rows = execute(statement)) {
        List<CallGraphNode> result = new ArrayList<>();
        while (rows.next()) {
          long id = rows.getLong(1);
          result.add(
              new CallGraphNode(
                  id,
                  displayName(rows.getString(2), id),
                  source(rows.getString(3), optionalLong(rows, 4)),
                  optionalLong(rows, 5),
                  optionalLong(rows, 6),
                  optionalLong(rows, 7),
                  optionalLong(rows, 8),
                  optionalLong(rows, 9)));
        }
        return List.copyOf(result);
      }
    }
  }

  private long countDirectedCallEdges(long[] functionIds) throws SQLException {
    String sql =
        directedCallEdgesCte(functionIds.length)
            + "SELECT COUNT(*) FROM ("
            + directedCallEdgesSelect()
            + ")";
    try (PreparedStatement statement = prepare(sql)) {
      bindFunctionIds(statement, functionIds);
      try (ResultSet rows = execute(statement)) {
        return rows.next() ? rows.getLong(1) : 0L;
      }
    }
  }

  private List<DirectedCallEdge> readDirectedCallEdges(long[] functionIds, int maxEdges)
      throws SQLException {
    String sql =
        directedCallEdgesCte(functionIds.length)
            + directedCallEdgesSelect()
            + " ORDER BY cpu_value DESC, caller_id, callee_id LIMIT ?";
    try (PreparedStatement statement = prepare(sql)) {
      int parameter = bindFunctionIds(statement, functionIds);
      statement.setInt(parameter, maxEdges);
      try (ResultSet rows = execute(statement)) {
        List<DirectedCallEdge> result = new ArrayList<>();
        while (rows.next()) {
          result.add(
              new DirectedCallEdge(
                  rows.getLong("caller_id"),
                  rows.getLong("callee_id"),
                  optionalLong(rows, "cpu_value"),
                  optionalLong(rows, "sample_records")));
        }
        return List.copyOf(result);
      }
    }
  }

  private static String directedCallEdgesCte(int functionCount) {
    StringBuilder values = new StringBuilder("WITH visible(function_id) AS (VALUES ");
    for (int index = 0; index < functionCount; index++) {
      if (index > 0) {
        values.append(',');
      }
      values.append("(?)");
    }
    return values.append(") ").toString();
  }

  private static String directedCallEdgesSelect() {
    return "SELECT caller_line.function_id AS caller_id,"
        + " callee_line.function_id AS callee_id,"
        + " SUM(edge.value) AS cpu_value,"
        + " SUM(edge.sample_count) AS sample_records"
        + " FROM visible caller_visible"
        + " JOIN starlark_profile_location_lines caller_line"
        + " ON caller_line.function_id = caller_visible.function_id"
        + " AND caller_line.ordinal = 0"
        + " JOIN starlark_call_edges edge"
        + " ON edge.caller_location_id = caller_line.location_id"
        + " JOIN starlark_profile_location_lines callee_line"
        + " ON callee_line.location_id = edge.callee_location_id"
        + " AND callee_line.ordinal = 0"
        + " JOIN visible callee_visible"
        + " ON callee_visible.function_id = callee_line.function_id"
        + " GROUP BY caller_line.function_id, callee_line.function_id";
  }

  private static int bindFunctionIds(PreparedStatement statement, long[] functionIds)
      throws SQLException {
    for (int index = 0; index < functionIds.length; index++) {
      statement.setLong(index + 1, functionIds[index]);
    }
    return functionIds.length + 1;
  }

  @Override
  public FlameSlice flameRows(OptionalLong focusNodeId, int maxNodes) {
    Objects.requireNonNull(focusNodeId, "focusNodeId");
    if (focusNodeId.isPresent()) {
      requireId(focusNodeId.getAsLong());
    }
    if (maxNodes <= 0) {
      throw new IllegalArgumentException("maxNodes must be positive");
    }
    return call(
        "reading the Starlark flame hierarchy",
        () ->
            focusNodeId.isPresent()
                ? focusedFlameRows(focusNodeId.getAsLong(), maxNodes)
                : rootFlameRows(maxNodes));
  }

  private FlameSlice rootFlameRows(int maxNodes) throws SQLException {
    long total;
    OptionalLong totalCpu;
    try (PreparedStatement statement =
            prepare(
                "SELECT (SELECT COUNT(*) FROM starlark_call_nodes WHERE depth > 0),"
                    + " inclusive_value FROM starlark_call_nodes"
                    + " WHERE depth = 0 ORDER BY node_id LIMIT 1");
        ResultSet rows = execute(statement)) {
      if (!rows.next()) {
        return new FlameSlice(OptionalLong.empty(), 0, 0, OptionalLong.empty(), List.of());
      }
      total = rows.getLong(1);
      totalCpu = optionalLong(rows, 2);
    }
    String sql = rootFlameRowsSql();
    List<FlameNode> nodes = readFlameRows(sql, statement -> statement.setInt(1, maxNodes));
    return new FlameSlice(
        OptionalLong.empty(), total, Math.max(0, total - nodes.size()), totalCpu, nodes);
  }

  private FlameSlice focusedFlameRows(long focusNodeId, int maxNodes) throws SQLException {
    String cte =
        "WITH RECURSIVE subtree(node_id, parent_node_id, location_id, depth,"
            + " inclusive_value, self_value, sample_count, relative_depth) AS ("
            + " SELECT node_id, parent_node_id, location_id, depth, inclusive_value,"
            + " self_value, sample_count, 0 FROM starlark_call_nodes WHERE node_id = ?"
            + " UNION ALL SELECT child.node_id, child.parent_node_id, child.location_id,"
            + " child.depth, child.inclusive_value, child.self_value, child.sample_count,"
            + " tree.relative_depth + 1 FROM starlark_call_nodes child"
            + " JOIN subtree tree ON child.parent_node_id = tree.node_id) ";
    long total;
    OptionalLong totalCpu;
    try (PreparedStatement statement =
        prepare(
            cte
                + "SELECT COUNT(*), MAX(CASE WHEN relative_depth = 0"
                + " THEN inclusive_value END) FROM subtree")) {
      statement.setLong(1, focusNodeId);
      try (ResultSet rows = execute(statement)) {
        rows.next();
        total = rows.getLong(1);
        totalCpu = optionalLong(rows, 2);
      }
    }
    if (total == 0) {
      return new FlameSlice(OptionalLong.of(focusNodeId), 0, 0, OptionalLong.empty(), List.of());
    }
    String sql =
        cte
            + "SELECT node.node_id,"
            + " CASE WHEN node.relative_depth = 0 THEN NULL ELSE node.parent_node_id END,"
            + " node.relative_depth, name.value, file.value, fn.start_line,"
            + " node.inclusive_value, node.self_value, node.sample_count"
            + flameFrom("subtree")
            + " ORDER BY node.relative_depth, node.node_id LIMIT ?";
    List<FlameNode> nodes =
        readFlameRows(
            sql,
            statement -> {
              statement.setLong(1, focusNodeId);
              statement.setInt(2, maxNodes);
            });
    return new FlameSlice(
        OptionalLong.of(focusNodeId), total, Math.max(0, total - nodes.size()), totalCpu, nodes);
  }

  private static String flameFrom() {
    return flameFrom("starlark_call_nodes");
  }

  /** Production root-flame SQL exposed package-locally for query-plan regression tests. */
  static String rootFlameRowsSql() {
    return "SELECT node.node_id,"
        + " CASE WHEN parent.depth = 0 THEN NULL ELSE node.parent_node_id END,"
        + " node.depth - 1, name.value, file.value, fn.start_line,"
        + " node.inclusive_value, node.self_value, node.sample_count"
        + flameFrom()
        + " WHERE node.depth > 0 ORDER BY node.depth, node.node_id LIMIT ?";
  }

  private static String flameFrom(String table) {
    return " FROM "
        + table
        + " node"
        + " LEFT JOIN starlark_call_nodes parent"
        + " ON parent.node_id = node.parent_node_id"
        + " LEFT JOIN starlark_profile_location_lines line"
        + " ON line.location_id = node.location_id AND line.ordinal = 0"
        + " LEFT JOIN starlark_profile_functions fn"
        + " ON fn.function_id = line.function_id"
        + " LEFT JOIN starlark_profile_strings name"
        + " ON name.string_index = fn.name_string_index"
        + " LEFT JOIN starlark_profile_strings file"
        + " ON file.string_index = fn.filename_string_index";
  }

  private List<FlameNode> readFlameRows(String sql, StatementBinder binder) throws SQLException {
    try (PreparedStatement statement = prepare(sql)) {
      binder.bind(statement);
      try (ResultSet rows = execute(statement)) {
        List<FlameNode> result = new ArrayList<>();
        while (rows.next()) {
          long id = rows.getLong(1);
          result.add(
              new FlameNode(
                  id,
                  optionalLong(rows, 2),
                  rows.getInt(3),
                  displayName(rows.getString(4), id),
                  source(rows.getString(5), optionalLong(rows, 6)),
                  optionalLong(rows, 7),
                  optionalLong(rows, 8),
                  optionalLong(rows, 9)));
        }
        return List.copyOf(result);
      }
    }
  }

  @Override
  public void cancelRunningQuery() {
    Statement statement = running;
    if (statement != null) {
      try {
        statement.cancel();
      } catch (SQLException ignored) {
        // It finished between the read and cancel request.
      }
    }
  }

  @Override
  public void close() {
    if (closed) {
      return;
    }
    closed = true;
    cancelRunningQuery();
    try {
      connection.close();
    } catch (SQLException failure) {
      throw new SessionDataException(
          "closing the Starlark profile reader for " + describedSession + " failed", failure);
    }
  }

  private PreparedStatement prepare(String sql) throws SQLException {
    if (closed) {
      throw new SQLException("the Starlark profile reader is closed");
    }
    return connection.prepareStatement(sql);
  }

  private ResultSet execute(PreparedStatement statement) throws SQLException {
    running = statement;
    // Keep the statement visible while ResultSet.next() performs the real SQLite work.
    // The next query replaces this reference; cancelling a statement that has already
    // closed is harmless and simply throws the ignored SQLException above.
    return statement.executeQuery();
  }

  private long count(String sql, String... values) throws SQLException {
    try (PreparedStatement statement = prepare(sql)) {
      for (int i = 0; i < values.length; i++) {
        statement.setString(i + 1, values[i]);
      }
      try (ResultSet rows = execute(statement)) {
        return rows.next() ? rows.getLong(1) : 0L;
      }
    }
  }

  private <T> T call(String what, Callable<T> query) {
    try {
      return query.call();
    } catch (Exception failure) {
      throw new SessionDataException(what + " in " + describedSession + " failed", failure);
    }
  }

  private static void requirePage(long offset, int limit) {
    if (offset < 0 || limit <= 0) {
      throw new IllegalArgumentException("offset must be non-negative and limit positive");
    }
  }

  private static void requireId(long id) {
    if (id <= 0) {
      throw new IllegalArgumentException("function or node id must be positive");
    }
  }

  private static String contains(String text) {
    String escaped = text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    return "%" + escaped + "%";
  }

  private static Optional<SourceLocation> source(String path, OptionalLong line) {
    if (path == null || path.isBlank()) {
      return Optional.empty();
    }
    OptionalInt usableLine =
        line.isPresent() && line.getAsLong() > 0 && line.getAsLong() <= Integer.MAX_VALUE
            ? OptionalInt.of((int) line.getAsLong())
            : OptionalInt.empty();
    return Optional.of(new SourceLocation(path, usableLine));
  }

  private static String displayName(String value, long id) {
    return value == null || value.isBlank() ? "location " + id : value;
  }

  private static OptionalLong nanosToMicros(OptionalLong nanos) {
    return nanos.isPresent() ? OptionalLong.of(nanos.getAsLong() / 1_000L) : OptionalLong.empty();
  }

  private static AttributionCoverage coverage(
      ResultSet rows,
      int totalValueColumn,
      int totalSamplesColumn,
      int attributedValueColumn,
      int attributedSamplesColumn,
      String description)
      throws SQLException {
    OptionalLong totalValue = optionalLong(rows, totalValueColumn);
    OptionalLong totalSamples = optionalLong(rows, totalSamplesColumn);
    OptionalLong attributedValue = optionalLong(rows, attributedValueColumn);
    OptionalLong attributedSamples = optionalLong(rows, attributedSamplesColumn);
    return new AttributionCoverage(
        attributedValue,
        difference(totalValue, attributedValue, description + " CPU"),
        attributedSamples,
        difference(totalSamples, attributedSamples, description + " samples"));
  }

  private static OptionalLong difference(OptionalLong total, OptionalLong part, String description)
      throws SQLException {
    if (total.isEmpty() || part.isEmpty()) {
      return OptionalLong.empty();
    }
    if (part.getAsLong() > total.getAsLong()) {
      throw new SQLException(description + " attribution exceeds its profile total");
    }
    return OptionalLong.of(total.getAsLong() - part.getAsLong());
  }

  private static OptionalLong optionalLong(ResultSet rows, int column) throws SQLException {
    long value = rows.getLong(column);
    return rows.wasNull() ? OptionalLong.empty() : OptionalLong.of(value);
  }

  private static OptionalLong optionalLong(ResultSet rows, String column) throws SQLException {
    long value = rows.getLong(column);
    return rows.wasNull() ? OptionalLong.empty() : OptionalLong.of(value);
  }

  private static String firstText(String... candidates) {
    for (String candidate : candidates) {
      if (candidate != null && !candidate.isBlank()) {
        return candidate;
      }
    }
    return "No detail was recorded.";
  }

  @FunctionalInterface
  private interface StatementBinder {
    void bind(PreparedStatement statement) throws SQLException;
  }
}
