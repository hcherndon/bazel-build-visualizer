package com.holtherndon.bazelviz.capture.bes;

import io.grpc.KnownLength;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * A gRPC marshaller that hands back the request's bytes exactly as they arrived.
 *
 * <h2>Why the generated marshaller is not enough</h2>
 *
 * <p>ADR-004 promises the journal contains what Bazel sent, byte for byte. The
 * generated protobuf marshaller parses a message, and journaling would then have
 * to serialize it again — and protobuf does not guarantee that re-serializing a
 * parsed message reproduces its input. Field order, unknown fields and varint
 * encoding are all free to differ. The difference would be invisible until
 * someone compared a captured session against the bytes Bazel actually wrote,
 * and by then every session on disk would be affected.
 *
 * <p>So the transport layer stops at the bytes, and every interpretation happens
 * later, from the journal, where it can be redone by a future build.
 *
 * <h2>The size limit</h2>
 *
 * <p>gRPC's own {@code maxInboundMessageSize} is the primary enforcement and
 * rejects an oversized message before this is reached. The check here is a
 * second, local one, because this class can be given a stream by any caller and
 * a length taken on trust is exactly the failure plan 21.3 warns about.
 */
final class RawBytesMarshaller implements MethodDescriptor.Marshaller<byte[]> {

    private final int maxMessageBytes;

    RawBytesMarshaller(int maxMessageBytes) {
        if (maxMessageBytes <= 0) {
            throw new IllegalArgumentException("maxMessageBytes must be positive, got " + maxMessageBytes);
        }
        this.maxMessageBytes = maxMessageBytes;
    }

    @Override
    public InputStream stream(byte[] value) {
        return new ByteArrayInputStream(value);
    }

    @Override
    public byte[] parse(InputStream stream) {
        try {
            if (stream instanceof KnownLength) {
                int available = stream.available();
                if (available > maxMessageBytes) {
                    throw Status.RESOURCE_EXHAUSTED
                            .withDescription("BES message of " + available + " bytes exceeds the "
                                    + maxMessageBytes + "-byte limit this capture accepts")
                            .asRuntimeException();
                }
            }
            byte[] bytes = stream.readAllBytes();
            if (bytes.length > maxMessageBytes) {
                throw Status.RESOURCE_EXHAUSTED
                        .withDescription("BES message of " + bytes.length + " bytes exceeds the "
                                + maxMessageBytes + "-byte limit this capture accepts")
                        .asRuntimeException();
            }
            return bytes;
        } catch (IOException failure) {
            throw Status.INTERNAL
                    .withDescription("could not read a BES message off the wire")
                    .withCause(failure)
                    .asRuntimeException();
        }
    }
}
