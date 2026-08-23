package com.holtherndon.bazelviz.ui.graph;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.analysis.GraphExtract;
import com.holtherndon.bazelviz.analysis.GraphLayout;
import com.holtherndon.bazelviz.core.graph.EdgeDerivation;
import com.holtherndon.bazelviz.graph.CsrBuilder;
import com.holtherndon.bazelviz.graph.CsrGraph;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What leaves the application, and what it says about itself.
 *
 * <p>An exported file outlives the window that explained it. These tests are
 * mostly about the provenance line for that reason: a drawing of three actions
 * from a build of thirty, saved without it, becomes a file that looks like a
 * build with three actions.
 */
final class GraphExportTest {

    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    @TempDir
    Path tempDir;

    private static CsrGraph chain(int nodes) {
        return CsrBuilder.build(nodes, visitor -> {
            for (int i = 0; i + 1 < nodes; i++) {
                visitor.edge(i, i + 1);
            }
        });
    }

    private static GraphModel truncatedModel() {
        // Three of thirty: an extraction that stopped at its budget.
        GraphExtract.Result extract = GraphExtract.dependencies(chain(30), 0, 10, 3);
        String[] labels = new String[30];
        long[] durations = new long[30];
        for (int i = 0; i < 30; i++) {
            labels[i] = "//pkg:target" + i;
            durations[i] = i == 1 ? GraphModel.UNKNOWN_DURATION : (i + 1) * 1_000L;
        }
        return GraphModel.of(
                new GraphLayoutService.Rendered(
                        GraphLayoutService.Request.around(EdgeDerivation.DECLARED, 0, 10),
                        extract, GraphLayout.layered(extract, RUNNING), null,
                        extract.describe()),
                labels, durations);
    }

    @Test
    @DisplayName("a DOT export carries the sentence that says how much of the build it is")
    void dotCarriesItsProvenance() throws IOException {
        GraphModel model = truncatedModel();

        GraphExport.Result result =
                GraphExport.visible(model, tempDir.resolve("visible"), GraphExport.Format.DOT);

        String dot = Files.readString(result.primary());
        assertThat(result.primary().getFileName().toString()).isEqualTo("visible.dot");
        assertThat(dot)
                .startsWith("// Visible graph.")
                .contains("from a graph of 30")
                .contains("digraph build");
        // The budget was hit, and the file has to say so as loudly as the view.
        assertThat(dot).contains("there is more beyond what is drawn");
    }

    @Test
    @DisplayName("an untimed action is exported as untimed, not as zero")
    void untimedSurvivesTheExport() throws IOException {
        GraphExport.Result result = GraphExport.visible(
                truncatedModel(), tempDir.resolve("visible"), GraphExport.Format.DOT);

        // Rule 11 all the way into the file. A "0 ms" here would be read as a
        // measurement by whatever opens it.
        assertThat(Files.readString(result.primary())).contains("(not timed)");
    }

    @Test
    @DisplayName("a CSV export writes nodes and edges, each with its provenance")
    void csvWritesBothFiles() throws IOException {
        GraphExport.Result result = GraphExport.visible(
                truncatedModel(), tempDir.resolve("visible.csv"), GraphExport.Format.CSV);

        assertThat(result.files()).hasSize(2);
        List<String> nodes = Files.readAllLines(result.files().get(0));
        List<String> edges = Files.readAllLines(result.files().get(1));
        assertThat(nodes.get(0)).startsWith("# Visible graph.").contains("from a graph of 30");
        assertThat(nodes.get(1)).isEqualTo("id,label,duration_micros");
        assertThat(edges.get(0)).startsWith("# Visible graph.");
        assertThat(edges.get(1)).isEqualTo("from,to");
        // Node 1 is the untimed one; its duration cell is empty, not zero.
        assertThat(nodes).anyMatch(line -> line.endsWith(",") && line.contains("target1"));
    }

    @Test
    @DisplayName("the complete export is not bounded by what could be drawn")
    void completeExportIgnoresTheDrawingLimit() throws IOException {
        String[] labels = new String[30];
        for (int i = 0; i < 30; i++) {
            labels[i] = "//pkg:target" + i;
        }

        GraphExport.Result result = GraphExport.whole(
                chain(30), labels, null, tempDir.resolve("all"), GraphExport.Format.CSV);

        // The drawing showed three. The export shows all thirty, which is what
        // makes the drawing limit a rendering decision rather than a data one.
        assertThat(result.nodes()).isEqualTo(30);
        assertThat(result.edges()).isEqualTo(29);
        assertThat(Files.readAllLines(result.files().get(0)))
                .hasSize(32)
                .first().asString().contains("all 30 actions");
        assertThat(Files.readAllLines(result.files().get(1))).hasSize(31);
    }

    @Test
    @DisplayName("a node the session never named exports as unnamed, not as blank")
    void unnamedNodesAreLabelled() throws IOException {
        GraphExport.Result result = GraphExport.whole(
                chain(3), new String[] {"//pkg:known", null, null}, null,
                tempDir.resolve("all"), GraphExport.Format.CSV);

        assertThat(Files.readString(result.files().get(0)))
                .contains("(name not recorded)")
                .contains("//pkg:known");
    }

    @Test
    @DisplayName("a label containing a comma or a quote survives the round trip")
    void csvQuoting() throws IOException {
        GraphExport.Result result = GraphExport.whole(
                chain(2), new String[] {"//a:with,comma", "//b:with\"quote"}, null,
                tempDir.resolve("odd"), GraphExport.Format.CSV);

        List<String> lines = Files.readAllLines(result.files().get(0));
        assertThat(lines).anyMatch(line -> line.contains("\"//a:with,comma\""));
        assertThat(lines).anyMatch(line -> line.contains("\"//b:with\"\"quote\""));
    }

    @Test
    @DisplayName("a label containing a quote survives DOT too")
    void dotQuoting() throws IOException {
        GraphExport.Result result = GraphExport.whole(
                chain(1), new String[] {"//a:with\"quote"}, null,
                tempDir.resolve("odd"), GraphExport.Format.DOT);

        assertThat(Files.readString(result.primary())).contains("//a:with\\\"quote");
    }

    @Test
    @DisplayName("nothing is left behind when the export finishes")
    void noTemporaryFilesRemain() throws IOException {
        GraphExport.visible(
                truncatedModel(), tempDir.resolve("visible"), GraphExport.Format.DOT);

        try (var entries = Files.list(tempDir)) {
            // Plan 10.4: written through a temporary file and renamed. A
            // leftover .partial would eventually be opened and believed.
            assertThat(entries.map(path -> path.getFileName().toString()))
                    .noneMatch(name -> name.contains("partial"));
        }
    }

    @Test
    @DisplayName("exporting over an existing file replaces it")
    void exportsAreRepeatable() throws IOException {
        Path target = tempDir.resolve("visible.dot");
        GraphExport.visible(truncatedModel(), target, GraphExport.Format.DOT);
        long first = Files.size(target);

        GraphExport.Result second = GraphExport.whole(
                chain(30), null, null, target, GraphExport.Format.DOT);

        assertThat(Files.size(second.primary())).isNotEqualTo(first);
        assertThat(Files.readString(second.primary())).contains("Complete graph");
    }

    @Test
    @DisplayName("the result describes what was written in words a user can check")
    void resultDescribesItself() throws IOException {
        GraphExport.Result result = GraphExport.visible(
                truncatedModel(), tempDir.resolve("visible"), GraphExport.Format.DOT);

        assertThat(result.describe())
                .contains("Wrote 3 actions")
                .contains("visible.dot");
    }

    @Test
    @DisplayName("both formats are offered by name, not by enum constant")
    void formatsAreWorded() {
        for (GraphExport.Format format : GraphExport.Format.values()) {
            assertThat(format.displayName()).isNotBlank().doesNotContain("_");
            assertThat(format.extension()).startsWith(".");
        }
    }
}
