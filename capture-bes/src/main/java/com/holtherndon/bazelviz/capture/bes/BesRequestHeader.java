package com.holtherndon.bazelviz.capture.bes;

import com.google.protobuf.CodedInputStream;
import java.io.IOException;
import java.util.Objects;
import java.util.Optional;

/**
 * The two facts the receive path needs out of a BES request, read without
 * materializing the event inside it.
 *
 * <h2>Why this is not just {@code parseFrom}</h2>
 *
 * <p>The gRPC callback must return quickly and must not do expensive protobuf
 * traversal (plan 9.3). A full {@code parseFrom} of the request would allocate
 * a {@code ByteString} copy of every build event that passes through — one
 * extra copy of the entire event stream, on the hottest path in the
 * application, to obtain two scalar fields the journal frame needs.
 *
 * <p>So this walks the outer messages with {@link CodedInputStream}, reads the
 * stream identity and the sequence number, and <em>skips</em> the event field
 * entirely. The bytes of the event are already in the caller's array on their
 * way to the journal verbatim; nothing here needs to look inside them, and
 * {@code EventNormalizer} does that later, off this thread.
 *
 * <p>The wire walk uses the protobuf library's own reader rather than a
 * hand-rolled varint decoder. Field numbers can be got wrong; wire-format
 * framing should not be re-implemented to find that out.
 *
 * @param buildId {@code StreamId.build_id}, absent when the request carried none
 * @param invocationId {@code StreamId.invocation_id}
 * @param component {@code StreamId.component}, as its enum number
 * @param sequence {@code OrderedBuildEvent.sequence_number}
 */
record BesRequestHeader(
        Optional<String> buildId, Optional<String> invocationId, int component, long sequence) {

    // Field numbers from the vendored google/devtools/build/v1 protos. Named
    // rather than inlined because a wrong number here reads a different field
    // and produces a plausible-looking wrong answer instead of an error.
    private static final int TOOL_REQUEST_ORDERED_BUILD_EVENT = 4;
    private static final int LIFECYCLE_REQUEST_BUILD_EVENT = 2;
    private static final int ORDERED_STREAM_ID = 1;
    private static final int ORDERED_SEQUENCE_NUMBER = 2;
    private static final int STREAM_ID_BUILD_ID = 1;
    private static final int STREAM_ID_COMPONENT = 3;
    private static final int STREAM_ID_INVOCATION_ID = 6;

    /** The {@code BuildComponent} enum number for an unset component. */
    static final int UNKNOWN_COMPONENT = 0;

    BesRequestHeader {
        buildId = Objects.requireNonNull(buildId, "buildId");
        invocationId = Objects.requireNonNull(invocationId, "invocationId");
    }

    /**
     * Reads a {@code PublishBuildToolEventStreamRequest} header.
     *
     * @throws IOException when the bytes are not readable as one; the caller
     *     fails the RPC rather than journaling a frame it cannot address
     */
    static BesRequestHeader ofToolRequest(byte[] payload, int offset, int length) throws IOException {
        return read(payload, offset, length, TOOL_REQUEST_ORDERED_BUILD_EVENT);
    }

    /** Reads a {@code PublishLifecycleEventRequest} header. */
    static BesRequestHeader ofLifecycleRequest(byte[] payload, int offset, int length) throws IOException {
        return read(payload, offset, length, LIFECYCLE_REQUEST_BUILD_EVENT);
    }

    private static BesRequestHeader read(byte[] payload, int offset, int length, int orderedEventField)
            throws IOException {
        CodedInputStream input = CodedInputStream.newInstance(payload, offset, length);
        while (true) {
            int tag = input.readTag();
            if (tag == 0) {
                // No ordered build event in the request at all. Legal on the
                // wire, meaningless to us: without a sequence number the frame
                // has no identity and duplicate detection cannot work.
                throw new IOException("BES request carried no ordered build event");
            }
            if (fieldOf(tag) == orderedEventField) {
                int limit = input.pushLimit(input.readRawVarint32());
                BesRequestHeader header = readOrdered(input);
                input.popLimit(limit);
                return header;
            }
            input.skipField(tag);
        }
    }

    private static BesRequestHeader readOrdered(CodedInputStream input) throws IOException {
        Optional<String> buildId = Optional.empty();
        Optional<String> invocationId = Optional.empty();
        int component = UNKNOWN_COMPONENT;
        long sequence = 0;
        while (true) {
            int tag = input.readTag();
            if (tag == 0) {
                return new BesRequestHeader(buildId, invocationId, component, sequence);
            }
            switch (fieldOf(tag)) {
                case ORDERED_STREAM_ID -> {
                    int limit = input.pushLimit(input.readRawVarint32());
                    StreamIdFields fields = readStreamId(input);
                    input.popLimit(limit);
                    buildId = fields.buildId();
                    invocationId = fields.invocationId();
                    component = fields.component();
                }
                case ORDERED_SEQUENCE_NUMBER -> sequence = input.readInt64();
                // Field 3 is the event itself. Skipped deliberately: see the
                // class comment. This is the branch that keeps a copy of every
                // build event from being made on the receive thread.
                default -> input.skipField(tag);
            }
        }
    }

    private static StreamIdFields readStreamId(CodedInputStream input) throws IOException {
        Optional<String> buildId = Optional.empty();
        Optional<String> invocationId = Optional.empty();
        int component = UNKNOWN_COMPONENT;
        while (true) {
            int tag = input.readTag();
            if (tag == 0) {
                return new StreamIdFields(buildId, invocationId, component);
            }
            switch (fieldOf(tag)) {
                case STREAM_ID_BUILD_ID -> buildId = nonEmpty(input.readStringRequireUtf8());
                case STREAM_ID_INVOCATION_ID -> invocationId = nonEmpty(input.readStringRequireUtf8());
                case STREAM_ID_COMPONENT -> component = input.readEnum();
                default -> input.skipField(tag);
            }
        }
    }

    private static int fieldOf(int tag) {
        return tag >>> 3;
    }

    private static Optional<String> nonEmpty(String value) {
        return value.isEmpty() ? Optional.empty() : Optional.of(value);
    }

    private record StreamIdFields(Optional<String> buildId, Optional<String> invocationId, int component) {}

    /**
     * The stream key this request belongs to.
     *
     * <p>A missing build or invocation id becomes an explicit placeholder rather
     * than an empty string, so that two streams that both lack an id do not
     * collapse into one key and start overwriting each other's sequences. It is
     * still one key per stream, and it still says which part was absent.
     */
    BesStreamKey streamKey() {
        return new BesStreamKey(
                buildId.orElse("unknown-build"),
                invocationId.orElse("unknown-invocation"),
                componentName());
    }

    /**
     * The component's enum name, or its number when this build does not know the
     * name. A newer Bazel adding a component must not make the key unreadable.
     */
    String componentName() {
        return switch (component) {
            case 0 -> "UNKNOWN_COMPONENT";
            case 1 -> "CONTROLLER";
            case 2 -> "WORKER";
            case 3 -> "TOOL";
            default -> "COMPONENT_" + component;
        };
    }
}
