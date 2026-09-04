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
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The embedded Build Event Service: the two RPCs Bazel calls when {@code --bes_backend} points
 * here.
 *
 * <h2>What this class is responsible for, and what it is not</h2>
 *
 * <p>It takes bytes off the wire, gives them an identity, hands them to the pipeline, and
 * acknowledges them once the pipeline says they are journaled. It does not decode build events,
 * touch a database or know what a session is. That division is plan 9.3's, and it is what lets the
 * receive path stay fast enough to keep up with a build that emits events faster than they can be
 * indexed.
 *
 * <h2>Acknowledgement is the contract with Bazel</h2>
 *
 * <p>Bazel treats an acknowledged sequence as delivered. Acknowledging before the frame is
 * journaled would mean a crash could lose events Bazel believes it handed over, and Bazel would
 * have no reason to retransmit them. So the ack is emitted from the {@code onJournaled} callback,
 * never from the receive path, and {@link BesStreamTracker} refuses to acknowledge past a gap.
 *
 * <h2>Backpressure</h2>
 *
 * <p>Automatic flow control is disabled and the next message is requested only after the previous
 * one has been submitted. When the pipeline saturates, {@link RawEventSink#submit} blocks, the
 * request is not issued, and Bazel's client stops sending. That is the intended behavior: the
 * alternative is an unbounded queue in front of a disk, which is how a large build takes the
 * capture out of memory rather than merely slowing it down.
 *
 * <p>Handlers run on the server's own executor, not on a transport event loop, so a blocked handler
 * stalls its own stream and nothing else.
 */
final class PublishBuildEventService {

  private static final Logger log = LoggerFactory.getLogger(PublishBuildEventService.class);

  private final RawEventSink sink;
  private final int maxMessageBytes;
  private final MicrosClock clock;
  private final BesResources resources;
  private final Object trackerAdmissionLock = new Object();

  /**
   * Stream state, kept per {@code StreamId} for the life of this server rather than per connection.
   *
   * <p>One BES stream can span more than one connection. Bazel's uploader outlives the client
   * process, and when it loses its connection it reopens one and replays the stream from sequence 1
   * — same {@code StreamId}, same events. A tracker owned by the connection would see that replay
   * as a fresh stream and journal every event twice. Keyed by identity, it sees what it is:
   * retransmission, handled idempotently, exactly as the plan requires.
   */
  private final ConcurrentMap<BesStreamKey, BesStreamTracker> trackers = new ConcurrentHashMap<>();

  /** Every identity admitted by either BES RPC, retained for this server's lifetime. */
  private final Set<BesStreamKey> admittedKeys = ConcurrentHashMap.newKeySet();

  private final ConcurrentMap<BesStreamTracker.Connection, ToolStreamObserver> connections =
      new ConcurrentHashMap<>();

  /**
   * How many messages may be in flight from the client at once.
   *
   * <p>Requesting one at a time is the simplest correct backpressure and it is also a round trip
   * per event: measured, the whole capture path settled at about 45,000 events per second,
   * latency-bound rather than work-bound, against an objective of 100,000. A window lets the
   * transport keep the pipeline fed while the bound still holds — at most this many events are
   * outstanding, so the memory ceiling is the window times the maximum message size, and the
   * receive queue behind it is bounded independently.
   *
   * <p>64 rather than something larger because the gain flattens: the point is to stop paying a
   * round trip per event, not to buffer the build.
   */
  private static final int FLOW_CONTROL_WINDOW = 64;

  /** Epoch-microsecond source; injectable so tests are deterministic. */
  @FunctionalInterface
  interface MicrosClock {
    long nowMicros();

    static MicrosClock system() {
      return () -> {
        Instant now = Clock.systemUTC().instant();
        return now.getEpochSecond() * 1_000_000L + now.getNano() / 1_000L;
      };
    }
  }

  PublishBuildEventService(
      RawEventSink sink, int maxMessageBytes, MicrosClock clock, BesResources resources) {
    this.sink = Objects.requireNonNull(sink, "sink");
    this.maxMessageBytes = maxMessageBytes;
    this.clock = Objects.requireNonNull(clock, "clock");
    this.resources = Objects.requireNonNull(resources, "resources");
  }

  /**
   * Builds the service definition with raw-byte request marshallers.
   *
   * <p>Assembled by hand rather than by extending the generated base class, because the generated
   * one parses requests into messages and this service must not (see {@link RawBytesMarshaller}).
   * The method names and types come from the generated descriptors, so a proto change is a compile
   * error here rather than a runtime {@code UNIMPLEMENTED} against a mistyped string.
   */
  ServerServiceDefinition bindService() {
    RawBytesMarshaller requests =
        new RawBytesMarshaller(maxMessageBytes, resources.payloadBudget());

    MethodDescriptor<RawPayloadLease, Empty> lifecycle =
        MethodDescriptor.<RawPayloadLease, Empty>newBuilder()
            .setType(MethodDescriptor.MethodType.UNARY)
            .setFullMethodName(
                PublishBuildEventGrpc.getPublishLifecycleEventMethod().getFullMethodName())
            .setRequestMarshaller(requests)
            .setResponseMarshaller(ProtoUtils.marshaller(Empty.getDefaultInstance()))
            .build();

    MethodDescriptor<RawPayloadLease, PublishBuildToolEventStreamResponse> stream =
        MethodDescriptor.<RawPayloadLease, PublishBuildToolEventStreamResponse>newBuilder()
            .setType(MethodDescriptor.MethodType.BIDI_STREAMING)
            .setFullMethodName(
                PublishBuildEventGrpc.getPublishBuildToolEventStreamMethod().getFullMethodName())
            .setRequestMarshaller(requests)
            .setResponseMarshaller(
                ProtoUtils.marshaller(PublishBuildToolEventStreamResponse.getDefaultInstance()))
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
   * <p>Lifecycle events are numbered independently of the tool stream and carry no build event, so
   * they get no acknowledgement machinery: returning from this method <em>is</em> the
   * acknowledgement, and it happens after the frame is journaled, for the same reason the streamed
   * ones do.
   */
  void publishLifecycleEvent(RawPayloadLease request, StreamObserver<Empty> responseObserver) {
    if (!resources.tryAcquireRpc()) {
      request.close();
      responseObserver.onError(resourceRefusal("concurrent RPC", "active RPC limit"));
      return;
    }
    RawBesEvent event = null;
    boolean transferred = false;
    try {
      BesRequestHeader header =
          BesRequestHeader.ofLifecycleRequest(request.bytes(), 0, request.length());
      if (!admitLifecycleKey(header.streamKey())) {
        responseObserver.onError(resourceRefusal("stream key", "admitted stream-key limit"));
        return;
      }
      event =
          new RawBesEvent(
              SourceKind.BES_LIFECYCLE,
              header.streamKey(),
              header.sequence(),
              clock.nowMicros(),
              request);
      CountDownLatch journaled = new CountDownLatch(1);
      AtomicReference<Throwable> rejection = new AtomicReference<>();
      try {
        sink.submit(
            event,
            new RawEventSink.SubmissionCallback() {
              @Override
              public void onJournaled() {
                journaled.countDown();
              }

              @Override
              public void onRejected(Throwable failure) {
                rejection.set(failure);
                journaled.countDown();
              }
            });
      } catch (RuntimeException submissionFailure) {
        responseObserver.onError(
            Status.INTERNAL
                .withDescription("capture failed while accepting a lifecycle event")
                .withCause(submissionFailure)
                .asRuntimeException());
        return;
      }
      // A successful return transfers the event and its lease to the sink. Interruption while
      // waiting for durability must not release bytes the pipeline still owns.
      transferred = true;
      journaled.await();
      if (rejection.get() != null) {
        responseObserver.onError(
            Status.UNAVAILABLE
                .withDescription("capture could not journal the lifecycle event")
                .withCause(rejection.get())
                .asRuntimeException());
        return;
      }
      responseObserver.onNext(Empty.getDefaultInstance());
      responseObserver.onCompleted();
    } catch (IOException malformed) {
      responseObserver.onError(
          Status.INVALID_ARGUMENT
              .withDescription("unreadable lifecycle request: " + malformed.getMessage())
              .asRuntimeException());
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      responseObserver.onError(
          Status.CANCELLED
              .withDescription("capture was interrupted while journaling a lifecycle event")
              .asRuntimeException());
    } catch (RawEventSink.CaptureRejectedException rejected) {
      responseObserver.onError(
          Status.UNAVAILABLE
              .withDescription("capture cannot accept events: " + rejected.getMessage())
              .withCause(rejected)
              .asRuntimeException());
    } finally {
      if (!transferred) {
        if (event != null) {
          event.close();
        } else {
          request.close();
        }
      }
      resources.releaseRpc();
    }
  }

  // ----------------------------------------------------------- tool stream

  StreamObserver<RawPayloadLease> publishBuildToolEventStream(
      StreamObserver<PublishBuildToolEventStreamResponse> responseObserver) {
    ServerCallStreamObserver<PublishBuildToolEventStreamResponse> responses =
        (ServerCallStreamObserver<PublishBuildToolEventStreamResponse>) responseObserver;
    if (!resources.tryAcquireRpc()) {
      responses.onError(resourceRefusal("concurrent RPC", "active RPC limit"));
      return new RefusedToolStreamObserver();
    }
    ToolStreamObserver observer = new ToolStreamObserver(responses);
    try {
      // Manual flow control: without this, gRPC keeps delivering messages regardless of whether
      // the pipeline is keeping up, and the queue in front of the journal becomes the only thing
      // absorbing a fast build.
      responses.disableAutoRequest();
      responses.setOnCancelHandler(observer::onCancelled);
      if (!observer.requestMore(FLOW_CONTROL_WINDOW)) {
        return observer;
      }
    } catch (RuntimeException failure) {
      observer.fail(
          Status.INTERNAL
              .withDescription("could not initialize BES stream flow control")
              .withCause(failure)
              .asRuntimeException());
    }
    return observer;
  }

  /**
   * One {@code PublishBuildToolEventStream} call.
   *
   * <p>gRPC delivers {@code onNext} serially for a single call, so the stream state needs no lock
   * against itself. It does need one against the acknowledgement path, which runs on the pipeline
   * thread — hence the synchronization around every use of the response observer, which is not
   * thread-safe and would otherwise be written to from two threads at once.
   */
  private final class ToolStreamObserver implements StreamObserver<RawPayloadLease> {

    private final ServerCallStreamObserver<PublishBuildToolEventStreamResponse> responses;
    private final Object responseLock = new Object();
    private final Object lifecycleLock = new Object();
    private final ArrayDeque<ResponseTask> responseTasks = new ArrayDeque<>();
    private boolean drainingResponses;
    private Thread responseDrainer;

    /**
     * Events accepted from the wire whose journal callback has not run yet.
     *
     * <p>This is what the client half-close has to wait for. Bazel refuses a stream the server
     * closes while an acknowledgement is still owed — "Server closed stream with status OK but not
     * all ACKs have been received" — and it is right to: an unacknowledged event is one the client
     * cannot know we kept.
     */
    private final AtomicLong outstanding = new AtomicLong();

    /** Accepted originals whose sink callback has not resolved their durable append yet. */
    private final Set<Long> durabilityPending = ConcurrentHashMap.newKeySet();

    private BesStreamTracker tracker;
    private BesStreamTracker.Connection connection;
    private StreamId streamId;
    private volatile boolean ended;
    private final AtomicBoolean rpcReleased = new AtomicBoolean();
    private volatile boolean halfClosed;
    private boolean responsesClosed;
    private volatile boolean opening;
    private BesStreamState.Completion deferredEnding;
    private String deferredEndingDetail;

    @FunctionalInterface
    private interface ResponseAction {
      void run();
    }

    private static final class ResponseTask {

      private final ResponseAction action;
      private final CountDownLatch done = new CountDownLatch(1);
      private volatile boolean succeeded;

      private ResponseTask(ResponseAction action) {
        this.action = action;
      }
    }

    ToolStreamObserver(ServerCallStreamObserver<PublishBuildToolEventStreamResponse> responses) {
      this.responses = responses;
    }

    @Override
    public void onNext(RawPayloadLease request) {
      boolean transferred = false;
      try {
        BesRequestHeader header;
        try {
          header = BesRequestHeader.ofToolRequest(request.bytes(), 0, request.length());
        } catch (IOException malformed) {
          fail(
              Status.INVALID_ARGUMENT
                  .withDescription("unreadable build tool event: " + malformed.getMessage())
                  .asRuntimeException());
          return;
        }

        if (tracker == null) {
          if (!start(header)) {
            return;
          }
        } else if (!tracker.key().equals(header.streamKey())) {
          fail(
              Status.INVALID_ARGUMENT
                  .withDescription("one BES RPC cannot change its stream identity")
                  .asRuntimeException());
          return;
        }

        long receiveMicros = clock.nowMicros();
        // Count before accepting. A journal callback on another thread can run as soon as accept
        // returns, including before submit() itself returns.
        BesStreamTracker.Decision decision;
        IllegalStateException endedConnection = null;
        boolean duplicateDurability = false;
        synchronized (lifecycleLock) {
          if (ended) {
            return;
          }
          outstanding.incrementAndGet();
          try {
            decision = tracker.accept(connection, header.sequence(), receiveMicros);
            if (decision == BesStreamTracker.Decision.ACCEPTED
                && !durabilityPending.add(header.sequence())) {
              duplicateDurability = true;
            }
          } catch (IllegalStateException endedWhileAccepting) {
            decision = BesStreamTracker.Decision.INVALID;
            endedConnection = endedWhileAccepting;
          }
        }
        if (endedConnection != null) {
          outstanding.decrementAndGet();
          fail(
              Status.CANCELLED
                  .withDescription("BES connection ended while an event was arriving")
                  .withCause(endedConnection)
                  .asRuntimeException());
          return;
        }
        if (duplicateDurability) {
          outstanding.decrementAndGet();
          fail(
              Status.INTERNAL
                  .withDescription(
                      "accepted BES sequence "
                          + header.sequence()
                          + " was already waiting for durability")
                  .asRuntimeException());
          return;
        }
        switch (decision) {
          case DUPLICATE_ACK_NOW -> {
            sendTrackedAck(header.sequence(), true);
          }
          case DUPLICATE_WAIT -> {
            // The original copy is still in flight or above a gap. Its journal callback will route
            // this connection's acknowledgement; closing earlier would tell Bazel we kept bytes
            // that are not durable yet. Do not replenish credit yet: the initial window then also
            // bounds repeated pending-duplicate bookkeeping on this connection.
          }
          case INVALID -> {
            outstanding.decrementAndGet();
            fail(
                Status.INVALID_ARGUMENT
                    .withDescription(
                        "BES sequence numbers start at "
                            + BesStreamState.FIRST_SEQUENCE
                            + "; got "
                            + header.sequence())
                    .asRuntimeException());
          }
          case TOO_FAR_AHEAD -> {
            outstanding.decrementAndGet();
            fail(
                Status.FAILED_PRECONDITION
                    .withDescription(
                        "too many events are outstanding above sequence "
                            + tracker.snapshot().highestContiguous()
                            + "; this capture will not buffer further out-of-order events")
                    .asRuntimeException());
          }
          case ACCEPTED -> {
            transferred = journal(header, request, receiveMicros);
            if (transferred) {
              // Ownership is already with the sink. If requesting more transport work throws,
              // the outer finally must not release the pipeline-owned payload.
              requestMore(1);
            }
          }
        }
      } finally {
        if (!transferred) {
          request.close();
        }
      }
    }

    private boolean journal(BesRequestHeader header, RawPayloadLease request, long receiveMicros) {
      RawBesEvent event =
          new RawBesEvent(
              SourceKind.BES_ENVELOPE, tracker.key(), header.sequence(), receiveMicros, request);
      // Counted before the submission, not inside the callback: the
      // callback can run on another thread before submit() has returned,
      // and a counter incremented afterwards would briefly read zero with
      // an event still in flight — which is exactly the moment a
      // half-close would close the stream too early.
      try {
        sink.submit(
            event,
            new RawEventSink.SubmissionCallback() {
              @Override
              public void onJournaled() {
                ToolStreamObserver.this.onJournaled(header.sequence());
              }

              @Override
              public void onRejected(Throwable failure) {
                ToolStreamObserver.this.onJournalRejected(header.sequence(), failure);
              }
            });
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        rejectSequence(
            header.sequence(),
            interrupted,
            Status.CANCELLED
                .withDescription(
                    "capture was interrupted while journaling event " + header.sequence())
                .asRuntimeException());
        return false;
      } catch (RawEventSink.CaptureRejectedException rejected) {
        rejectSequence(
            header.sequence(),
            rejected,
            Status.UNAVAILABLE
                .withDescription("capture cannot accept events: " + rejected.getMessage())
                .withCause(rejected)
                .asRuntimeException());
        return false;
      } catch (RuntimeException submissionFailure) {
        rejectSequence(
            header.sequence(),
            submissionFailure,
            Status.INTERNAL
                .withDescription("capture failed while accepting BES event " + header.sequence())
                .withCause(submissionFailure)
                .asRuntimeException());
        return false;
      }
      return true;
    }

    private void onJournalRejected(long sequence, Throwable failure) {
      rejectSequence(
          sequence,
          failure,
          Status.UNAVAILABLE
              .withDescription("capture could not journal BES event " + sequence)
              .withCause(failure)
              .asRuntimeException());
    }

    private void rejectSequence(
        long sequence, Throwable failure, StatusRuntimeException responseFailure) {
      BesStreamTracker.RejectedResult rejected =
          tracker.rejected(sequence, responseFailure.getStatus().getDescription());
      try {
        // Claim the terminal response before making outstanding zero. Otherwise a concurrent
        // half-close can win as FINISHED between the decrement and this failure.
        fail(responseFailure);
        outstanding.decrementAndGet();
        for (BesStreamTracker.Connection waitingConnection : rejected.waitingConnections()) {
          ToolStreamObserver waiting = connections.get(waitingConnection);
          if (waiting != null) {
            waiting.rejectPending(sequence, failure);
          }
        }
        publishTerminal(rejected.terminalState());
      } finally {
        resolveDurability(sequence);
      }
    }

    private void rejectPending(long sequence, Throwable failure) {
      fail(
          Status.UNAVAILABLE
              .withDescription(
                  "capture could not journal the original BES event "
                      + sequence
                      + " replayed by this connection")
              .withCause(failure)
              .asRuntimeException());
      outstanding.decrementAndGet();
    }

    /** Runs on the pipeline thread once the frame is in the journal. */
    private void onJournaled(long sequence) {
      try {
        BesStreamTracker.JournalResult journaled = tracker.journaled(sequence);
        for (BesStreamTracker.PendingAck ready : journaled.readyAcks()) {
          ToolStreamObserver waiting = connections.get(ready.connection());
          if (waiting != null) {
            waiting.sendTrackedAck(ready.sequence(), ready.replenishCredit());
          }
        }
        publishTerminal(journaled.terminalState());
      } finally {
        resolveDurability(sequence);
      }
    }

    private void sendTrackedAck(long sequence, boolean replenishCredit) {
      boolean acknowledged = false;
      try {
        acknowledged = sendAck(sequence);
        if (acknowledged && replenishCredit && !halfClosed && !ended) {
          requestMore(1);
        }
      } finally {
        if (outstanding.decrementAndGet() == 0) {
          completeIfDrained();
        }
      }
    }

    private boolean sendAck(long sequence) {
      boolean sent =
          runResponse(
              () ->
                  responses.onNext(
                      PublishBuildToolEventStreamResponse.newBuilder()
                          .setStreamId(streamId)
                          .setSequenceNumber(sequence)
                          .build()));
      if (sent) {
        tracker.acknowledged(sequence);
      } else if (!ended) {
        fail(
            Status.CANCELLED
                .withDescription("BES client stopped accepting acknowledgements")
                .asRuntimeException());
      }
      return sent;
    }

    private boolean start(BesRequestHeader header) {
      BesStreamKey key = header.streamKey();
      boolean refused = false;
      boolean newlyAdmitted = false;
      boolean createdTracker = false;
      StatusRuntimeException startFailure = null;
      synchronized (lifecycleLock) {
        if (ended) {
          return false;
        }
        try {
          synchronized (trackerAdmissionLock) {
            tracker = trackers.get(key);
            if (tracker == null) {
              newlyAdmitted = admittedKeys.add(key);
              if (newlyAdmitted && !resources.tryAdmitStreamKey()) {
                admittedKeys.remove(key);
                refused = true;
              } else {
                boolean installed = false;
                try {
                  tracker = new BesStreamTracker(key);
                  trackers.put(key, tracker);
                  createdTracker = true;
                  installed = true;
                } finally {
                  if (!installed && newlyAdmitted) {
                    admittedKeys.remove(key);
                    resources.rollbackStreamKeyAdmission();
                  }
                }
              }
            }
          }
          if (!refused) {
            streamId =
                StreamId.newBuilder()
                    .setBuildId(header.buildId().orElse(""))
                    .setInvocationId(header.invocationId().orElse(""))
                    .setComponentValue(header.component())
                    .build();
            connection = tracker.openConnectionInterruptibly();
            connections.put(connection, this);
            opening = true;
          }
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          startFailure =
              Status.CANCELLED
                  .withDescription("interrupted while opening BES stream " + key)
                  .withCause(interrupted)
                  .asRuntimeException();
        } catch (RuntimeException failure) {
          startFailure =
              Status.INTERNAL
                  .withDescription("capture failed while opening BES stream " + key)
                  .withCause(failure)
                  .asRuntimeException();
        }
        if (startFailure != null && connection == null && createdTracker) {
          rollbackUnopenedTracker(key, newlyAdmitted);
        }
      }
      if (refused) {
        fail(resourceRefusal("stream key", "admitted stream-key limit"));
        return false;
      }
      if (startFailure != null) {
        fail(startFailure);
        return false;
      }
      boolean opened = false;
      try {
        sink.streamOpened(tracker.key());
        opened = true;
      } catch (RuntimeException failure) {
        fail(
            Status.INTERNAL
                .withDescription("capture sink failed while opening BES stream " + tracker.key())
                .withCause(failure)
                .asRuntimeException());
      } finally {
        finishOpening();
      }
      return opened && !ended;
    }

    private void rollbackUnopenedTracker(BesStreamKey key, boolean newlyAdmitted) {
      synchronized (trackerAdmissionLock) {
        if (tracker.activeConnectionCount() != 0 || !trackers.remove(key, tracker)) {
          return;
        }
        if (newlyAdmitted && admittedKeys.remove(key)) {
          resources.rollbackStreamKeyAdmission();
        }
      }
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
     * <p>This does <em>not</em> close the response stream. Events accepted a moment ago may still
     * be on their way to the journal, and Bazel rejects a stream closed while it is still owed an
     * acknowledgement. The close happens in {@link #completeIfDrained()}, from whichever thread
     * empties the outstanding count last.
     */
    @Override
    public void onCompleted() {
      halfClosed = true;
      completeIfDrained();
    }

    /**
     * Closes the response stream once every accepted event has been journaled and acknowledged, and
     * the client has half-closed.
     *
     * <p>Called from two threads — the gRPC thread on half-close, and the pipeline thread as each
     * journal callback completes — so the close itself is guarded and idempotent. Either one can be
     * the last to arrive, and the stream must close exactly once whichever it is.
     */
    private void completeIfDrained() {
      if (!halfClosed || outstanding.get() > 0) {
        return;
      }
      if (end(BesStreamState.Completion.FINISHED, null)) {
        closeResponses(responses::onCompleted, false);
      }
    }

    private void onCancelled() {
      end(BesStreamState.Completion.ABORTED, "the client cancelled the stream");
    }

    private void fail(StatusRuntimeException status) {
      if (end(BesStreamState.Completion.FAILED, status.getStatus().getDescription())) {
        closeResponses(() -> responses.onError(status), true);
      }
    }

    private boolean end(BesStreamState.Completion how, String detail) {
      boolean won;
      Optional<BesStreamState> finalState = Optional.empty();
      synchronized (lifecycleLock) {
        won = !ended;
        if (won) {
          ended = true;
        }
        if (opening) {
          if (won || precedence(how) > precedence(deferredEnding)) {
            deferredEnding = how;
            deferredEndingDetail = detail;
          }
          return won;
        }
        if (tracker != null && connection != null) {
          if (won) {
            finalState = tracker.end(connection, how, detail);
            connections.remove(connection, this);
          } else if (how == BesStreamState.Completion.FAILED) {
            finalState = tracker.failEpoch(detail);
          }
        }
      }
      try {
        publishTerminal(finalState);
      } finally {
        releaseRpcIfSettled();
      }
      return won;
    }

    private void finishOpening() {
      Optional<BesStreamState> finalState = Optional.empty();
      synchronized (lifecycleLock) {
        opening = false;
        if (deferredEnding != null && tracker != null && connection != null) {
          finalState = tracker.end(connection, deferredEnding, deferredEndingDetail);
          connections.remove(connection, this);
          deferredEnding = null;
          deferredEndingDetail = null;
        }
      }
      try {
        publishTerminal(finalState);
      } finally {
        releaseRpcIfSettled();
      }
    }

    private void publishTerminal(Optional<BesStreamState> finalState) {
      finalState.ifPresent(
          state -> {
            try {
              sink.streamEnded(state);
            } catch (RuntimeException misbehaving) {
              log.warn("capture sink failed while ending BES stream {}", state.key(), misbehaving);
            } finally {
              tracker.terminalPublished();
            }
          });
    }

    private void resolveDurability(long sequence) {
      durabilityPending.remove(sequence);
      releaseRpcIfSettled();
    }

    private void releaseRpcIfSettled() {
      if (ended
          && !opening
          && durabilityPending.isEmpty()
          && rpcReleased.compareAndSet(false, true)) {
        resources.releaseRpc();
      }
    }

    private int precedence(BesStreamState.Completion completion) {
      if (completion == null) {
        return 0;
      }
      return switch (completion) {
        case FAILED -> 3;
        case FINISHED -> 2;
        case ABORTED -> 1;
        case OPEN -> throw new IllegalArgumentException("OPEN is not a connection ending");
      };
    }

    private boolean requestMore(int count) {
      boolean requested = runResponse(() -> responses.request(count));
      if (requested) {
        return true;
      }
      fail(
          Status.CANCELLED
              .withDescription("BES client stopped accepting inbound flow-control requests")
              .asRuntimeException());
      return false;
    }

    /** Serializes observer calls without invoking client code while an internal lock is held. */
    private boolean runResponse(ResponseAction action) {
      ResponseTask task = new ResponseTask(action);
      boolean drain = false;
      synchronized (responseLock) {
        if (responsesClosed) {
          return false;
        }
        responseTasks.addLast(task);
        if (!drainingResponses) {
          drainingResponses = true;
          responseDrainer = Thread.currentThread();
          drain = true;
        } else if (responseDrainer == Thread.currentThread()) {
          // Do not claim a response succeeded merely because it was queued reentrantly from an
          // observer callback. Dropping it makes the caller fail the RPC without advancing the
          // visible acknowledgement watermark.
          responseTasks.removeLastOccurrence(task);
          task.done.countDown();
          return false;
        }
      }
      if (drain) {
        drainResponses();
      } else {
        awaitUninterruptibly(task.done);
      }
      return task.succeeded;
    }

    private void closeResponses(ResponseAction terminal, boolean discardPending) {
      ResponseTask task = new ResponseTask(terminal);
      boolean drain = false;
      synchronized (responseLock) {
        if (responsesClosed) {
          return;
        }
        responsesClosed = true;
        if (discardPending) {
          ResponseTask discarded;
          while ((discarded = responseTasks.pollFirst()) != null) {
            discarded.done.countDown();
          }
        }
        responseTasks.addLast(task);
        if (!drainingResponses) {
          drainingResponses = true;
          responseDrainer = Thread.currentThread();
          drain = true;
        }
      }
      if (drain) {
        drainResponses();
      }
    }

    private void drainResponses() {
      while (true) {
        ResponseTask task;
        synchronized (responseLock) {
          task = responseTasks.pollFirst();
          if (task == null) {
            drainingResponses = false;
            responseDrainer = null;
            return;
          }
        }
        try {
          task.action.run();
          task.succeeded = true;
        } catch (RuntimeException gone) {
          log.debug("BES response observer rejected a callback: {}", gone.toString());
        } finally {
          task.done.countDown();
        }
      }
    }

    private void awaitUninterruptibly(CountDownLatch latch) {
      boolean interrupted = false;
      while (true) {
        try {
          latch.await();
          break;
        } catch (InterruptedException retry) {
          interrupted = true;
        }
      }
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }

  private boolean admitLifecycleKey(BesStreamKey key) {
    synchronized (trackerAdmissionLock) {
      if (admittedKeys.contains(key)) {
        return true;
      }
      if (!resources.tryAdmitStreamKey()) {
        return false;
      }
      boolean inserted = false;
      try {
        inserted = admittedKeys.add(key);
        return true;
      } finally {
        if (!inserted) {
          resources.rollbackStreamKeyAdmission();
        }
      }
    }
  }

  /** A refused RPC still owns any request payload gRPC happens to deliver while it is closing. */
  private static final class RefusedToolStreamObserver implements StreamObserver<RawPayloadLease> {

    @Override
    public void onNext(RawPayloadLease request) {
      request.close();
    }

    @Override
    public void onError(Throwable failure) {}

    @Override
    public void onCompleted() {}
  }

  private StatusRuntimeException resourceRefusal(String resource, String limit) {
    BesResourceSnapshot snapshot = resources.snapshot();
    return Status.RESOURCE_EXHAUSTED
        .withDescription(
            "embedded BES refused "
                + resource
                + ": "
                + limit
                + " reached (active RPCs "
                + snapshot.activeRpcs()
                + "/"
                + snapshot.activeRpcLimit()
                + ", stream keys "
                + snapshot.admittedStreamKeys()
                + "/"
                + snapshot.admittedStreamKeyLimit()
                + ")")
        .asRuntimeException();
  }
}
