package com.holtherndon.bazelviz.capture.bes;

import io.grpc.KnownLength;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/**
 * A gRPC marshaller that hands back the request's bytes exactly as they arrived.
 *
 * <h2>Why the generated marshaller is not enough</h2>
 *
 * <p>ADR-004 promises the journal contains what Bazel sent, byte for byte. The generated protobuf
 * marshaller parses a message, and journaling would then have to serialize it again — and protobuf
 * does not guarantee that re-serializing a parsed message reproduces its input. Field order,
 * unknown fields and varint encoding are all free to differ. The difference would be invisible
 * until someone compared a captured session against the bytes Bazel actually wrote, and by then
 * every session on disk would be affected.
 *
 * <p>So the transport layer stops at the bytes, and every interpretation happens later, from the
 * journal, where it can be redone by a future build.
 *
 * <h2>The size limit</h2>
 *
 * <p>gRPC's own {@code maxInboundMessageSize} is the primary enforcement and rejects an oversized
 * message before this is reached. The check here is a second, local one, because this class can be
 * given a stream by any caller and a length taken on trust is exactly the failure plan 21.3 warns
 * about.
 */
final class RawBytesMarshaller implements MethodDescriptor.Marshaller<RawPayloadLease> {

  private final int maxMessageBytes;
  private final RetainedPayloadBudget retainedPayloadBudget;

  RawBytesMarshaller(int maxMessageBytes) {
    this(maxMessageBytes, new RetainedPayloadBudget(Long.MAX_VALUE));
  }

  RawBytesMarshaller(int maxMessageBytes, RetainedPayloadBudget retainedPayloadBudget) {
    if (maxMessageBytes <= 0) {
      throw new IllegalArgumentException(
          "maxMessageBytes must be positive, got " + maxMessageBytes);
    }
    this.maxMessageBytes = maxMessageBytes;
    this.retainedPayloadBudget =
        Objects.requireNonNull(retainedPayloadBudget, "retainedPayloadBudget");
  }

  @Override
  public InputStream stream(RawPayloadLease value) {
    return new ByteArrayInputStream(value.bytes(), 0, value.length());
  }

  @Override
  public RawPayloadLease parse(InputStream stream) {
    RawPayloadLease lease = null;
    try {
      if (stream instanceof KnownLength) {
        int available = stream.available();
        if (available > maxMessageBytes) {
          throw tooLarge(available + " bytes");
        }
        lease = reserve(available);
        byte[] payload = new byte[available];
        int offset = 0;
        while (offset < available) {
          int read = stream.read(payload, offset, available - offset);
          if (read < 0) {
            throw Status.INTERNAL
                .withDescription(
                    "known-length BES message ended after "
                        + offset
                        + " of "
                        + available
                        + " bytes")
                .asRuntimeException();
          }
          if (read > 0) {
            offset += read;
          }
        }
        if (stream.read() >= 0) {
          throw tooLarge("at least " + ((long) available + 1L) + " bytes");
        }
        lease.attach(payload, available);
        return lease;
      }

      // With no trustworthy length, reserve the worst legal case before allocating. The backing
      // array stays at that size until normalization, so its whole physical retention is included
      // in the aggregate budget even when the message turns out to be small.
      lease = reserve(maxMessageBytes);
      byte[] payload = new byte[maxMessageBytes];
      byte[] buffer = new byte[16 * 1024];
      int size = 0;
      while (true) {
        int remaining = maxMessageBytes - size;
        int requested = (int) Math.min(buffer.length, (long) remaining + 1L);
        int read = stream.read(buffer, 0, requested);
        if (read < 0) {
          lease.attach(payload, size);
          return lease;
        }
        if (read == 0) {
          continue;
        }
        if (read > remaining) {
          throw tooLarge("at least " + ((long) maxMessageBytes + 1L) + " bytes");
        }
        System.arraycopy(buffer, 0, payload, size, read);
        size += read;
      }
    } catch (IOException failure) {
      if (lease != null) {
        lease.close();
      }
      throw Status.INTERNAL
          .withDescription("could not read a BES message off the wire")
          .withCause(failure)
          .asRuntimeException();
    } catch (RuntimeException | Error failure) {
      if (lease != null) {
        lease.close();
      }
      throw failure;
    }
  }

  private RawPayloadLease reserve(int bytes) {
    try {
      return retainedPayloadBudget.reserve(bytes);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw Status.CANCELLED
          .withDescription("interrupted while waiting for retained BES payload capacity")
          .withCause(interrupted)
          .asRuntimeException();
    } catch (RetainedPayloadBudget.PayloadRefusedException refused) {
      throw Status.RESOURCE_EXHAUSTED
          .withDescription(refused.getMessage())
          .withCause(refused)
          .asRuntimeException();
    }
  }

  private RuntimeException tooLarge(String observedSize) {
    return Status.RESOURCE_EXHAUSTED
        .withDescription(
            "BES message of "
                + observedSize
                + " exceeds the "
                + maxMessageBytes
                + "-byte limit this capture accepts")
        .asRuntimeException();
  }
}
