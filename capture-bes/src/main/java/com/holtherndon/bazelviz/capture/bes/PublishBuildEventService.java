package com.holtherndon.bazelviz.capture.bes;

import com.google.devtools.build.v1.PublishBuildEventGrpc;
import com.google.devtools.build.v1.PublishBuildToolEventStreamResponse;
import com.google.devtools.build.v1.StreamId;
import com.google.protobuf.Empty;
import com.holtherndon.bazelviz.core.journal.JournalFormat.SourceKind;
import io.grpc.MethodDescriptor;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.protobuf.ProtoUtils;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.ServerCalls;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The embedded Build Event Service: the two RPCs Bazel calls when
 * {@code --bes_backend} points here.
 *
 * <h2>What this class is responsible for, and what it is not</h2>
 *
 * <p>It takes bytes off the wire, gives them an identity, hands them to the
 * pipeline, and acknowledges them once the pipeline says they are journaled. It
 * does not decode build events, touch a database or know what a session is.
 * That division is plan 9.3's, and it is what lets the receive path stay fast
 * enough to keep up with a build that emits events faster than they can be
 * indexed.
 *
 * <h2>Acknowledgement is the contract with Bazel</h2>
 *
 * <p>Bazel treats an acknowledged sequence as delivered. Acknowledging before
 * the frame is journaled would mean a crash could lose events Bazel believes it
 * handed over, and Bazel would have no reason to retransmit them. So the ack is
 * emitted from the {@code onJournaled} callback, never from the receive path,
 * and {@link BesStreamTracker} refuses to acknowledge past a gap.
 *
 * <h2>Backpressure</h2>
 *
 * <p>Automatic flow control is disabled and the next message is requested only
 * after the previous one has been submitted. When the pipeline saturates,
 * {@link RawEventSink#submit} blocks, the request is not issued, and Bazel's
 * client stops sending. That is the intended behavior: the alternative is an
 * unbounded queue in front of a disk, which is how a large build takes the
 * capture out of memory rather than merely slowing it down.
 *
 * <p>Handlers run on the server's own executor, not on a transport event loop,
 * so a blocked handler stalls its own stream and nothing else.
 */
final class PublishBuildEventService {

    private static final Logger log = LoggerFactory.getLogger(PublishBuildEventService.class);

    private final RawEventSink sink;
    private final int maxMessageBytes;
    private final MicrosClock clock;
    private final AtomicInteger openStreams = new AtomicInteger();

    /**
     * Stream state, kept per {@code StreamId} for the life of this server rather
     * than per connection.
     *
     * <p>One BES stream can span more than one connection. Bazel's uploader
     * outlives the client process, and when it loses its connection it reopens
     * one and replays the stream from sequence 1 — same {@code StreamId}, same
     * events. A tracker owned by the connection would see that replay as a
     * fresh stream and journal every event twice. Keyed by identity, it sees
     * what it is: retransmission, handled idempotently, exactly as the plan
     * requires.
     */
    private final java.util.concurrent.ConcurrentMap<BesStreamKey, BesStreamTracker> trackers =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Epoch-microsecond source; injectable so tests are deterministic. */
    @FunctionalInterface
    interface MicrosClock {
        long nowMicros();

        static MicrosClock system() {
            return () -> {
                java.time.Instant now = java.time.Clock.systemUTC().instant();
                return now.getEpochSecond() * 1_000_000L + now.getNano() / 1_000L;
            };
        }
    }

    PublishBuildEventService(RawEventSink sink, int maxMessageBytes, MicrosClock clock) {
        this.sink = Objects.requireNonNull(sink, "sink");
        this.maxMessageBytes = maxMessageBytes;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Streams currently being received. Used by the capture status display. */
    int openStreamCount() {
        return openStreams.get();
    }

    /**
     * Builds the service definition with raw-byte request marshallers.
     *
     * <p>Assembled by hand rather than by extending the generated base class,
     * because the generated one parses requests into messages and this service
     * must not (see {@link RawBytesMarshaller}). The method names and types come
     * from the generated descriptors, so a proto change is a compile error here
     * rather than a runtime {@code UNIMPLEMENTED} against a mistyped string.
     */
    ServerServiceDefinition bindService() {
        RawBytesMarshaller requests = new RawBytesMarshaller(maxMessageBytes);

        MethodDescriptor<byte[], Empty> lifecycle =
                MethodDescriptor.<byte[], Empty>newBuilder()
                        .setType(MethodDescriptor.MethodType.UNARY)
                        .setFullMethodName(
                                PublishBuildEventGrpc.getPublishLifecycleEventMethod().getFullMethodName())
                        .setRequestMarshaller(requests)
                        .setResponseMarshaller(ProtoUtils.marshaller(Empty.getDefaultInstance()))
                        .build();

        MethodDescriptor<byte[], PublishBuildToolEventStreamResponse> stream =
                MethodDescriptor.<byte[], PublishBuildToolEventStreamResponse>newBuilder()
                        .setType(MethodDescriptor.MethodType.BIDI_STREAMING)
                        .setFullMethodName(PublishBuildEventGrpc.getPublishBuildToolEventStreamMethod()
                                .getFullMethodName())
                        .setRequestMarshaller(requests)
                        .setResponseMarshaller(ProtoUtils.marshaller(
                                PublishBuildToolEventStreamResponse.getDefaultInstance()))
                        .build();

        return ServerServiceDefinition.builder(PublishBuildEventGrpc.SERVICE_NAME)
                .addMethod(lifecycle, ServerCalls.asyncUnaryCall(this::publishLifecycleEvent))
                .addMethod(stream, ServerCalls.asyncBidiStreamingCall(this::publishBuildToolEventStream))
                .build();
    }

    // ------------------------------------------------------------- lifecycle

    /**
     * Journals a lifecycle request and returns {@code Empty}.
     *
     * <p>Lifecycle events are numbered independently of the tool stream and
     * carry no build event, so they get no acknowledgement machinery: returning
     * from this method <em>is</em> the acknowledgement, and it happens after the
     * frame is journaled, for the same reason the streamed ones do.
     */
    private void publishLifecycleEvent(byte[] request, StreamObserver<Empty> responseObserver) {
        BesRequestHeader header;
        try {
            header = BesRequestHeader.ofLifecycleRequest(request, 0, request.length);
        } catch (IOException malformed) {
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription("unreadable lifecycle request: " + malformed.getMessage())
                    .asRuntimeException());
            return;
        }

        RawBesEvent event = new RawBesEvent(
                SourceKind.BES_LIFECYCLE,
                header.streamKey(),
                header.sequence(),
                clock.nowMicros(),
                request);
        try {
            java.util.concurrent.CountDownLatch journaled = new java.util.concurrent.CountDownLatch(1);
            sink.submit(event, journaled::countDown);
            journaled.await();
            responseObserver.onNext(Empty.getDefaultInstance());
            responseObserver.onCompleted();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            responseObserver.onError(Status.CANCELLED
                    .withDescription("capture was interrupted while journaling a lifecycle event")
                    .asRuntimeException());
        } catch (RawEventSink.CaptureRejectedException rejected) {
            responseObserver.onError(Status.UNAVAILABLE
                    .withDescription("capture cannot accept events: " + rejected.getMessage())
                    .withCause(rejected)
                    .asRuntimeException());
        }
    }

    // ----------------------------------------------------------- tool stream

    private StreamObserver<byte[]> publishBuildToolEventStream(
            StreamObserver<PublishBuildToolEventStreamResponse> responseObserver) {
        ServerCallStreamObserver<PublishBuildToolEventStreamResponse> responses =
                (ServerCallStreamObserver<PublishBuildToolEventStreamResponse>) responseObserver;
        // Manual flow control: without this, gRPC keeps delivering messages
        // regardless of whether the pipeline is keeping up, and the queue in
        // front of the journal becomes the only thing absorbing a fast build.
        responses.disableAutoRequest();
        return new ToolStreamObserver(responses);
    }

    /**
     * One {@code PublishBuildToolEventStream} call.
     *
     * <p>gRPC delivers {@code onNext} serially for a single call, so the stream
     * state needs no lock against itself. It does need one against the
     * acknowledgement path, which runs on the pipeline thread — hence the
     * synchronization around every use of the response observer, which is not
     * thread-safe and would otherwise be written to from two threads at once.
     */
    private final class ToolStreamObserver implements StreamObserver<byte[]> {

        private final ServerCallStreamObserver<PublishBuildToolEventStreamResponse> responses;
        private final Object responseLock = new Object();

        /**
         * Events accepted from the wire whose journal callback has not run yet.
         *
         * <p>This is what the client half-close has to wait for. Bazel refuses a
         * stream the server closes while an acknowledgement is still owed —
         * "Server closed stream with status OK but not all ACKs have been
         * received" — and it is right to: an unacknowledged event is one the
         * client cannot know we kept.
         */
        private final java.util.concurrent.atomic.AtomicLong outstanding =
                new java.util.concurrent.atomic.AtomicLong();

        private BesStreamTracker tracker;
        private StreamId streamId;
        private boolean counted;
        private volatile boolean halfClosed;
        private boolean responsesClosed;

        ToolStreamObserver(ServerCallStreamObserver<PublishBuildToolEventStreamResponse> responses) {
            this.responses = responses;
            responses.setOnCancelHandler(this::onCancelled);
            responses.request(1);
        }

        @Override
        public void onNext(byte[] request) {
            BesRequestHeader header;
            try {
                header = BesRequestHeader.ofToolRequest(request, 0, request.length);
            } catch (IOException malformed) {
                fail(Status.INVALID_ARGUMENT
                        .withDescription("unreadable build tool event: " + malformed.getMessage())
                        .asRuntimeException());
                return;
            }

            if (tracker == null) {
                start(header);
            }

            long receiveMicros = clock.nowMicros();
            BesStreamTracker.Decision decision = tracker.accept(header.sequence(), receiveMicros);
            switch (decision) {
                case DUPLICATE -> {
                    // Retransmission after a reconnect. Acknowledge it again so
                    // the client can move on, and do not journal it twice.
                    sendAck(header.sequence());
                    responses.request(1);
                }
                case INVALID -> fail(Status.INVALID_ARGUMENT
                        .withDescription("BES sequence numbers start at " + BesStreamState.FIRST_SEQUENCE
                                + "; got " + header.sequence())
                        .asRuntimeException());
                case TOO_FAR_AHEAD -> fail(Status.FAILED_PRECONDITION
                        .withDescription("too many events are outstanding above sequence "
                                + tracker.snapshot().highestContiguous()
                                + "; this capture will not buffer further out-of-order events")
                        .asRuntimeException());
                case ACCEPTED -> journal(header, request, receiveMicros);
            }
        }

        private void journal(BesRequestHeader header, byte[] request, long receiveMicros) {
            RawBesEvent event = new RawBesEvent(
                    SourceKind.BES_ENVELOPE, tracker.key(), header.sequence(), receiveMicros, request);
            // Counted before the submission, not inside the callback: the
            // callback can run on another thread before submit() has returned,
            // and a counter incremented afterwards would briefly read zero with
            // an event still in flight — which is exactly the moment a
            // half-close would close the stream too early.
            outstanding.incrementAndGet();
            try {
                sink.submit(event, () -> onJournaled(header.sequence()));
            } catch (InterruptedException interrupted) {
                outstanding.decrementAndGet();
                Thread.currentThread().interrupt();
                fail(Status.CANCELLED
                        .withDescription("capture was interrupted while journaling event "
                                + header.sequence())
                        .asRuntimeException());
                return;
            } catch (RawEventSink.CaptureRejectedException rejected) {
                outstanding.decrementAndGet();
                fail(Status.UNAVAILABLE
                        .withDescription("capture cannot accept events: " + rejected.getMessage())
                        .withCause(rejected)
                        .asRuntimeException());
                return;
            }
            // Ask for the next message only now. Everything above this line is
            // the backpressure: if submit blocked, the client waited.
            responses.request(1);
        }

        /** Runs on the pipeline thread once the frame is in the journal. */
        private void onJournaled(long sequence) {
            try {
                BesStreamTracker.AckRange range = tracker.journaled(sequence);
                for (long seq = range.from(); seq <= range.to(); seq++) {
                    sendAck(seq);
                }
            } finally {
                // In a finally block so that a failure to acknowledge cannot
                // leave the counter permanently above zero, which would hang
                // the half-close and leave Bazel waiting for a stream close
                // that never comes.
                if (outstanding.decrementAndGet() == 0) {
                    completeIfDrained();
                }
            }
        }

        private void sendAck(long sequence) {
            synchronized (responseLock) {
                if (responsesClosed) {
                    return;
                }
                try {
                    responses.onNext(PublishBuildToolEventStreamResponse.newBuilder()
                            .setStreamId(streamId)
                            .setSequenceNumber(sequence)
                            .build());
                } catch (StatusRuntimeException | IllegalStateException gone) {
                    // The client hung up between journaling and acknowledging.
                    // Recorded, not thrown: the event is safely journaled, and
                    // the only thing lost is Bazel's knowledge of that.
                    log.debug("could not acknowledge sequence {} on {}: {}",
                            sequence, tracker == null ? "(unstarted stream)" : tracker.key(), gone.toString());
                }
            }
        }

        private void start(BesRequestHeader header) {
            BesStreamKey key = header.streamKey();
            tracker = trackers.computeIfAbsent(key, BesStreamTracker::new);
            // A stream this server has seen before is a replay, not a new one.
            // Reopening keeps its watermarks, which is what lets the replayed
            // events be recognised as duplicates.
            tracker.reopen();
            streamId = StreamId.newBuilder()
                    .setBuildId(header.buildId().orElse(""))
                    .setInvocationId(header.invocationId().orElse(""))
                    .setComponentValue(header.component())
                    .build();
            counted = true;
            openStreams.incrementAndGet();
            sink.streamOpened(tracker.key());
        }

        @Override
        public void onError(Throwable failure) {
            // The client cancelled, the socket died, or the build was killed.
            // Not an error of ours: whatever arrived is journaled and the
            // session records the stream as aborted rather than complete.
            end(BesStreamState.Completion.ABORTED, String.valueOf(failure));
        }

        /**
         * The client has sent its last event and half-closed.
         *
         * <p>This does <em>not</em> close the response stream. Events accepted a
         * moment ago may still be on their way to the journal, and Bazel rejects
         * a stream closed while it is still owed an acknowledgement. The close
         * happens in {@link #completeIfDrained()}, from whichever thread empties
         * the outstanding count last.
         */
        @Override
        public void onCompleted() {
            halfClosed = true;
            completeIfDrained();
        }

        /**
         * Closes the response stream once every accepted event has been
         * journaled and acknowledged, and the client has half-closed.
         *
         * <p>Called from two threads — the gRPC thread on half-close, and the
         * pipeline thread as each journal callback completes — so the close
         * itself is guarded and idempotent. Either one can be the last to
         * arrive, and the stream must close exactly once whichever it is.
         */
        private void completeIfDrained() {
            if (!halfClosed || outstanding.get() > 0) {
                return;
            }
            end(BesStreamState.Completion.FINISHED, null);
            synchronized (responseLock) {
                if (responsesClosed) {
                    return;
                }
                responsesClosed = true;
                try {
                    responses.onCompleted();
                } catch (StatusRuntimeException | IllegalStateException gone) {
                    log.debug("stream already closed when completing: {}", gone.toString());
                }
            }
        }

        private void onCancelled() {
            end(BesStreamState.Completion.ABORTED, "the client cancelled the stream");
        }

        private void fail(StatusRuntimeException status) {
            end(BesStreamState.Completion.FAILED, status.getStatus().getDescription());
            synchronized (responseLock) {
                if (responsesClosed) {
                    return;
                }
                responsesClosed = true;
                try {
                    responses.onError(status);
                } catch (StatusRuntimeException | IllegalStateException gone) {
                    log.debug("stream already closed when failing: {}", gone.toString());
                }
            }
        }

        private void end(BesStreamState.Completion how, String detail) {
            if (tracker == null) {
                // The stream ended before a single readable request arrived.
                // Nothing was accepted, so there is nothing to report beyond the
                // log line that the caller already has.
                return;
            }
            boolean first;
            synchronized (tracker) {
                first = !tracker.hasEnded();
                tracker.end(how, detail);
            }
            if (!first) {
                return;
            }
            if (counted) {
                counted = false;
                openStreams.decrementAndGet();
            }
            sink.streamEnded(tracker.snapshot());
        }
    }
}
