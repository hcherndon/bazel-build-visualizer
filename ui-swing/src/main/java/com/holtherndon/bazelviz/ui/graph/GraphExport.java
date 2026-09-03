package com.holtherndon.bazelviz.ui.graph;

import com.holtherndon.bazelviz.graph.CsrGraph;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * Writes a graph to a file the user can take elsewhere.
 *
 * <h2>Two exports, because they answer different questions</h2>
 *
 * <p>Plan 13.6 offers "export" as one of the three things a user may do when a
 * graph is too large to draw, and the Phase 7 task list asks for "export of
 * visible and complete filtered graphs". {@link #visible} writes every node in
 * the current extracted view and every dependency among those nodes, including
 * cross-links hidden only by drawing detail. {@link #whole} writes every node
 * and edge the session holds, whether or not any of it could be extracted for
 * the canvas. The second is the one that makes the limit acceptable: the
 * drawing is bounded, the data never is.
 *
 * <h2>Streamed, never materialised</h2>
 *
 * <p>{@link #whole} walks the CSR index and writes as it goes, so exporting a
 * five-million-edge graph costs a buffer rather than a heap. Rule 12's
 * prohibition on silent truncation is easy to honour when nothing is held.
 *
 * <h2>Every file says what it is</h2>
 *
 * <p>An exported file outlives the window that explains it. A drawing of nine
 * actions from a build of ninety thousand, saved without that sentence, becomes
 * a file that looks like a build with nine actions — plan 13.6's "never claim
 * the omitted nodes do not exist", one step further out than the plan states it.
 * So every export carries its provenance in the file: as a comment in DOT, and
 * as a leading {@code #} line in each CSV. The {@code #} line is a deliberate
 * trade — a few strict CSV readers will need it skipped, which is a smaller
 * problem than a file that misrepresents a build.
 *
 * <h2>Written through a temporary file</h2>
 *
 * <p>Plan 10.4: "export through a temporary file, then atomically rename". An
 * export interrupted halfway leaves no file at all rather than a plausible
 * truncated one.
 */
public final class GraphExport {

    private GraphExport() {}

    /** What an export is written as. */
    public enum Format {
        /** Graphviz. One file, opens in anything, keeps its provenance comment. */
        DOT("Graphviz (.dot)", ".dot"),
        /** Two files, nodes and edges; the shape Gephi and pandas expect. */
        CSV("CSV (nodes and edges)", ".csv");

        private final String displayName;
        private final String extension;

        Format(String displayName, String extension) {
            this.displayName = displayName;
            this.extension = extension;
        }

        public String displayName() {
            return displayName;
        }

        public String extension() {
            return extension;
        }
    }

    /** What was written, and where. */
    public record Result(
            List<Path> files, long nodes, long edges, String summary, String nodeNoun) {

        public Result {
            files = List.copyOf(files);
        }

        public Path primary() {
            return files.get(0);
        }

        /** The sentence the view shows after an export. */
        public String describe() {
            String where = files.size() == 1
                    ? primary().getFileName().toString()
                    : files.size() + " files beside " + primary().getFileName();
            return "Wrote " + nodes + " " + plural(nodeNoun, nodes) + " and "
                    + edges + (edges == 1 ? " dependency to " : " dependencies to ") + where + ".";
        }
    }

    /**
     * Writes the current extracted node set and all dependencies among it.
     *
     * <p>Including its description, which is the part that says how much of the
     * build this is. Paint-only detail such as hierarchy cross-link decluttering
     * is deliberately reversed in the file, and the provenance says so: export
     * should not turn a readability choice into data loss.
     *
     * <p>The node-name column is headed {@code name}, not {@code label},
     * because it holds what the canvas draws — for action graphs that is the
     * per-action "Mnemonic — output basename", which is not a Bazel label.
     * {@link #whole} writes real target labels and keeps the {@code label}
     * header; two different contents under one header would make one of the
     * two files a lie.
     */
    public static Result visible(GraphModel model, Path target, Format format)
            throws IOException {
        String provenance = "Visible graph. Includes all dependencies among these nodes,"
                + " including links hidden only by drawing detail. " + model.description();
        int nodes = model.size();
        int[][] edges = model.edgePositions();

        if (format == Format.DOT) {
            Path file = withExtension(target, format);
            writeAtomically(file, out -> {
                dotHeader(out, provenance);
                for (int i = 0; i < nodes; i++) {
                    dotNode(out, i, model.canvasLabelAt(i),
                            model.durationAt(i).isPresent()
                                    ? model.durationAt(i).getAsLong() : -1);
                }
                for (int e = 0; e < edges[0].length; e++) {
                    out.write("  n" + edges[0][e] + " -> n" + edges[1][e] + ";\n");
                }
                out.write("}\n");
            });
            return new Result(
                    List.of(file), nodes, edges[0].length, provenance, model.nodeNoun());
        }

        Path nodeFile = sibling(target, "-nodes.csv");
        Path edgeFile = sibling(target, "-edges.csv");
        writeAtomically(nodeFile, out -> {
            out.write("# " + provenance + "\n");
            // "name", not "label": this is the drawn display name, which for
            // an action graph is "Mnemonic — output basename" rather than a
            // Bazel label. The complete export is the one that writes labels.
            out.write("id,name,duration_micros\n");
            for (int i = 0; i < nodes; i++) {
                out.write(i + "," + csv(model.canvasLabelAt(i)) + ","
                        + (model.durationAt(i).isPresent()
                                ? Long.toString(model.durationAt(i).getAsLong()) : "")
                        + "\n");
            }
        });
        writeAtomically(edgeFile, out -> {
            out.write("# " + provenance + "\n");
            out.write("from,to\n");
            for (int e = 0; e < edges[0].length; e++) {
                out.write(edges[0][e] + "," + edges[1][e] + "\n");
            }
        });
        return new Result(
                List.of(nodeFile, edgeFile), nodes, edges[0].length,
                provenance, model.nodeNoun());
    }

    /**
     * Writes every node and edge in the session's graph.
     *
     * <p>Streamed straight from the CSR index, so the cost is the file rather
     * than the heap and there is no limit to hit. This is what makes the
     * drawing limit a rendering decision instead of a data one.
     *
     * @param labelsByNodeIndex names, indexed by node; nulls become the same
     *     "(name not recorded)" the drawing uses rather than an empty cell that
     *     would read as a real blank name
     */
    public static Result whole(
            CsrGraph forward,
            String[] labelsByNodeIndex,
            long[] durationsByNodeIndex,
            Path target,
            Format format)
            throws IOException {
        return whole(
                forward, labelsByNodeIndex, durationsByNodeIndex,
                target, format, "action");
    }

    /** Complete export with the graph source's truthful node noun. */
    public static Result whole(
            CsrGraph forward,
            String[] labelsByNodeIndex,
            long[] durationsByNodeIndex,
            Path target,
            Format format,
            String nodeNoun)
            throws IOException {
        long nodes = forward.nodeCount();
        long edges = forward.edgeCount();
        String provenance = "Complete graph: all " + nodes + " "
                + plural(nodeNoun, nodes) + " and " + edges
                + " dependencies this session holds, drawn or not.";

        if (format == Format.DOT) {
            Path file = withExtension(target, format);
            writeAtomically(file, out -> {
                dotHeader(out, provenance);
                for (int node = 0; node < nodes; node++) {
                    dotNode(out, node, nameOf(labelsByNodeIndex, node),
                            durationOf(durationsByNodeIndex, node));
                }
                for (int node = 0; node < nodes; node++) {
                    int from = node;
                    forward.forEachNeighbor(node, to -> {
                        try {
                            out.write("  n" + from + " -> n" + to + ";\n");
                        } catch (IOException failure) {
                            throw new java.io.UncheckedIOException(failure);
                        }
                    });
                }
                out.write("}\n");
            });
            return new Result(List.of(file), nodes, edges, provenance, nodeNoun);
        }

        Path nodeFile = sibling(target, "-nodes.csv");
        Path edgeFile = sibling(target, "-edges.csv");
        writeAtomically(nodeFile, out -> {
            out.write("# " + provenance + "\n");
            out.write("id,label,duration_micros\n");
            for (int node = 0; node < nodes; node++) {
                long duration = durationOf(durationsByNodeIndex, node);
                out.write(node + "," + csv(nameOf(labelsByNodeIndex, node)) + ","
                        + (duration < 0 ? "" : Long.toString(duration)) + "\n");
            }
        });
        writeAtomically(edgeFile, out -> {
            out.write("# " + provenance + "\n");
            out.write("from,to\n");
            for (int node = 0; node < nodes; node++) {
                int from = node;
                forward.forEachNeighbor(node, to -> {
                    try {
                        out.write(from + "," + to + "\n");
                    } catch (IOException failure) {
                        throw new java.io.UncheckedIOException(failure);
                    }
                });
            }
        });
        return new Result(List.of(nodeFile, edgeFile), nodes, edges, provenance, nodeNoun);
    }

    private static String plural(String noun, long count) {
        String safe = noun == null || noun.isBlank() ? "node" : noun;
        return count == 1 ? safe : safe + "s";
    }

    private static String nameOf(String[] labels, int node) {
        String label = labels != null && node < labels.length ? labels[node] : null;
        // The same wording the drawing uses. An empty cell would read as a real
        // blank name rather than as a name nobody recorded.
        return label == null ? "(name not recorded)" : label;
    }

    private static long durationOf(long[] durations, int node) {
        return durations != null && node < durations.length ? durations[node] : -1;
    }

    private static void dotHeader(Writer out, String provenance) throws IOException {
        out.write("// " + provenance + "\n");
        out.write("digraph build {\n");
        out.write("  graph [rankdir=LR, label=" + dotQuote(provenance) + ", labelloc=t];\n");
        out.write("  node [shape=box, fontsize=9];\n");
    }

    private static void dotNode(Writer out, int id, String label, long durationMicros)
            throws IOException {
        String text = durationMicros < 0
                // Rule 11 all the way into the exported file: not timed is not
                // zero, and must not be written as one.
                ? label + "\\n(not timed)"
                : label + "\\n" + durationMicros / 1_000 + " ms";
        out.write("  n" + id + " [label=" + dotQuote(text) + "];\n");
    }

    private static String dotQuote(String text) {
        return '"' + text.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    /**
     * RFC 4180 quoting, from the one place it is written.
     *
     * <p>It was four lines here and four lines in the table export, which is
     * exactly the size at which duplication looks harmless and stops being
     * checked.
     */
    private static String csv(String value) {
        return com.holtherndon.bazelviz.core.text.Csv.field(value);
    }

    private static Path withExtension(Path target, Format format) {
        String name = target.getFileName().toString();
        return name.endsWith(format.extension())
                ? target
                : target.resolveSibling(name + format.extension());
    }

    private static Path sibling(Path target, String suffix) {
        String name = target.getFileName().toString();
        if (name.endsWith(".csv")) {
            name = name.substring(0, name.length() - 4);
        }
        return target.resolveSibling(name + suffix);
    }

    /** What writes into the temporary file. */
    private interface Body {
        void writeTo(Writer out) throws IOException;
    }

    /**
     * Writes through a temporary file and renames.
     *
     * <p>Plan 10.4. An export interrupted halfway leaves nothing rather than a
     * plausible truncated file, which is the failure that would be believed.
     */
    private static void writeAtomically(Path target, Body body) throws IOException {
        Path directory = target.toAbsolutePath().getParent();
        Files.createDirectories(directory);
        Path temporary = Files.createTempFile(directory, ".bbv-export", ".partial");
        try {
            try (BufferedWriter out = Files.newBufferedWriter(
                    temporary, StandardCharsets.UTF_8)) {
                body.writeTo(out);
            } catch (java.io.UncheckedIOException unwrapped) {
                throw unwrapped.getCause();
            }
            try {
                Files.move(temporary, target,
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException notAtomic) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
