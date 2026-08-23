package com.holtherndon.bazelviz.enrich.graph;

import com.google.devtools.build.lib.analysis.AnalysisProtosV2.Action;
import com.google.devtools.build.lib.analysis.AnalysisProtosV2.Artifact;
import com.google.devtools.build.lib.analysis.AnalysisProtosV2.Configuration;
import com.google.devtools.build.lib.analysis.AnalysisProtosV2.DepSetOfFiles;
import com.google.devtools.build.lib.analysis.AnalysisProtosV2.PathFragment;
import com.google.devtools.build.lib.analysis.AnalysisProtosV2.RuleClass;
import com.google.devtools.build.lib.analysis.AnalysisProtosV2.Target;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.ExtensionRegistryLite;
import com.google.protobuf.WireFormat;
import java.io.IOException;
import java.io.InputStream;

/**
 * Reads an {@code aquery --output=proto} file one top-level sub-message at a
 * time.
 *
 * <h2>Why not {@code ActionGraphContainer.parseFrom}</h2>
 *
 * <p>The file is a single {@code ActionGraphContainer} message — there is no
 * record framing and no message boundary between entities (finding Q2 in
 * {@code docs/aquery-and-cquery.md}). Parsing it whole would hold the entire
 * graph, and protobuf-java refuses any message above 2 GB regardless of the
 * heap, so on a large build {@code parseFrom} does not merely use a lot of
 * memory: it fails.
 *
 * <p>So the container is never materialised. This reads the top-level tags with
 * a {@code CodedInputStream} and hands each sub-message to the visitor as it
 * arrives, holding one at a time.
 *
 * <h2>Order guarantees, of which there are none</h2>
 *
 * <p>The top-level fields are interleaved — 57 to 71 runs of consecutive
 * same-kind entries for a six-target workspace (Q3) — and entities are
 * referenced before they are declared on three of the four supported versions
 * (Q4). A visitor therefore may not resolve any reference while reading. That
 * is why {@link ActionGraphVisitor} has no resolution methods and why the
 * importer stages everything first.
 */
public final class ActionGraphReader {

    /**
     * Protobuf's default 64 MB read limit would refuse a real graph. The
     * limit exists to stop a hostile message allocating without bound, which
     * this does not do: the stream is consumed sub-message by sub-message and
     * only one is ever held.
     */
    private static final int SIZE_LIMIT = Integer.MAX_VALUE;

    private final ActionGraphVisitor visitor;

    public ActionGraphReader(ActionGraphVisitor visitor) {
        this.visitor = visitor;
    }

    /**
     * Reads the whole file, calling {@code visitor} for every entity.
     *
     * @return how many top-level entities were seen
     */
    public long read(InputStream stream) throws IOException {
        CodedInputStream in = CodedInputStream.newInstance(stream);
        in.setSizeLimit(SIZE_LIMIT);
        ExtensionRegistryLite registry = ExtensionRegistryLite.getEmptyRegistry();
        long seen = 0;

        while (true) {
            int tag = in.readTag();
            if (tag == 0) {
                break;
            }
            switch (WireFormat.getTagFieldNumber(tag)) {
                case 1 -> visitor.artifact(in.readMessage(Artifact.parser(), registry));
                case 2 -> visitor.action(in.readMessage(Action.parser(), registry));
                case 3 -> visitor.target(in.readMessage(Target.parser(), registry));
                case 4 -> visitor.depSet(in.readMessage(DepSetOfFiles.parser(), registry));
                case 5 -> visitor.configuration(in.readMessage(Configuration.parser(), registry));
                // Aspect descriptors were empty in every probe run, so nothing
                // is known about how aspect-generated actions appear. Skipped
                // rather than half-handled, and recorded as unmeasured in the
                // ground truth.
                case 6 -> in.skipField(tag);
                case 7 -> visitor.ruleClass(in.readMessage(RuleClass.parser(), registry));
                case 8 -> visitor.pathFragment(in.readMessage(PathFragment.parser(), registry));
                default -> in.skipField(tag);
            }
            seen++;
        }
        return seen;
    }
}
