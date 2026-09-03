package com.holtherndon.bazelviz.ui.criticalpath;

import com.holtherndon.bazelviz.storage.graph.GraphQueries;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/** One dependency-chain step, resolved from its graph node only when its page is read. */
public record CriticalPathRow(
    int ordinal,
    int totalSteps,
    int nodeIndex,
    Optional<GraphQueries.GraphNode> node,
    OptionalLong weightMicros,
    long earliestStartMicros,
    long earliestFinishMicros,
    long slackMicros) {

  public CriticalPathRow {
    if (ordinal < 0 || totalSteps < 0 || ordinal >= totalSteps) {
      throw new IllegalArgumentException("invalid path position " + ordinal + " of " + totalSteps);
    }
    Objects.requireNonNull(node, "node");
    Objects.requireNonNull(weightMicros, "weightMicros");
  }

  /** One-based position for display. */
  public int stepNumber() {
    return ordinal + 1;
  }

  /** The action-like name carried by aquery, even when this build never executed it. */
  public String displayName() {
    return node.map(GraphQueries.GraphNode::displayName)
        .orElse("graph node " + nodeIndex + " (details unavailable)");
  }

  public Optional<String> targetLabel() {
    return node.flatMap(GraphQueries.GraphNode::label);
  }

  public Optional<String> mnemonic() {
    return node.flatMap(GraphQueries.GraphNode::mnemonic);
  }

  public Optional<String> primaryOutput() {
    return node.flatMap(GraphQueries.GraphNode::primaryOutput);
  }

  public OptionalLong actionId() {
    return node.map(GraphQueries.GraphNode::actionId).orElseGet(OptionalLong::empty);
  }
}
