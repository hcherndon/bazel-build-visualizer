package com.holtherndon.bazelviz.capture.file.export;

import com.google.protobuf.CodedOutputStream;
import com.holtherndon.bazelviz.bepcodec.BesEnvelope;
import com.holtherndon.bazelviz.bepcodec.BesEnvelopeDecoder;
import com.holtherndon.bazelviz.capture.file.json.JsonBuildEventDecoder;
import com.holtherndon.bazelviz.capture.file.json.JsonDecodeResult;
import com.holtherndon.bazelviz.format.journal.JournalFrame;
import com.holtherndon.bazelviz.format.journal.JournalReader;
import com.holtherndon.bazelviz.format.journal.JournalReaderConfig;
import com.holtherndon.bazelviz.format.journal.JournalSegments;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Writes the captured stream back out as a binary BEP file (plan 10.5).
 *
 * <h2>The point of this export</h2>
 *
 * <p>It is what makes a captured session portable to <em>other</em> tooling. A
 * {@code .bviz} archive is only readable here; a length-delimited BEP file is
 * what {@code --build_event_binary_file} produces, so anything that reads
 * Bazel's own output reads this. That is why it comes out of the raw journal
 * rather than out of the database: the database is this application's reading
 * of the stream, and the journal is the stream.
 *
 * <h2>What is preserved, and what is not</h2>
 *
 * <p>Plan 10.5: "preserve original serialized payload bytes where possible".
 * For a live capture that is exact — a BES envelope carries the inner
 * {@code BuildEvent} as an opaque byte string, so the export copies those bytes
 * without decoding or re-serialising them, and a byte-for-byte comparison with
 * what Bazel sent will match.
 *
 * <p>For a session imported from a <em>JSON</em> BEP file it cannot be: there
 * were never any binary bytes. Those records are decoded and re-encoded, which
 * is a faithful message and not the original bytes — protobuf does not promise
 * that two encoders agree on field order or on how they write a default. The
 * result counts them separately, under their own name, because "10,000 events
 * exported" would otherwise conceal that some of them went through a
 * conversion.
 *
 * <h2>Everything that is not a BuildEvent is excluded</h2>
 *
 * <p>Plan 10.5 asks for lifecycle envelopes to be excluded, and the same
 * applies to console-output envelopes and to the stream terminator: a BEP file
 * is a sequence of {@code BuildEvent} messages, and a reader that met a
 * {@code PublishLifecycleEventRequest} in one would fail to parse it. Each kind
 * is counted so the export can say what it left out.
 *
 * <h2>A partial session exports partially, and says so</h2>
 *
 * <p>Plan 10.5: "allows partial export from incomplete sessions" and "reports
 * any missing sequence ranges". A capture killed mid-build has a journal that
 * simply stops, and a BES stream that lost a message has a gap in its sequence
 * numbers. Both produce a usable file and a result that names what is missing —
 * which is the difference between a short file and a short file somebody knows
 * is short.
 */
public final class BepStreamExport {

    private BepStreamExport() {}

    /** Bytes of any single envelope this will decode. Matches the capture path's own bound. */
    private static final int MAX_MESSAGE_BYTES = 512 * 1024 * 1024;

    private static final int BUFFER_BYTES = 256 * 1024;

    /**
     * What an export produced.
     *
     * @param eventsPreserved events written with their original serialized
     *     bytes, unchanged
     * @param eventsReencoded events that had no binary form and were converted
     *     from JSON, which is a faithful message and not the original bytes
     * @param lifecycleSkipped BES lifecycle requests, which carry no BuildEvent
     * @param otherEnvelopesSkipped console output, the stream terminator, and
     *     anything a newer Bazel added that this build does not model
     * @param undecodable frames whose bytes would not decode; they stay in the
     *     journal, and this file does not contain them
     * @param gaps sequence ranges the journal never held
     */
    public record Result(
            Path file,
            long eventsPreserved,
            long eventsReencoded,
            long lifecycleSkipped,
            long otherEnvelopesSkipped,
            long undecodable,
            List<SequenceGap> gaps,
            long bytesWritten) {

        public Result {
            gaps = List.copyOf(gaps);
        }

        /** Events in the file, however they got there. */
        public long events() {
            return eventsPreserved + eventsReencoded;
        }

        /** True when nothing was lost between the journal and this file. */
        public boolean isComplete() {
            return gaps.isEmpty() && undecodable == 0;
        }

        /** What has to be said alongside the file. */
        public String describe() {
            StringBuilder text = new StringBuilder()
                    .append(events()).append(" events written, ")
                    .append(bytesWritten).append(" bytes.");
            if (eventsReencoded > 0) {
                text.append(' ').append(eventsReencoded)
                        .append(" of them had no binary form in this session and were converted"
                                + " from JSON: a faithful message, not the original bytes.");
            }
            if (lifecycleSkipped > 0 || otherEnvelopesSkipped > 0) {
                text.append(' ').append(lifecycleSkipped + otherEnvelopesSkipped)
                        .append(" envelopes carried no BuildEvent and are not in a BEP file by"
                                + " definition; they remain in the raw journal.");
            }
            if (undecodable > 0) {
                text.append(' ').append(undecodable)
                        .append(" frames would not decode and are absent from this file. They are"
                                + " still in the journal.");
            }
            if (!gaps.isEmpty()) {
                long missing = gaps.stream().mapToLong(SequenceGap::missing).sum();
                text.append(' ').append("The capture is missing ").append(missing)
                        .append(" events across ").append(gaps.size())
                        .append(gaps.size() == 1 ? " gap" : " gaps")
                        .append("; this file is short by that much and cannot be made whole.");
            }
            if (isComplete()) {
                text.append(" Nothing was lost between the journal and this file.");
            }
            return text.toString();
        }
    }

    /** A run of sequence numbers the journal does not contain. */
    public record SequenceGap(long afterSequence, long beforeSequence) {

        public SequenceGap {
            if (beforeSequence <= afterSequence + 1) {
                throw new IllegalArgumentException(
                        "not a gap: " + afterSequence + " to " + beforeSequence);
            }
        }

        public long missing() {
            return beforeSequence - afterSequence - 1;
        }
    }

    /**
     * Streams the journal into a length-delimited binary BEP file.
     *
     * <p>One frame at a time, never the whole session: plan 24's Phase 9 exit
     * criterion is that "export does not require loading the entire session
     * into memory", and a Tier 3 journal is gigabytes.
     *
     * @param rawDirectory the session's {@code raw/} directory
     * @param target where the file lands, written through a temporary file
     *     beside it so an interrupted export leaves nothing that looks finished
     */
    public static Result write(Path rawDirectory, Path target) throws IOException {
        Objects.requireNonNull(rawDirectory, "rawDirectory");
        Objects.requireNonNull(target, "target");
        List<Integer> segments = JournalSegments.listSegmentIndexes(rawDirectory);
        if (segments.isEmpty()) {
            throw new IOException("no journal segments under " + rawDirectory
                    + ", so there is no captured stream to export");
        }
        Path parent = target.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path partial = target.resolveSibling(target.getFileName() + ".partial");
        Files.deleteIfExists(partial);

        Counters counters = new Counters();
        boolean ok = false;
        try {
            try (OutputStream raw = Files.newOutputStream(partial);
                    OutputStream out = new BufferedOutputStream(raw, BUFFER_BYTES)) {
                BesEnvelopeDecoder envelopes = new BesEnvelopeDecoder(MAX_MESSAGE_BYTES);
                JsonBuildEventDecoder json = new JsonBuildEventDecoder();
                for (int segment : segments) {
                    exportSegment(rawDirectory, segment, envelopes, json, out, counters);
                }
            }
            Files.move(partial, target,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            ok = true;
        } finally {
            if (!ok) {
                Files.deleteIfExists(partial);
            }
        }
        return new Result(
                target, counters.preserved, counters.reencoded, counters.lifecycle,
                counters.otherEnvelopes, counters.undecodable, counters.gaps,
                Files.size(target));
    }

    private static void exportSegment(
            Path rawDirectory,
            int segment,
            BesEnvelopeDecoder envelopes,
            JsonBuildEventDecoder json,
            OutputStream out,
            Counters counters) throws IOException {
        Path file = JournalSegments.segmentFile(rawDirectory, segment);
        try (JournalReader reader = JournalReader.open(
                file, JournalReaderConfig.defaults())) {
            JournalFrame frame;
            while ((frame = reader.next()) != null) {
                counters.noteSequence(frame.header().sequence());
                byte[] payload = frame.requirePayload();
                switch (frame.header().sourceKind()) {
                    case BEP_BINARY -> {
                        writeDelimited(out, payload, 0, payload.length);
                        counters.preserved++;
                    }
                    case BES_ENVELOPE -> exportEnvelope(envelopes, payload, out, counters);
                    case BES_LIFECYCLE -> counters.lifecycle++;
                    case BEP_JSON_RECORD -> exportJsonRecord(json, payload, out, counters);
                }
            }
        }
    }

    private static void exportEnvelope(
            BesEnvelopeDecoder decoder, byte[] payload, OutputStream out, Counters counters)
            throws IOException {
        BesEnvelopeDecoder.Result result =
                decoder.decodeToolEvent(payload, 0, payload.length);
        if (result.isFailed() || result.envelope().isEmpty()) {
            counters.undecodable++;
            return;
        }
        BesEnvelope envelope = result.envelope().orElseThrow();
        if (!envelope.kind().carriesBuildEvent()) {
            if (envelope.kind() == BesEnvelope.Kind.LIFECYCLE) {
                counters.lifecycle++;
            } else {
                counters.otherEnvelopes++;
            }
            return;
        }
        // The inner event is an opaque byte string in the envelope, so these
        // are Bazel's own bytes rather than a re-serialisation of them.
        byte[] bytes = envelope.bazelEventBytes().orElseThrow().toByteArray();
        writeDelimited(out, bytes, 0, bytes.length);
        counters.preserved++;
    }

    private static void exportJsonRecord(
            JsonBuildEventDecoder decoder, byte[] payload, OutputStream out, Counters counters)
            throws IOException {
        JsonDecodeResult result = decoder.decode(payload);
        if (result.decodedEvent().isEmpty()) {
            counters.undecodable++;
            return;
        }
        byte[] bytes = result.decodedEvent().orElseThrow().toByteArray();
        writeDelimited(out, bytes, 0, bytes.length);
        counters.reencoded++;
    }

    /**
     * Writes one message with a varint length prefix.
     *
     * <p>The framing {@code --build_event_binary_file} uses, and the framing
     * {@code parseDelimitedFrom} expects — which is what makes this file
     * readable by tooling that has never heard of this application.
     */
    private static void writeDelimited(OutputStream out, byte[] message, int offset, int length)
            throws IOException {
        byte[] prefix = new byte[CodedOutputStream.computeUInt32SizeNoTag(length)];
        CodedOutputStream coded = CodedOutputStream.newInstance(prefix);
        coded.writeUInt32NoTag(length);
        coded.flush();
        out.write(prefix);
        out.write(message, offset, length);
    }

    /** Running totals, and the sequence walk that finds gaps. */
    private static final class Counters {

        private final List<SequenceGap> gaps = new ArrayList<>();
        private long preserved;
        private long reencoded;
        private long lifecycle;
        private long otherEnvelopes;
        private long undecodable;
        private long lastSequence = -1;

        /**
         * Records a frame's sequence, noting any run that was skipped.
         *
         * <p>Sequence numbers come from the BES stream and are contiguous when
         * nothing was lost. A file import journals its records with sequential
         * numbers of its own, so this finds gaps in a live capture and finds
         * none in an import — which is correct in both cases.
         */
        void noteSequence(long sequence) {
            if (lastSequence >= 0 && sequence > lastSequence + 1) {
                gaps.add(new SequenceGap(lastSequence, sequence));
            }
            if (sequence > lastSequence) {
                lastSequence = sequence;
            }
        }
    }
}
