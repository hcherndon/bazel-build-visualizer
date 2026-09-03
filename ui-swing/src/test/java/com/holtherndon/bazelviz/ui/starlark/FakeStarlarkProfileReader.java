package com.holtherndon.bazelviz.ui.starlark;

import com.holtherndon.bazelviz.ui.session.StarlarkProfileReader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import javax.swing.SwingUtilities;

/** Small deterministic reader shared by the headless Starlark view tests. */
class FakeStarlarkProfileReader implements StarlarkProfileReader {

  final AtomicBoolean queriedOnEdt = new AtomicBoolean();
  final AtomicBoolean closedOnEdt = new AtomicBoolean();
  final AtomicBoolean closed = new AtomicBoolean();
  volatile Summary summary = availableSummary();
  volatile List<HotFunction> functions = functions();
  volatile List<SourceFile> files = files();
  volatile List<CallEdge> callers = edges("caller");
  volatile List<CallEdge> callees = edges("callee");
  volatile DirectedCallGraph directedGraph = directedGraph();
  volatile FlameSlice flame = flame();

  @Override
  public Summary summary() {
    noteThread();
    return summary;
  }

  @Override
  public long hotFunctionCount(FunctionQuery query) {
    noteThread();
    return matchingFunctions(query).size();
  }

  @Override
  public List<HotFunction> hotFunctions(FunctionQuery query, long offset, int limit) {
    noteThread();
    return page(matchingFunctions(query), offset, limit);
  }

  @Override
  public long sourceFileCount(FileQuery query) {
    noteThread();
    return matchingFiles(query).size();
  }

  @Override
  public List<SourceFile> sourceFiles(FileQuery query, long offset, int limit) {
    noteThread();
    return page(matchingFiles(query), offset, limit);
  }

  @Override
  public long callEdgeCount(long functionId, CallDirection direction) {
    noteThread();
    return edges(direction).size();
  }

  @Override
  public List<CallEdge> callEdges(
      long functionId, CallDirection direction, long offset, int limit) {
    noteThread();
    return page(edges(direction), offset, limit);
  }

  @Override
  public DirectedCallGraph directedCallGraph(
      OptionalLong pinnedFunctionId, int maxNodes, int maxEdges) {
    noteThread();
    List<CallGraphNode> nodes =
        directedGraph.nodes().stream()
            .sorted(
                Comparator.comparingInt(
                    node ->
                        pinnedFunctionId.isPresent()
                                && node.functionId() == pinnedFunctionId.getAsLong()
                            ? 0
                            : 1))
            .limit(maxNodes)
            .toList();
    Set<Long> visible = nodes.stream().map(CallGraphNode::functionId).collect(Collectors.toSet());
    List<DirectedCallEdge> eligible =
        directedGraph.edges().stream()
            .filter(
                edge ->
                    visible.contains(edge.callerFunctionId())
                        && visible.contains(edge.calleeFunctionId()))
            .toList();
    List<DirectedCallEdge> edges = eligible.stream().limit(maxEdges).toList();
    return new DirectedCallGraph(
        directedGraph.totalFunctionCount(),
        directedGraph.totalFunctionCount() - nodes.size(),
        eligible.size(),
        eligible.size() - edges.size(),
        directedGraph.totalCpuMicros(),
        nodes,
        edges);
  }

  @Override
  public FlameSlice flameRows(OptionalLong focusNodeId, int maxNodes) {
    noteThread();
    List<FlameNode> rows = flame.nodes().subList(0, Math.min(maxNodes, flame.nodes().size()));
    return new FlameSlice(
        focusNodeId,
        flame.totalNodeCount(),
        flame.totalNodeCount() - rows.size(),
        flame.totalCpuMicros(),
        rows);
  }

  @Override
  public void close() {
    closedOnEdt.set(SwingUtilities.isEventDispatchThread());
    closed.set(true);
  }

  private List<HotFunction> matchingFunctions(FunctionQuery query) {
    String needle = query.search().toLowerCase(Locale.ROOT);
    List<HotFunction> matches =
        new ArrayList<>(
            functions.stream()
                .filter(
                    function ->
                        needle.isEmpty()
                            || function.name().toLowerCase(Locale.ROOT).contains(needle)
                            || function
                                .source()
                                .map(SourceLocation::path)
                                .orElse("")
                                .toLowerCase(Locale.ROOT)
                                .contains(needle))
                .toList());
    Comparator<HotFunction> order =
        switch (query.sort()) {
          case SELF_CPU ->
              Comparator.comparingLong(function -> function.selfCpuMicros().orElse(-1));
          case CUMULATIVE_CPU ->
              Comparator.comparingLong(function -> function.cumulativeCpuMicros().orElse(-1));
          case NAME -> Comparator.comparing(HotFunction::name);
          case FILE ->
              Comparator.comparing(
                  function -> function.source().map(SourceLocation::path).orElse(""));
        };
    order = order.thenComparingLong(HotFunction::id);
    matches.sort(query.descending() ? order.reversed() : order);
    return matches;
  }

  private List<SourceFile> matchingFiles(FileQuery query) {
    String needle = query.search().toLowerCase(Locale.ROOT);
    List<SourceFile> matches =
        new ArrayList<>(
            files.stream()
                .filter(
                    file ->
                        needle.isEmpty() || file.path().toLowerCase(Locale.ROOT).contains(needle))
                .toList());
    Comparator<SourceFile> order =
        switch (query.sort()) {
          case SELF_CPU -> Comparator.comparingLong(file -> file.selfCpuMicros().orElse(-1));
          case CUMULATIVE_CPU ->
              Comparator.comparingLong(file -> file.cumulativeCpuMicros().orElse(-1));
          case PATH -> Comparator.comparing(SourceFile::path);
        };
    matches.sort(query.descending() ? order.reversed() : order);
    return matches;
  }

  private List<CallEdge> edges(CallDirection direction) {
    return direction == CallDirection.CALLERS ? callers : callees;
  }

  private void noteThread() {
    queriedOnEdt.compareAndSet(false, SwingUtilities.isEventDispatchThread());
  }

  private static <T> List<T> page(List<T> rows, long offset, int limit) {
    int first = Math.toIntExact(Math.min(offset, rows.size()));
    int last = Math.min(rows.size(), first + limit);
    return List.copyOf(rows.subList(first, last));
  }

  static Summary availableSummary() {
    return new Summary(
        Availability.AVAILABLE,
        "A complete Bazel Starlark CPU profile was imported.",
        Correlation.CAPTURED_WITH_INVOCATION,
        OptionalLong.of(2_000_000),
        OptionalLong.of(1_000_000),
        OptionalLong.of(200),
        OptionalLong.of(10_000),
        OptionalLong.of(2),
        OptionalLong.of(1),
        completeCoverage(2_000_000, 200),
        completeCoverage(2_000_000, 200),
        completeCoverage(2_000_000, 200));
  }

  private static AttributionCoverage completeCoverage(long cpuMicros, long samples) {
    return new AttributionCoverage(
        OptionalLong.of(cpuMicros), OptionalLong.of(0),
        OptionalLong.of(samples), OptionalLong.of(0));
  }

  static List<HotFunction> functions() {
    return List.of(
        new HotFunction(
            1,
            "compile_rules",
            Optional.of(new SourceLocation("tools/compile.bzl", OptionalInt.of(17))),
            OptionalLong.of(1_200_000),
            OptionalLong.of(1_700_000),
            OptionalLong.of(120),
            OptionalLong.of(170),
            OptionalLong.of(3)),
        new HotFunction(
            2,
            "load_config",
            Optional.of(SourceLocation.file("config/settings.bzl")),
            OptionalLong.of(800_000),
            OptionalLong.of(900_000),
            OptionalLong.of(80),
            OptionalLong.of(90),
            OptionalLong.of(1)));
  }

  static List<SourceFile> files() {
    return List.of(
        new SourceFile(
            "tools/compile.bzl",
            OptionalLong.of(1_200_000),
            OptionalLong.of(1_700_000),
            OptionalLong.of(1),
            OptionalLong.of(120)));
  }

  static List<CallEdge> edges(String name) {
    return List.of(
        new CallEdge(
            name.equals("caller") ? 11 : 12,
            name + "_function",
            Optional.of(SourceLocation.file(name + ".bzl")),
            OptionalLong.of(500_000),
            OptionalLong.of(50)));
  }

  static DirectedCallGraph directedGraph() {
    List<CallGraphNode> nodes =
        functions().stream()
            .map(
                function ->
                    new CallGraphNode(
                        function.id(),
                        function.name(),
                        function.source(),
                        function.selfCpuMicros(),
                        function.cumulativeCpuMicros(),
                        function.selfSampleRecords(),
                        function.cumulativeSampleRecords(),
                        function.contextCount()))
            .toList();
    return new DirectedCallGraph(
        nodes.size(),
        0,
        1,
        0,
        OptionalLong.of(2_000_000),
        nodes,
        List.of(new DirectedCallEdge(1, 2, OptionalLong.of(800_000), OptionalLong.of(80))));
  }

  static FlameSlice flame() {
    List<FlameNode> nodes =
        List.of(
            new FlameNode(
                1,
                OptionalLong.empty(),
                0,
                "compile_rules",
                Optional.of(SourceLocation.file("tools/compile.bzl")),
                OptionalLong.of(2_000_000),
                OptionalLong.of(1_200_000),
                OptionalLong.of(200)),
            new FlameNode(
                2,
                OptionalLong.of(1),
                1,
                "load_config",
                Optional.of(SourceLocation.file("config/settings.bzl")),
                OptionalLong.of(800_000),
                OptionalLong.of(800_000),
                OptionalLong.of(80)));
    return new FlameSlice(OptionalLong.empty(), nodes.size(), 0, OptionalLong.of(2_000_000), nodes);
  }
}
