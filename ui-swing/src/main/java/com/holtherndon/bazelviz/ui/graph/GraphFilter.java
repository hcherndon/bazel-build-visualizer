package com.holtherndon.bazelviz.ui.graph;

import com.holtherndon.bazelviz.analysis.GraphExtract;
import com.holtherndon.bazelviz.analysis.GraphWeights;
import com.holtherndon.bazelviz.core.filter.FilterExpression;
import com.holtherndon.bazelviz.core.filter.FilterExpression.Condition;
import com.holtherndon.bazelviz.core.filter.FilterExpression.Group;
import com.holtherndon.bazelviz.core.filter.FilterExpression.Operator;
import com.holtherndon.bazelviz.graph.CsrGraph;
import com.holtherndon.bazelviz.storage.graph.GraphQueries;
import com.holtherndon.bazelviz.ui.filter.FilterField;
import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/** Worker-only filtering of bounded scope extractions, or streamed whole-graph metadata. */
final class GraphFilter {
  private GraphFilter() {}

  static List<FilterField> fields() {
    return List.of(
        field(
            "label",
            "Target label",
            FilterField.Kind.TEXT,
            "Use starts with // to keep workspace labels. Text comparisons ignore case; % and _ are"
                + " literal."),
        field(
            "mnemonic",
            "Mnemonic",
            FilterField.Kind.TEXT,
            "Action mnemonic, such as Javac. Unknown for target-label graph nodes."),
        field(
            "name",
            "Display name",
            FilterField.Kind.TEXT,
            "The action's mnemonic/output name, or target label."),
        field(
            "deps",
            "Direct dependencies",
            FilterField.Kind.NUMBER,
            "Immediate dependencies in the full selected source graph, before filtering."),
        field(
            "rdeps",
            "Direct reverse dependencies",
            FilterField.Kind.NUMBER,
            "Immediate dependents in the full selected source graph, before filtering."),
        field(
            "transitive_deps",
            "Transitive dependencies (scope)",
            FilterField.Kind.NUMBER,
            "Other nodes reachable within the chosen scope BEFORE filters. Not a whole-build count"
                + " for a neighbourhood. Requires the scope to fit its budgets."),
        field(
            "transitive_rdeps",
            "Transitive reverse dependencies (scope)",
            FilterField.Kind.NUMBER,
            "Other dependent nodes reachable within the chosen scope BEFORE filters. Counts are not"
                + " recomputed after nodes are hidden."),
        field(
            "duration",
            "Duration (µs)",
            FilterField.Kind.NUMBER,
            "Recorded action duration in microseconds. Unknown for unexecuted actions and"
                + " target-label nodes."));
  }

  private static FilterField field(String id, String label, FilterField.Kind kind, String help) {
    return new FilterField(id, label, kind, List.of(), help);
  }

  static void validate(FilterExpression filter) {
    if (filter instanceof Group group) {
      group.children().forEach(GraphFilter::validate);
      return;
    }
    Condition condition = (Condition) filter;
    FilterField field =
        fields().stream()
            .filter(value -> value.id().equals(condition.field()))
            .findFirst()
            .orElseThrow(
                () -> new IllegalArgumentException("Unknown graph filter: " + condition.field()));
    if (!field.operators().contains(condition.operator())) {
      throw new IllegalArgumentException("Unsupported operator for " + field.label());
    }
    if (field.kind() == FilterField.Kind.NUMBER) {
      condition.values().forEach(Long::parseLong);
    }
  }

  static boolean uses(FilterExpression filter, String field) {
    return filter instanceof Condition condition
        ? condition.field().equals(field)
        : ((Group) filter).children().stream().anyMatch(child -> uses(child, field));
  }

  static boolean transitive(FilterExpression filter) {
    return uses(filter, "transitive_deps") || uses(filter, "transitive_rdeps");
  }

  static long retainedBytes(FilterExpression filter) {
    if (filter instanceof Group group) {
      return 64 + group.children().stream().mapToLong(GraphFilter::retainedBytes).sum();
    }
    Condition condition = (Condition) filter;
    return 96L
        + 2L * condition.field().length()
        + condition.values().stream().mapToLong(value -> 48L + 2L * value.length()).sum();
  }

  static boolean matches(FilterExpression filter, Function<String, Object> value) {
    if (filter.isEmpty()) {
      return true;
    }
    if (filter instanceof Group group) {
      var children = group.children().stream().filter(child -> !child.isEmpty());
      return group.junction() == FilterExpression.Junction.ALL
          ? children.allMatch(child -> matches(child, value))
          : children.anyMatch(child -> matches(child, value));
    }
    Condition condition = (Condition) filter;
    Object actual = value.apply(condition.field());
    Operator op = condition.operator();
    if (op == Operator.IS_PRESENT || op == Operator.IS_ABSENT) {
      return (actual != null) == (op == Operator.IS_PRESENT);
    }
    // Missing metadata never satisfies a negative comparison either.
    if (actual == null) {
      return false;
    }
    String wanted = condition.values().getFirst();
    if (actual instanceof Long number) {
      int comparison = Long.compare(number, Long.parseLong(wanted));
      return switch (op) {
        case EQUALS -> comparison == 0;
        case NOT_EQUALS -> comparison != 0;
        case GREATER_THAN -> comparison > 0;
        case AT_LEAST -> comparison >= 0;
        case LESS_THAN -> comparison < 0;
        case AT_MOST -> comparison <= 0;
        default -> throw new IllegalArgumentException("Invalid numeric filter: " + op);
      };
    }
    String text = actual.toString().toLowerCase(Locale.ROOT);
    String expected = wanted.toLowerCase(Locale.ROOT);
    return switch (op) {
      case EQUALS -> text.equals(expected);
      case NOT_EQUALS -> !text.equals(expected);
      case CONTAINS -> text.contains(expected);
      case NOT_CONTAINS -> !text.contains(expected);
      case STARTS_WITH -> text.startsWith(expected);
      case NOT_STARTS_WITH -> !text.startsWith(expected);
      case ENDS_WITH -> text.endsWith(expected);
      case NOT_ENDS_WITH -> !text.endsWith(expected);
      case IN ->
          condition.values().stream().anyMatch(item -> text.equals(item.toLowerCase(Locale.ROOT)));
      case NOT_IN ->
          condition.values().stream().noneMatch(item -> text.equals(item.toLowerCase(Locale.ROOT)));
      default -> throw new IllegalArgumentException("Invalid text filter: " + op);
    };
  }

  record Result(GraphExtract.Result extract, String description) {}

  static Result apply(
      GraphLayoutService.Request request,
      GraphExtract.Result scope,
      GraphQueries queries,
      CsrGraph forward,
      CsrGraph reverse,
      AtomicBoolean cancelled)
      throws IOException, SQLException {
    FilterExpression filter = request.filter();
    boolean wholeStream = request.mode() == GraphExtract.Mode.WHOLE && !transitive(filter);
    if (!wholeStream && scope.hitLimit()) {
      throw new IOException(
          "The scope reached its node or edge budget. Narrow the scope or raise the budget before"
              + " filtering; transitive counts require a complete scope.");
    }
    long[] deps = counts(filter, "transitive_deps", scope, false);
    long[] rdeps = counts(filter, "transitive_rdeps", scope, true);
    long count = wholeStream ? forward.nodeCount() : scope.nodes().size();
    List<Integer> kept = new ArrayList<>();
    long matched = 0;
    for (long start = 0; start < count; start += 400) {
      checkCancelled(cancelled);
      int size = (int) Math.min(400, count - start);
      List<Integer> batch = new ArrayList<>(size);
      for (int offset = 0; offset < size; offset++) {
        batch.add(
            wholeStream
                ? Math.toIntExact(start + offset)
                : scope.nodes().get((int) start + offset));
      }
      try (GraphQueries.NodeMetadata metadata = queries.metadata(request.graph(), batch)) {
        for (int offset = 0; offset < size; offset++) {
          int position = offset;
          int scopePosition = Math.toIntExact(start + offset);
          int node = batch.get(offset);
          if (matches(
              filter,
              field ->
                  switch (field) {
                    case "label" -> metadata.ownerLabels()[position];
                    case "mnemonic" -> metadata.mnemonics()[position];
                    case "name" -> metadata.displayLabels()[position];
                    case "duration" -> known(metadata.durations()[position]);
                    case "deps" -> reverse == null ? null : (long) reverse.degree(node);
                    case "rdeps" -> forward == null ? null : (long) forward.degree(node);
                    case "transitive_deps" -> known(deps[scopePosition]);
                    case "transitive_rdeps" -> known(rdeps[scopePosition]);
                    default -> throw new IllegalArgumentException("Unknown graph field: " + field);
                  })) {
            matched++;
            if (kept.size() < request.nodeLimit()) {
              kept.add(node);
            }
          }
        }
      }
    }
    boolean tooManyNodes = matched > request.nodeLimit();
    List<GraphExtract.Edge> edges = new ArrayList<>();
    long[] matchingEdges = {0};
    if (!tooManyNodes) {
      Set<Integer> membership = new HashSet<>(kept);
      if (wholeStream) {
        for (int from : kept) {
          checkCancelled(cancelled);
          forward.forEachNeighbor(
              from,
              to -> {
                checkCancelled(cancelled);
                if (membership.contains(to)) {
                  matchingEdges[0]++;
                  if (edges.size() < request.edgeLimit()) {
                    edges.add(new GraphExtract.Edge(from, to));
                  }
                }
              });
        }
      } else {
        for (GraphExtract.Edge edge : scope.edges()) {
          checkCancelled(cancelled);
          if (membership.contains(edge.from()) && membership.contains(edge.to())) {
            matchingEdges[0]++;
            if (edges.size() < request.edgeLimit()) {
              edges.add(edge);
            }
          }
        }
      }
    }
    boolean tooManyEdges = matchingEdges[0] > request.edgeLimit();
    boolean refused = tooManyNodes || tooManyEdges;
    GraphExtract.Result filtered =
        new GraphExtract.Result(
            request.mode(),
            refused ? List.of() : kept,
            refused ? List.of() : edges,
            scope.totalNodes(),
            scope.totalEdges(),
            request.nodeLimit(),
            request.edgeLimit(),
            tooManyNodes,
            tooManyEdges);
    String description =
        "Filters: "
            + matched
            + " of "
            + count
            + " "
            + GraphLayoutService.nounFor(request.graph())
            + " nodes match in the chosen scope; "
            + (count - matched)
            + " hidden. "
            + (refused
                ? "Matching nodes or edges exceed the drawing budget; nothing was drawn. "
                : "Only edges between matching nodes are shown. ")
            + "Direct counts use the full source graph. "
            + (transitive(filter) ? "Transitive counts use the scope before filtering. " : "")
            + "Source: "
            + scope.totalNodes()
            + " nodes, "
            + scope.totalEdges()
            + " edges.";
    return new Result(filtered, description);
  }

  private static Long known(long value) {
    return value < 0 ? null : value;
  }

  private static long[] counts(
      FilterExpression filter, String field, GraphExtract.Result scope, boolean forward)
      throws IOException {
    if (!uses(filter, field)) {
      return new long[0];
    }
    GraphWeights.Result counts =
        GraphWeights.subgraphTransitiveCounts(
            scope.nodes(), scope.edges(), forward, GraphWeights.SUBGRAPH_TRANSITIVE_WORK_BUDGET);
    if (counts.truncated()) {
      throw new IOException(
          "Transitive filter counts exceeded the work budget. Narrow the scope; no partially"
              + " evaluated filter was applied.");
    }
    return counts.values();
  }

  private static void checkCancelled(AtomicBoolean cancelled) {
    if (cancelled.get() || Thread.currentThread().isInterrupted()) {
      throw new CancellationException("Graph filtering cancelled");
    }
  }
}
