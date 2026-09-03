package com.holtherndon.bazelviz.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.holtherndon.bazelviz.graph.CsrBuilder;
import com.holtherndon.bazelviz.graph.CsrGraph;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The far-zoom view, and the arithmetic that makes it trustworthy.
 *
 * <p>An aggregation is only worth drawing if its parts add up to the whole. Two tests here assert
 * exactly that — cluster node counts sum to the graph's node count, and internal plus between edges
 * sum to its edge count — because a clustering that quietly dropped a node would look completely
 * correct.
 */
final class GraphClusteringTest {

  private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

  /** Six actions across two packages, wired both within and across. */
  private static CsrGraph sixNodes() {
    return CsrBuilder.build(
        6,
        visitor -> {
          visitor.edge(0, 1); // inside //a
          visitor.edge(1, 2); // inside //a
          visitor.edge(2, 3); // //a -> //b
          visitor.edge(0, 4); // //a -> //b
          visitor.edge(3, 4); // inside //b
          visitor.edge(4, 5); // inside //b
        });
  }

  private static String[] twoPackages() {
    return new String[] {
      "//a:one", "//a:two", "//a:three", "//b:one", "//b:two", "//b:three",
    };
  }

  private static String[] packagesOf(String[] labels) {
    String[] out = new String[labels.length];
    for (int i = 0; i < labels.length; i++) {
      out[i] = GraphClustering.packageOf(labels[i]);
    }
    return out;
  }

  @Test
  @DisplayName("a label's package is everything before the last colon")
  void packageDerivation() {
    assertThat(GraphClustering.packageOf("//src/main/java:lib")).isEqualTo("//src/main/java");
    // Shorthand: //src/main means //src/main:main, and is its own package.
    assertThat(GraphClustering.packageOf("//src/main")).isEqualTo("//src/main");
    assertThat(GraphClustering.packageOf("//:root")).isEqualTo("//");
    assertThat(GraphClustering.packageOf(null)).isNull();
    assertThat(GraphClustering.packageOf("  ")).isNull();
  }

  @Test
  @DisplayName("an external repository keeps its prefix rather than merging with the main one")
  void externalRepositoriesStaySeparate() {
    // @rules_java//java and //java are two different packages; a clustering
    // that merged them would invent a group that does not exist.
    assertThat(GraphClustering.packageOf("@rules_java//java:defs"))
        .isEqualTo("@rules_java//java")
        .isNotEqualTo(GraphClustering.packageOf("//java:defs"));
  }

  @Test
  @DisplayName("every node lands in exactly one cluster, and they sum to the graph")
  void everyNodeIsAccountedFor() {
    GraphClustering.Result result =
        GraphClustering.cluster(
            sixNodes(), packagesOf(twoPackages()), GraphClustering.By.PACKAGE, 100, RUNNING);

    assertThat(result.clusters()).hasSize(2);
    // Rule 12: nothing silently dropped. If this ever fails, a node went
    // missing between the graph and the drawing.
    assertThat(result.clusteredNodes()).isEqualTo(result.totalNodes()).isEqualTo(6);
  }

  @Test
  @DisplayName("every edge is counted, inside a cluster or between two")
  void everyEdgeIsAccountedFor() {
    GraphClustering.Result result =
        GraphClustering.cluster(
            sixNodes(), packagesOf(twoPackages()), GraphClustering.By.PACKAGE, 100, RUNNING);

    assertThat(result.clusteredEdges()).isEqualTo(result.totalEdges()).isEqualTo(6);
    // Four inside (0->1, 1->2, 3->4, 4->5) and two across (2->3, 0->4).
    assertThat(result.clusters().get(0).internalEdges()).isEqualTo(2);
    assertThat(result.clusters().get(1).internalEdges()).isEqualTo(2);
    assertThat(result.edges()).hasSize(1);
    assertThat(result.edges().get(0).weight()).isEqualTo(2);
  }

  @Test
  @DisplayName("parallel dependencies between two packages become one weighted edge")
  void edgesAreAggregatedNotListed() {
    GraphClustering.Result result =
        GraphClustering.cluster(
            sixNodes(), packagesOf(twoPackages()), GraphClustering.By.PACKAGE, 100, RUNNING);

    // Two real edges cross //a -> //b. Drawing two lines between the same
    // pair of boxes says nothing; one line labelled 2 says how coupled they
    // are.
    GraphClustering.ClusterEdge across = result.edges().get(0);
    assertThat(across.from()).isEqualTo(0);
    assertThat(across.to()).isEqualTo(1);
    assertThat(across.weight()).isEqualTo(2);
  }

  @Test
  @DisplayName("a node with no name goes into a cluster that says so")
  void unknownIsVisiblyUnknown() {
    String[] keys = packagesOf(twoPackages());
    keys[5] = null;

    GraphClustering.Result result =
        GraphClustering.cluster(sixNodes(), keys, GraphClustering.By.PACKAGE, 100, RUNNING);

    GraphClustering.Cluster unknown = result.clusters().get(result.clusters().size() - 1);
    // Plan 11.4: unknown must be visibly unknown, not an empty-named group
    // that reads as a real package.
    assertThat(unknown.isUnknown()).isTrue();
    assertThat(unknown.key()).isNull();
    assertThat(unknown.displayName()).isEqualTo("(name not recorded)");
    assertThat(unknown.nodeCount()).isEqualTo(1);
    assertThat(result.clusteredNodes()).isEqualTo(6);
  }

  @Test
  @DisplayName("no unknown cluster appears when nothing is unknown")
  void noEmptyUnknownCluster() {
    GraphClustering.Result result =
        GraphClustering.cluster(
            sixNodes(), packagesOf(twoPackages()), GraphClustering.By.PACKAGE, 100, RUNNING);

    // An always-present empty box would suggest a gap that is not there.
    assertThat(result.clusters()).noneMatch(GraphClustering.Cluster::isUnknown);
  }

  @Test
  @DisplayName("the same graph clusters into the same picture every time")
  void clusteringIsDeterministic() {
    String[] keys = packagesOf(twoPackages());

    GraphClustering.Result first =
        GraphClustering.cluster(sixNodes(), keys, GraphClustering.By.PACKAGE, 100, RUNNING);
    GraphClustering.Result second =
        GraphClustering.cluster(sixNodes(), keys, GraphClustering.By.PACKAGE, 100, RUNNING);

    // Ordinals follow the keys' sort order and edges are sorted before
    // return, so neither TreeMap nor HashMap iteration can leak through.
    assertThat(second.clusters()).isEqualTo(first.clusters());
    assertThat(second.edges()).isEqualTo(first.edges());
  }

  @Test
  @DisplayName("too many clusters is refused with the exact count, not truncated")
  void tooManyClustersIsRefused() {
    String[] everyNodeItsOwn = twoPackages();

    GraphClustering.Result result =
        GraphClustering.cluster(sixNodes(), everyNodeItsOwn, GraphClustering.By.TARGET, 3, RUNNING);

    assertThat(result.hitLimit()).isTrue();
    assertThat(result.clusters()).isEmpty();
    assertThat(result.clusterCount()).isEqualTo(6);
    assertThat(result.describe())
        .contains("6 groups")
        .contains("Nothing is hidden")
        .contains("coarser");
  }

  @Test
  @DisplayName("clustering by mnemonic collapses a build to a handful of groups")
  void mnemonicClustering() {
    String[] mnemonics = {"Javac", "Javac", "Javac", "CppCompile", "CppCompile", "Javac"};

    GraphClustering.Result result =
        GraphClustering.cluster(sixNodes(), mnemonics, GraphClustering.By.MNEMONIC, 100, RUNNING);

    // The reason mnemonic clustering is the escape hatch when packages are
    // too many: a build has thousands of packages and dozens of mnemonics.
    assertThat(result.clusters()).hasSize(2);
    assertThat(result.clusters().get(0).key()).isEqualTo("CppCompile");
    assertThat(result.clusters().get(1).key()).isEqualTo("Javac");
    assertThat(result.describe()).startsWith("Mnemonic: 2 groups covering all 6 actions");
  }

  @Test
  @DisplayName("clusters can be laid out like any other subgraph")
  void clustersBecomeADrawableSubgraph() {
    GraphClustering.Result result =
        GraphClustering.cluster(
            sixNodes(), packagesOf(twoPackages()), GraphClustering.By.PACKAGE, 100, RUNNING);

    GraphExtract.Result extract = result.asExtract();
    assertThat(extract.mode()).isEqualTo(GraphExtract.Mode.CLUSTERS);
    assertThat(extract.nodes()).containsExactly(0, 1);
    // And the totals it carries are the real graph's, not the cluster
    // count -- plan 13.6's "exact totals remain visible" survives the
    // aggregation.
    assertThat(extract.totalNodes()).isEqualTo(6);

    GraphLayout.Result layout =
        GraphLayout.run(GraphLayout.defaultFor(extract.mode()), extract, RUNNING);
    assertThat(layout.kind()).isEqualTo(GraphLayout.Kind.GRID);
    assertThat(layout.size()).isEqualTo(2);
  }

  @Test
  @DisplayName("a cancelled clustering groups nothing rather than some of it")
  void cancellationIsClean() {
    GraphClustering.Result result =
        GraphClustering.cluster(
            sixNodes(),
            packagesOf(twoPackages()),
            GraphClustering.By.PACKAGE,
            100,
            new AtomicBoolean(true));

    assertThat(result.cancelled()).isTrue();
    assertThat(result.clusters()).isEmpty();
    assertThat(result.hitLimit()).isFalse();
  }

  @Test
  @DisplayName("keys that do not cover the graph are an error, not a silent unknown")
  void shortKeyArrayIsAnError() {
    assertThatThrownBy(
            () ->
                GraphClustering.cluster(
                    sixNodes(), new String[] {"//a:one"}, GraphClustering.By.PACKAGE, 100, RUNNING))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("keys cover 1 nodes but the graph has 6");
  }

  @Test
  @DisplayName("an empty graph clusters into nothing without failing")
  void emptyGraph() {
    GraphClustering.Result result =
        GraphClustering.cluster(
            CsrBuilder.build(0, visitor -> {}),
            new String[0],
            GraphClustering.By.PACKAGE,
            100,
            RUNNING);

    assertThat(result.clusters()).isEmpty();
    assertThat(result.cancelled()).isFalse();
    assertThat(result.hitLimit()).isFalse();
  }

  @Test
  @DisplayName("every grouping has a name, and none renders as an enum constant")
  void everyGroupingIsWorded() {
    for (GraphClustering.By by : GraphClustering.By.values()) {
      assertThat(by.displayName()).as("%s", by).isNotBlank().doesNotContain("_");
    }
  }
}
