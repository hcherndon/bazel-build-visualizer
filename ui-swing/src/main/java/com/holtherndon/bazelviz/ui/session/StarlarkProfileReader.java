package com.holtherndon.bazelviz.ui.session;

import java.text.NumberFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * Read service for one Bazel Starlark CPU profile.
 *
 * <p>Every method may block on the session database and therefore belongs on a view-owned worker,
 * never Swing's event thread. Results are immutable snapshots that may safely be handed to the
 * event thread. Missing measurements use {@link OptionalLong}; a profile that measured zero is not
 * the same thing as one that did not report a value.
 */
public interface StarlarkProfileReader extends AutoCloseable {

  /** Maximum type/unit label length before a standalone metric is refused for display. */
  int MAX_METRIC_TEXT_CHARACTERS = 256;

  /** Standalone values retain original units; legacy CPU field names are storage adapters. */
  record SampleMetric(String type, String unit) {
    public SampleMetric {
      Objects.requireNonNull(type, "type");
      Objects.requireNonNull(unit, "unit");
      if (type.length() > MAX_METRIC_TEXT_CHARACTERS
          || unit.length() > MAX_METRIC_TEXT_CHARACTERS) {
        throw new IllegalArgumentException(
            "Profile sample type or unit exceeds the "
                + MAX_METRIC_TEXT_CHARACTERS
                + "-character display limit");
      }
    }

    public String format(OptionalLong value) {
      return value.isEmpty()
          ? "—"
          : NumberFormat.getIntegerInstance().format(value.getAsLong())
              + (unit.isBlank() ? "" : " " + unit);
    }
  }

  /** Empty for the Bazel-specific CPU presentation. Called on the reader worker. */
  default Optional<SampleMetric> sampleMetric() {
    return Optional.empty();
  }

  /** Why a profile page does or does not have queryable rows. */
  enum Availability {
    AVAILABLE("Profile imported"),
    NOT_CAPTURED("No Starlark CPU profile was captured"),
    SKIPPED("Starlark CPU profiling was skipped"),
    IMPORT_FAILED("The Starlark CPU profile could not be imported"),
    UNSUPPORTED("This Bazel or platform could not produce a Starlark CPU profile"),
    UNKNOWN("Starlark CPU profile availability is unknown");

    private final String displayName;

    Availability(String displayName) {
      this.displayName = displayName;
    }

    public String displayName() {
      return displayName;
    }
  }

  /** How confidently this profile is associated with the open invocation. */
  enum Correlation {
    STANDALONE("Standalone profile; not associated with a build"),
    CAPTURED_WITH_INVOCATION("Captured by this invocation"),
    MANUAL_UNVERIFIED("Manually attached; build association is unverified"),
    UNKNOWN("Build association is unknown");

    private final String displayName;

    Correlation(String displayName) {
      this.displayName = displayName;
    }

    public String displayName() {
      return displayName;
    }
  }

  /** Supported orderings for the hot-function table. */
  enum FunctionSort {
    SELF_CPU("Self"),
    CUMULATIVE_CPU("Cumulative"),
    NAME("Function"),
    FILE("Source file");

    private final String displayName;

    FunctionSort(String displayName) {
      this.displayName = displayName;
    }

    @Override
    public String toString() {
      return displayName;
    }
  }

  /** Supported orderings for source-file aggregates. */
  enum FileSort {
    SELF_CPU("Self"),
    CUMULATIVE_CPU("Cumulative"),
    PATH("Path");

    private final String displayName;

    FileSort(String displayName) {
      this.displayName = displayName;
    }

    @Override
    public String toString() {
      return displayName;
    }
  }

  /** Which side of a selected function an edge page describes. */
  enum CallDirection {
    CALLERS,
    CALLEES
  }

  /** One source location. The line is a navigation hint, not line-level CPU evidence. */
  record SourceLocation(String path, OptionalInt line) {
    public SourceLocation {
      Objects.requireNonNull(path, "path");
      Objects.requireNonNull(line, "line");
      if (path.isBlank()) {
        throw new IllegalArgumentException("a source path must not be blank");
      }
      if (line.isPresent() && line.getAsInt() < 1) {
        throw new IllegalArgumentException("a source line must be positive");
      }
    }

    public static SourceLocation file(String path) {
      return new SourceLocation(path, OptionalInt.empty());
    }
  }

  /** Exact partition of sampled CPU and sample records by one attribution level. */
  record AttributionCoverage(
      OptionalLong attributedCpuMicros,
      OptionalLong unattributedCpuMicros,
      OptionalLong attributedSampleRecords,
      OptionalLong unattributedSampleRecords) {

    public AttributionCoverage {
      requireNonNegative(attributedCpuMicros, "attributedCpuMicros");
      requireNonNegative(unattributedCpuMicros, "unattributedCpuMicros");
      requireNonNegative(attributedSampleRecords, "attributedSampleRecords");
      requireNonNegative(unattributedSampleRecords, "unattributedSampleRecords");
    }

    public static AttributionCoverage unavailable() {
      return new AttributionCoverage(
          OptionalLong.empty(), OptionalLong.empty(),
          OptionalLong.empty(), OptionalLong.empty());
    }

    public boolean complete() {
      return unattributedCpuMicros.isPresent()
          && unattributedCpuMicros.getAsLong() == 0
          && unattributedSampleRecords.isPresent()
          && unattributedSampleRecords.getAsLong() == 0;
    }
  }

  /** Profile-wide facts. All counts, durations, and coverage values may be unavailable. */
  record Summary(
      Availability availability,
      String detail,
      Correlation correlation,
      OptionalLong sampledCpuMicros,
      OptionalLong wallDurationMicros,
      OptionalLong sampleRecordCount,
      OptionalLong samplePeriodMicros,
      OptionalLong functionCount,
      OptionalLong fileCount,
      AttributionCoverage functionAttribution,
      AttributionCoverage fileAttribution,
      AttributionCoverage contextAttribution) {

    public Summary {
      Objects.requireNonNull(availability, "availability");
      Objects.requireNonNull(detail, "detail");
      Objects.requireNonNull(correlation, "correlation");
      requireNonNegative(sampledCpuMicros, "sampledCpuMicros");
      requireNonNegative(wallDurationMicros, "wallDurationMicros");
      requireNonNegative(sampleRecordCount, "sampleRecordCount");
      requireNonNegative(samplePeriodMicros, "samplePeriodMicros");
      requireNonNegative(functionCount, "functionCount");
      requireNonNegative(fileCount, "fileCount");
      Objects.requireNonNull(functionAttribution, "functionAttribution");
      Objects.requireNonNull(fileAttribution, "fileAttribution");
      Objects.requireNonNull(contextAttribution, "contextAttribution");
    }

    public boolean isAvailable() {
      return availability == Availability.AVAILABLE;
    }
  }

  /** Search and ordering applied to one stable function page source. */
  record FunctionQuery(String search, FunctionSort sort, boolean descending) {
    public FunctionQuery {
      search = Objects.requireNonNull(search, "search").strip();
      Objects.requireNonNull(sort, "sort");
    }
  }

  /** Search and ordering applied to one stable file page source. */
  record FileQuery(String search, FileSort sort, boolean descending) {
    public FileQuery {
      search = Objects.requireNonNull(search, "search").strip();
      Objects.requireNonNull(sort, "sort");
    }
  }

  /** One function aggregate. Percentages are intentionally derived from the summary total. */
  record HotFunction(
      long id,
      String name,
      Optional<SourceLocation> source,
      OptionalLong selfCpuMicros,
      OptionalLong cumulativeCpuMicros,
      OptionalLong selfSampleRecords,
      OptionalLong cumulativeSampleRecords,
      OptionalLong contextCount) {

    public HotFunction {
      requireId(id, "function id");
      requireText(name, "function name");
      source = Objects.requireNonNull(source, "source");
      requireNonNegative(selfCpuMicros, "selfCpuMicros");
      requireNonNegative(cumulativeCpuMicros, "cumulativeCpuMicros");
      requireNonNegative(selfSampleRecords, "selfSampleRecords");
      requireNonNegative(cumulativeSampleRecords, "cumulativeSampleRecords");
      requireNonNegative(contextCount, "contextCount");
    }
  }

  /** One source-file aggregate. */
  record SourceFile(
      String path,
      OptionalLong selfCpuMicros,
      OptionalLong cumulativeCpuMicros,
      OptionalLong functionCount,
      OptionalLong sampleRecords) {

    public SourceFile {
      requireText(path, "source path");
      requireNonNegative(selfCpuMicros, "selfCpuMicros");
      requireNonNegative(cumulativeCpuMicros, "cumulativeCpuMicros");
      requireNonNegative(functionCount, "functionCount");
      requireNonNegative(sampleRecords, "sampleRecords");
    }

    public SourceLocation source() {
      return SourceLocation.file(path);
    }
  }

  /** One aggregate caller or callee edge for the selected function. */
  record CallEdge(
      long relatedFunctionId,
      String relatedFunction,
      Optional<SourceLocation> source,
      OptionalLong cpuMicros,
      OptionalLong sampleRecords) {

    public CallEdge {
      requireId(relatedFunctionId, "related function id");
      requireText(relatedFunction, "related function name");
      source = Objects.requireNonNull(source, "source");
      requireNonNegative(cpuMicros, "cpuMicros");
      requireNonNegative(sampleRecords, "sampleRecords");
    }
  }

  /** One function box in the bounded directed call-graph projection. */
  record CallGraphNode(
      long functionId,
      String function,
      Optional<SourceLocation> source,
      OptionalLong selfCpuMicros,
      OptionalLong cumulativeCpuMicros,
      OptionalLong selfSampleRecords,
      OptionalLong cumulativeSampleRecords,
      OptionalLong contextCount) {

    public CallGraphNode {
      requireId(functionId, "call-graph function id");
      requireText(function, "call-graph function name");
      source = Objects.requireNonNull(source, "source");
      requireNonNegative(selfCpuMicros, "selfCpuMicros");
      requireNonNegative(cumulativeCpuMicros, "cumulativeCpuMicros");
      requireNonNegative(selfSampleRecords, "selfSampleRecords");
      requireNonNegative(cumulativeSampleRecords, "cumulativeSampleRecords");
      requireNonNegative(contextCount, "contextCount");
    }
  }

  /** One caller-to-callee arrow after physical locations are aggregated by function. */
  record DirectedCallEdge(
      long callerFunctionId,
      long calleeFunctionId,
      OptionalLong cpuMicros,
      OptionalLong sampleRecords) {

    public DirectedCallEdge {
      requireId(callerFunctionId, "caller function id");
      requireId(calleeFunctionId, "callee function id");
      requireNonNegative(cpuMicros, "cpuMicros");
      requireNonNegative(sampleRecords, "sampleRecords");
    }
  }

  /**
   * A bounded hot-function projection of the profile's directed call graph.
   *
   * <p>{@code totalFunctionCount} covers the complete imported profile. Edge totals cover the exact
   * relationships whose two endpoints are among {@code nodes}; relationships touching an omitted
   * function are outside this projection and are described separately by the UI.
   */
  record DirectedCallGraph(
      long totalFunctionCount,
      long omittedFunctionCount,
      long visibleEdgeCount,
      long omittedVisibleEdgeCount,
      OptionalLong totalCpuMicros,
      List<CallGraphNode> nodes,
      List<DirectedCallEdge> edges) {

    public DirectedCallGraph {
      if (totalFunctionCount < 0
          || omittedFunctionCount < 0
          || omittedFunctionCount > totalFunctionCount) {
        throw new IllegalArgumentException("invalid call-graph function totals");
      }
      if (visibleEdgeCount < 0
          || omittedVisibleEdgeCount < 0
          || omittedVisibleEdgeCount > visibleEdgeCount) {
        throw new IllegalArgumentException("invalid call-graph edge totals");
      }
      requireNonNegative(totalCpuMicros, "totalCpuMicros");
      nodes = List.copyOf(nodes);
      edges = List.copyOf(edges);
      if ((long) nodes.size() + omittedFunctionCount != totalFunctionCount) {
        throw new IllegalArgumentException(
            "call-graph rows plus omitted functions must equal the exact total");
      }
      if ((long) edges.size() + omittedVisibleEdgeCount != visibleEdgeCount) {
        throw new IllegalArgumentException(
            "call-graph arrows plus omitted arrows must equal the visible-edge total");
      }
      HashSet<Long> functionIds = new HashSet<>();
      for (CallGraphNode node : nodes) {
        if (!functionIds.add(node.functionId())) {
          throw new IllegalArgumentException("call-graph function ids must be unique");
        }
      }
      for (DirectedCallEdge edge : edges) {
        if (!functionIds.contains(edge.callerFunctionId())
            || !functionIds.contains(edge.calleeFunctionId())) {
          throw new IllegalArgumentException(
              "every call-graph arrow endpoint must be a visible function");
        }
      }
    }

    public boolean containsFunction(long functionId) {
      return nodes.stream().anyMatch(node -> node.functionId() == functionId);
    }
  }

  /** One context in a root-to-leaf call hierarchy returned for the flame view. */
  record FlameNode(
      long id,
      OptionalLong parentId,
      int depth,
      String function,
      Optional<SourceLocation> source,
      OptionalLong inclusiveCpuMicros,
      OptionalLong selfCpuMicros,
      OptionalLong sampleRecords) {

    public FlameNode {
      requireId(id, "flame node id");
      Objects.requireNonNull(parentId, "parentId");
      if (parentId.isPresent()) {
        requireId(parentId.getAsLong(), "parent flame node id");
      }
      if (depth < 0) {
        throw new IllegalArgumentException("flame depth must not be negative");
      }
      requireText(function, "flame function name");
      source = Objects.requireNonNull(source, "source");
      requireNonNegative(inclusiveCpuMicros, "inclusiveCpuMicros");
      requireNonNegative(selfCpuMicros, "selfCpuMicros");
      requireNonNegative(sampleRecords, "sampleRecords");
    }
  }

  /** A bounded, exact-prefix hierarchy slice. Omitted contexts are always counted explicitly. */
  record FlameSlice(
      OptionalLong focusNodeId,
      long totalNodeCount,
      long omittedNodeCount,
      OptionalLong totalCpuMicros,
      List<FlameNode> nodes) {

    public FlameSlice {
      Objects.requireNonNull(focusNodeId, "focusNodeId");
      if (focusNodeId.isPresent()) {
        requireId(focusNodeId.getAsLong(), "focus node id");
      }
      if (totalNodeCount < 0 || omittedNodeCount < 0 || omittedNodeCount > totalNodeCount) {
        throw new IllegalArgumentException("invalid flame-node totals");
      }
      requireNonNegative(totalCpuMicros, "totalCpuMicros");
      nodes = List.copyOf(nodes);
      if ((long) nodes.size() + omittedNodeCount != totalNodeCount) {
        throw new IllegalArgumentException(
            "flame rows plus omitted rows must equal the exact total");
      }
    }

    public boolean complete() {
      return omittedNodeCount == 0;
    }
  }

  Summary summary();

  long hotFunctionCount(FunctionQuery query);

  List<HotFunction> hotFunctions(FunctionQuery query, long offset, int limit);

  long sourceFileCount(FileQuery query);

  List<SourceFile> sourceFiles(FileQuery query, long offset, int limit);

  long callEdgeCount(long functionId, CallDirection direction);

  List<CallEdge> callEdges(long functionId, CallDirection direction, long offset, int limit);

  /**
   * Returns the hottest {@code maxNodes}, pinning the requested function when it exists, and at
   * most {@code maxEdges} strongest arrows between those returned functions.
   */
  DirectedCallGraph directedCallGraph(OptionalLong pinnedFunctionId, int maxNodes, int maxEdges);

  /** Returns at most {@code maxNodes}; the result states exactly what was omitted. */
  FlameSlice flameRows(OptionalLong focusNodeId, int maxNodes);

  /** Interrupts a running database query when the implementation supports it. */
  default void cancelRunningQuery() {}

  @Override
  void close();

  private static void requireId(long id, String name) {
    if (id <= 0) {
      throw new IllegalArgumentException(name + " must be positive");
    }
  }

  private static void requireText(String value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
  }

  private static void requireNonNegative(OptionalLong value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isPresent() && value.getAsLong() < 0) {
      throw new IllegalArgumentException(name + " must not be negative");
    }
  }
}
