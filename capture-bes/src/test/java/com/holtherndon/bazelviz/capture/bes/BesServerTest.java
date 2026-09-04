package com.holtherndon.bazelviz.capture.bes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos;
import com.google.devtools.build.v1.BuildEvent;
import com.google.devtools.build.v1.OrderedBuildEvent;
import com.google.devtools.build.v1.PublishBuildEventGrpc;
import com.google.devtools.build.v1.PublishBuildToolEventStreamRequest;
import com.google.devtools.build.v1.PublishBuildToolEventStreamResponse;
import com.google.devtools.build.v1.PublishLifecycleEventRequest;
import com.google.devtools.build.v1.StreamId;
import com.google.protobuf.Any;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.StreamObserver;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.LongStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The embedded server driven by a real gRPC client over a real socket.
 *
 * <p>These use the generated stub rather than calling the service class directly, because the
 * properties being checked — duplicate handling, flow control, half-close ordering — are properties
 * of the wire behaviour. A test that invoked the handler methods in-process would pass while the
 * server deadlocked against an actual client, which is what happened the first time this was run
 * against Bazel.
 */
class BesServerTest {

  @Test
  @DisplayName("a retransmitted sequence is acknowledged again but journaled only once")
  void duplicateSequencesAreIdempotent() throws Exception {
    RecordingSink sink = new RecordingSink();
    try (BesServer server = new BesServer(sink, BesServerConfig.defaults())) {
      BesEndpoint endpoint = server.start();
      try (Client client = new Client(endpoint)) {
        client.send(1);
        client.send(1);
        client.send(2);
        client.halfCloseAndAwait(10);

        // Three requests, three acknowledgements: Bazel is told about
        // the duplicate rather than left waiting for an ack it will
        // never get.
        assertThat(client.acknowledged()).containsExactly(1L, 1L, 2L);
      }

      assertThat(sink.awaitStreamEnd(5_000)).isTrue();
      // ...but only two frames were journaled.
      assertThat(sink.events()).hasSize(2);
      assertThat(sink.events().stream().map(RawBesEvent::sequence).toList())
          .containsExactly(1L, 2L);

      BesStreamState state = sink.ended().get(0);
      assertThat(state.duplicateCount()).isEqualTo(1);
      assertThat(state.eventsAccepted()).isEqualTo(2);
      assertThat(state.completion()).isEqualTo(BesStreamState.Completion.FINISHED);
      assertThat(state.isCleanlyComplete()).isTrue();
    } finally {
      sink.stop();
    }
  }

  @Test
  @DisplayName("every event is acknowledged even when journaling is slower than the client")
  void backpressureDoesNotLoseEvents() throws Exception {
    // A queue far smaller than the burst, and a journal slow enough that it
    // cannot keep up: the conditions under which an unbounded design would
    // grow without limit and a lossy one would drop.
    RecordingSink sink = new RecordingSink(4);
    sink.setJournalDelay(2);
    try (BesServer server = new BesServer(sink, BesServerConfig.defaults())) {
      BesEndpoint endpoint = server.start();
      int count = 60;
      try (Client client = new Client(endpoint)) {
        for (int sequence = 1; sequence <= count; sequence++) {
          client.send(sequence);
        }
        client.halfCloseAndAwait(60);

        assertThat(client.acknowledged()).hasSize(count);
        // Acknowledgements are in order and complete: 1..count.
        assertThat(client.acknowledged())
            .isEqualTo(LongStream.rangeClosed(1, count).boxed().toList());
      }

      assertThat(sink.awaitStreamEnd(30_000)).isTrue();
      assertThat(sink.events()).hasSize(count);
      assertThat(sink.ended().get(0).isCleanlyComplete()).isTrue();
    } finally {
      sink.stop();
    }
  }

  @Test
  @DisplayName("a client that vanishes mid-stream leaves the stream recorded as aborted")
  void abandonedStreamIsAborted() throws Exception {
    RecordingSink sink = new RecordingSink();
    try (BesServer server = new BesServer(sink, BesServerConfig.defaults())) {
      BesEndpoint endpoint = server.start();
      Client client = new Client(endpoint);
      client.send(1);
      assertThat(sink.awaitEvents(1, 5_000)).isTrue();
      client.cancel();

      assertThat(sink.awaitStreamEnd(5_000)).isTrue();
      BesStreamState state = sink.ended().get(0);
      assertThat(state.completion()).isEqualTo(BesStreamState.Completion.ABORTED);
      // The event that did arrive is still ours, and still counted.
      assertThat(state.eventsAccepted()).isEqualTo(1);
      assertThat(state.isCleanlyComplete()).isFalse();
    } finally {
      sink.stop();
    }
  }

  @Test
  @DisplayName("cancellation waits for accepted durability before publishing its terminal state")
  void cancelledStreamSettlesAcceptedWorkBeforeTerminalPublication() throws Exception {
    RecordingSink sink = new RecordingSink();
    sink.blockJournaling();
    try (BesServer server = new BesServer(sink, BesServerConfig.defaults())) {
      BesEndpoint endpoint = server.start();
      try (Client client = new Client(endpoint)) {
        client.send(1);
        assertThat(sink.awaitEvents(1, 5_000)).isTrue();
        client.cancel();

        Thread.sleep(100);
        assertThat(sink.ended()).isEmpty();
        assertThat(server.awaitQuiescence(Duration.ofMillis(300))).isFalse();

        sink.releaseJournaling();
        assertThat(sink.awaitStreamEnd(5_000)).isTrue();
        BesStreamState state = sink.ended().getFirst();
        assertThat(state.completion()).isEqualTo(BesStreamState.Completion.ABORTED);
        assertThat(state.highestContiguous()).isEqualTo(1);
        assertThat(server.awaitQuiescence(Duration.ofSeconds(2))).isTrue();
      }
    } finally {
      sink.stop();
    }
  }

  @Test
  @DisplayName("a pipeline that refuses events fails the RPC rather than dropping them")
  void rejectionFailsTheRpc() throws Exception {
    RecordingSink sink = new RecordingSink();
    sink.startRejecting();
    try (BesServer server = new BesServer(sink, BesServerConfig.defaults())) {
      BesEndpoint endpoint = server.start();
      try (Client client = new Client(endpoint)) {
        client.send(1);
        client.awaitTermination(10);

        // The client is told. A capture that cannot store an event must
        // never let the sender believe it was stored.
        assertThat(client.error()).isNotNull();
        assertThat(client.acknowledged()).isEmpty();
      }
    } finally {
      sink.stop();
    }
  }

  @Test
  @DisplayName("acknowledgements echo the exact sequence numbers Bazel sent")
  void acknowledgementsEchoExactSequenceNumbers() throws Exception {
    // This is not a stylistic preference. An acknowledgement carrying the
    // wrong sequence_number does not merely fail the upload: on Bazel 6.5
    // and 9.2 it kills the Bazel server with a fatal internal error, taking
    // the user's analysis cache with it. 7.6 and 8.4 fail gracefully, so a
    // regression here would look harmless on half the supported range.
    RecordingSink sink = new RecordingSink();
    try (BesServer server = new BesServer(sink, BesServerConfig.defaults())) {
      BesEndpoint endpoint = server.start();
      try (Client client = new Client(endpoint)) {
        for (int sequence = 1; sequence <= 25; sequence++) {
          client.send(sequence);
        }
        client.halfCloseAndAwait(20);

        assertThat(client.acknowledged()).isEqualTo(LongStream.rangeClosed(1, 25).boxed().toList());
      }
    } finally {
      sink.stop();
    }
  }

  @Test
  @DisplayName("a stream replayed on a new connection is recognised, not journaled twice")
  void replayedStreamIsNotJournaledTwice() throws Exception {
    // Bazel's uploader outlives its client process. If it loses the
    // connection it opens another and replays the stream from sequence 1
    // with the same StreamId. Tracking state per connection would journal
    // every one of those events a second time.
    RecordingSink sink = new RecordingSink();
    try (BesServer server = new BesServer(sink, BesServerConfig.defaults())) {
      BesEndpoint endpoint = server.start();

      try (Client first = new Client(endpoint)) {
        first.send(1);
        first.send(2);
        first.send(3);
        assertThat(sink.awaitEvents(3, 5_000)).isTrue();
        first.cancel();
      }
      assertThat(sink.awaitStreamEnd(5_000)).isTrue();

      try (Client replay = new Client(endpoint)) {
        for (int sequence = 1; sequence <= 5; sequence++) {
          replay.send(sequence);
        }
        replay.halfCloseAndAwait(20);

        // Every replayed sequence is acknowledged, so the client can
        // finish; only the two it had never sent before are journaled.
        assertThat(replay.acknowledged()).contains(1L, 2L, 3L, 4L, 5L);
      }

      assertThat(sink.events()).hasSize(5);
      assertThat(sink.events().stream().map(RawBesEvent::sequence).toList())
          .containsExactly(1L, 2L, 3L, 4L, 5L);
    } finally {
      sink.stop();
    }
  }

  @Test
  @DisplayName("a reconnect waits for the original append and an old ending cannot finish it")
  void overlappingReconnectWaitsForOriginalDurability() throws Exception {
    RecordingSink sink = new RecordingSink();
    sink.blockJournaling();
    try (BesServer server = new BesServer(sink, BesServerConfig.defaults())) {
      BesEndpoint endpoint = server.start();
      Client original = new Client(endpoint);
      Client replay = new Client(endpoint);
      try {
        original.send(1);
        assertThat(sink.awaitEvents(1, 5_000)).isTrue();
        replay.send(1);
        Thread.sleep(100);
        assertThat(replay.acknowledged()).isEmpty();
        assertThat(sink.events()).hasSize(1);

        original.cancel();
        Thread.sleep(100);
        assertThat(sink.ended()).isEmpty();

        sink.releaseJournaling();
        assertThat(replay.awaitAcknowledgements(1, 5)).isTrue();
        replay.send(2);
        replay.halfCloseAndAwait(10);

        assertThat(replay.acknowledged()).containsExactly(1L, 2L);
        assertThat(sink.events().stream().map(RawBesEvent::sequence).toList())
            .containsExactly(1L, 2L);
        assertThat(sink.awaitStreamEnd(5_000)).isTrue();
        assertThat(sink.ended().getLast().completion())
            .isEqualTo(BesStreamState.Completion.FINISHED);
      } finally {
        original.close();
        replay.close();
      }
    } finally {
      sink.stop();
    }
  }

  @Test
  @DisplayName("gap-closing acknowledgements return to the connections that delivered each event")
  void gapClosingAcknowledgementsAreConnectionAware() throws Exception {
    ReorderingSink sink = new ReorderingSink();
    try (BesServer server = new BesServer(sink, BesServerConfig.defaults())) {
      BesEndpoint endpoint = server.start();
      Client first = new Client(endpoint);
      Client second = new Client(endpoint);
      try {
        first.send(1);
        assertThat(sink.firstSubmitEntered.await(5, TimeUnit.SECONDS)).isTrue();
        second.send(2);
        assertThat(sink.secondJournaled.await(5, TimeUnit.SECONDS)).isTrue();
        second.halfClose();
        Thread.sleep(100);
        assertThat(first.acknowledged()).isEmpty();
        assertThat(second.acknowledged()).isEmpty();
        assertThat(second.isDone()).isFalse();

        sink.releaseFirst.countDown();
        assertThat(first.awaitAcknowledgements(1, 5)).isTrue();
        assertThat(second.awaitAcknowledgements(1, 5)).isTrue();
        assertThat(first.acknowledged()).containsExactly(1L);
        assertThat(second.acknowledged()).containsExactly(2L);
        second.awaitTermination(5);
        first.halfCloseAndAwait(5);
        assertThat(sink.ended.await(5, TimeUnit.SECONDS)).isTrue();
      } finally {
        sink.releaseFirst.countDown();
        first.close();
        second.close();
      }
    }
  }

  @Test
  @DisplayName("more than one flow window of pending duplicates stays bounded and drains")
  void pendingDuplicateBurstIsFlowControlledAndDrains() throws Exception {
    RecordingSink sink = new RecordingSink();
    sink.blockJournaling();
    try (BesServer server = new BesServer(sink, BesServerConfig.defaults())) {
      BesEndpoint endpoint = server.start();
      Client original = new Client(endpoint);
      Client replay = new Client(endpoint);
      try {
        original.send(1);
        assertThat(sink.awaitEvents(1, 5_000)).isTrue();
        for (int duplicate = 0; duplicate < 80; duplicate++) {
          replay.send(1);
        }
        Thread.sleep(100);
        assertThat(replay.acknowledged()).isEmpty();
        assertThat(sink.events()).hasSize(1);

        sink.releaseJournaling();
        assertThat(replay.awaitAcknowledgements(80, 10)).isTrue();
        replay.halfCloseAndAwait(10);
        original.cancel();

        assertThat(replay.acknowledged()).hasSize(80).containsOnly(1L);
        assertThat(sink.events()).hasSize(1);
        assertThat(sink.awaitStreamEnd(5_000)).isTrue();
      } finally {
        original.close();
        replay.close();
      }
    } finally {
      sink.stop();
    }
  }

  @Test
  @DisplayName("journal rejection fails the original and every repeated pending replay")
  void journalFailureRejectsEveryPendingDuplicate() throws Exception {
    RecordingSink sink = new RecordingSink();
    sink.blockJournaling();
    try (BesServer server = new BesServer(sink, BesServerConfig.defaults())) {
      BesEndpoint endpoint = server.start();
      Client original = new Client(endpoint);
      Client replay = new Client(endpoint);
      try {
        original.send(1);
        assertThat(sink.awaitEvents(1, 5_000)).isTrue();
        replay.send(1);
        replay.send(1);
        sink.failJournaling(new IllegalStateException("journal failed"));

        original.awaitTermination(5);
        replay.awaitTermination(5);
        assertThat(Status.fromThrowable(original.error()).getCode())
            .isEqualTo(Status.Code.UNAVAILABLE);
        assertThat(Status.fromThrowable(replay.error()).getCode())
            .isEqualTo(Status.Code.UNAVAILABLE);
        assertThat(server.awaitQuiescence(Duration.ofSeconds(2))).isTrue();
      } finally {
        original.close();
        replay.close();
      }
    } finally {
      sink.stop();
    }
  }

  @Test
  @DisplayName("the exact active-RPC boundary refuses visibly and recovers")
  void activeRpcBoundaryRefusesAndRecovers() throws Exception {
    RecordingSink sink = new RecordingSink();
    sink.blockJournaling();
    BesResourceLimits limits = new BesResourceLimits(1_048_576, 2, 8, 4, 8, Duration.ofMillis(20));
    BesServerConfig config = BesServerConfig.defaults().withResourceLimits(limits);
    try (BesServer server = new BesServer(sink, config)) {
      BesEndpoint endpoint = server.start();
      Client first = new Client(endpoint, "build-1");
      Client second = null;
      Client refused = null;
      try {
        first.send(1);
        assertThat(sink.awaitEvents(1, 5_000)).isTrue();
        second = new Client(endpoint, "build-2");
        second.send(1);
        assertThat(sink.awaitEvents(2, 5_000)).isTrue();
        refused = new Client(endpoint, "build-3");
        refused.send(1);
        refused.awaitTermination(5);
        assertThat(Status.fromThrowable(refused.error()).getCode())
            .isEqualTo(Status.Code.RESOURCE_EXHAUSTED);
        assertThat(server.resourceSnapshot().activeRpcs()).isEqualTo(2);
        assertThat(server.resourceSnapshot().rpcRefusals()).isEqualTo(1);

        sink.releaseJournaling();
        first.halfCloseAndAwait(10);
        second.halfCloseAndAwait(10);
        assertThat(server.awaitQuiescence(Duration.ofSeconds(2))).isTrue();

        try (Client recovered = new Client(endpoint, "build-4")) {
          recovered.send(1);
          recovered.halfCloseAndAwait(10);
          assertThat(recovered.error()).isNull();
        }
      } finally {
        first.close();
        if (second != null) {
          second.close();
        }
        if (refused != null) {
          refused.close();
        }
      }
    } finally {
      sink.stop();
    }
  }

  @Test
  @DisplayName("distinct stream keys stop at the exact boundary without evicting admitted keys")
  void streamKeyBoundaryRetainsAdmittedKeys() throws Exception {
    RecordingSink sink = new RecordingSink();
    BesResourceLimits limits = new BesResourceLimits(1_048_576, 4, 2, 4, 8, Duration.ofMillis(20));
    try (BesServer server =
        new BesServer(sink, BesServerConfig.defaults().withResourceLimits(limits))) {
      BesEndpoint endpoint = server.start();
      for (String build : List.of("build-a", "build-b")) {
        try (Client client = new Client(endpoint, build)) {
          client.send(1);
          client.halfCloseAndAwait(10);
          assertThat(client.error()).isNull();
        }
      }
      try (Client refused = new Client(endpoint, "build-c")) {
        refused.send(1);
        refused.awaitTermination(5);
        assertThat(Status.fromThrowable(refused.error()).getCode())
            .isEqualTo(Status.Code.RESOURCE_EXHAUSTED);
      }
      assertThat(server.resourceSnapshot().admittedStreamKeys()).isEqualTo(2);
      assertThat(server.resourceSnapshot().streamKeyRefusals()).isEqualTo(1);

      // Reusing an admitted identity does not consume another key or require eviction.
      try (Client replay = new Client(endpoint, "build-a")) {
        replay.send(1);
        replay.halfCloseAndAwait(10);
        assertThat(replay.error()).isNull();
        assertThat(replay.acknowledged()).containsExactly(1L);
      }
      assertThat(server.resourceSnapshot().admittedStreamKeys()).isEqualTo(2);
    } finally {
      sink.stop();
    }
  }

  @Test
  @DisplayName("lifecycle and tool RPCs share one exact retained stream-key boundary")
  void lifecycleAndToolStreamsShareKeyAdmission() throws Exception {
    RecordingSink sink = new RecordingSink();
    BesResourceLimits limits = new BesResourceLimits(1_048_576, 4, 2, 4, 8, Duration.ofMillis(20));
    try (BesServer server =
        new BesServer(sink, BesServerConfig.defaults().withResourceLimits(limits))) {
      BesEndpoint endpoint = server.start();
      publishLifecycle(endpoint, "build-a");
      try (Client second = new Client(endpoint, "build-b")) {
        second.send(1);
        second.halfCloseAndAwait(10);
      }

      assertThatThrownBy(() -> publishLifecycle(endpoint, "build-c"))
          .isInstanceOfSatisfying(
              StatusRuntimeException.class,
              failure ->
                  assertThat(failure.getStatus().getCode())
                      .isEqualTo(Status.Code.RESOURCE_EXHAUSTED));
      // A tool stream may reuse the identity first admitted by lifecycle traffic.
      try (Client sameAsLifecycle = new Client(endpoint, "build-a")) {
        sameAsLifecycle.send(1);
        sameAsLifecycle.halfCloseAndAwait(10);
        assertThat(sameAsLifecycle.error()).isNull();
      }
      assertThat(server.resourceSnapshot().admittedStreamKeys()).isEqualTo(2);
      assertThat(server.resourceSnapshot().streamKeyRefusals()).isEqualTo(1);
    } finally {
      sink.stop();
    }
  }

  @Test
  @DisplayName("quiescence waits until terminal state publication returns")
  void quiescenceIncludesTerminalStatePublication() throws Exception {
    RecordingSink sink = new RecordingSink();
    sink.blockStreamEnd();
    try (BesServer server = new BesServer(sink, BesServerConfig.defaults())) {
      BesEndpoint endpoint = server.start();
      try (Client client = new Client(endpoint)) {
        client.send(1);
        assertThat(sink.awaitEvents(1, 5_000)).isTrue();
        Thread ending = Thread.ofPlatform().start(() -> client.halfCloseAndAwaitUnchecked(10));
        assertThat(sink.awaitStreamEndCallback(5_000)).isTrue();
        assertThat(server.awaitQuiescence(Duration.ofMillis(300))).isFalse();

        sink.releaseStreamEnd();
        ending.join(5_000);
        assertThat(ending.isAlive()).isFalse();
        assertThat(server.awaitQuiescence(Duration.ofSeconds(2))).isTrue();
      }
    } finally {
      sink.stop();
    }
  }

  @Test
  @DisplayName("forced shutdown waits for an active stream's terminal callback")
  void forcedShutdownWaitsForTerminalCallback() throws Exception {
    RecordingSink sink = new RecordingSink();
    sink.blockStreamEnd();
    BesServerConfig defaults = BesServerConfig.defaults();
    BesServerConfig config =
        new BesServerConfig(
            0,
            defaults.maxMessageBytes(),
            Duration.ofMillis(200),
            defaults.permitKeepAliveEvery(),
            defaults.resourceLimits());
    try (BesServer server = new BesServer(sink, config)) {
      BesEndpoint endpoint = server.start();
      try (Client client = new Client(endpoint)) {
        client.send(1);
        assertThat(client.awaitAcknowledgements(1, 5)).isTrue();
        AtomicBoolean terminated = new AtomicBoolean();
        Thread closing = Thread.ofPlatform().start(() -> terminated.set(server.shutdownAndAwait()));

        assertThat(sink.awaitStreamEndCallback(5_000)).isTrue();
        assertThat(closing.isAlive()).isTrue();
        assertThat(terminated).isFalse();
        sink.releaseStreamEnd();

        closing.join(5_000);
        assertThat(closing.isAlive()).isFalse();
        assertThat(terminated).isTrue();
      }
    } finally {
      sink.stop();
    }
  }

  @Test
  @DisplayName("the endpoint is always loopback, and a non-loopback one cannot be built")
  void bindingIsLoopbackOnly() throws Exception {
    RecordingSink sink = new RecordingSink();
    try (BesServer server = new BesServer(sink, BesServerConfig.defaults())) {
      BesEndpoint endpoint = server.start();
      assertThat(endpoint.host()).isEqualTo(BesEndpoint.LOOPBACK);
      assertThat(endpoint.besBackendUri()).startsWith("grpc://127.0.0.1:");
      assertThat(endpoint.port()).isPositive();
    } finally {
      sink.stop();
    }

    assertThatThrownBy(() -> new BesEndpoint("10.0.0.5", 8080, "token"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("loopback only");
  }

  // ----------------------------------------------------------------- client

  private static final class ReorderingSink implements RawEventSink {

    private final CountDownLatch firstSubmitEntered = new CountDownLatch(1);
    private final CountDownLatch releaseFirst = new CountDownLatch(1);
    private final CountDownLatch secondJournaled = new CountDownLatch(1);
    private final CountDownLatch ended = new CountDownLatch(1);

    @Override
    public void submit(RawBesEvent event, SubmissionCallback callback) throws InterruptedException {
      try {
        if (event.sequence() == 1) {
          firstSubmitEntered.countDown();
          releaseFirst.await();
        }
        callback.onJournaled();
        if (event.sequence() == 2) {
          secondJournaled.countDown();
        }
      } finally {
        event.close();
      }
    }

    @Override
    public void streamOpened(BesStreamKey key) {}

    @Override
    public void streamEnded(BesStreamState finalState) {
      ended.countDown();
    }
  }

  private static void publishLifecycle(BesEndpoint endpoint, String buildId) {
    ManagedChannel channel =
        NettyChannelBuilder.forAddress(endpoint.host(), endpoint.port()).usePlaintext().build();
    try {
      PublishLifecycleEventRequest request =
          PublishLifecycleEventRequest.newBuilder()
              .setBuildEvent(
                  OrderedBuildEvent.newBuilder()
                      .setStreamId(
                          StreamId.newBuilder()
                              .setBuildId(buildId)
                              .setInvocationId("inv-1")
                              .setComponent(StreamId.BuildComponent.TOOL))
                      .setSequenceNumber(1)
                      .setEvent(BuildEvent.getDefaultInstance()))
              .setProjectId("bbv-test")
              .build();
      PublishBuildEventGrpc.newBlockingStub(channel).publishLifecycleEvent(request);
    } finally {
      channel.shutdownNow();
    }
  }

  /** A real gRPC client speaking the same protocol Bazel does. */
  private static final class Client implements AutoCloseable {

    private final ManagedChannel channel;
    private final StreamObserver<PublishBuildToolEventStreamRequest> requests;
    private final List<Long> acknowledged = new ArrayList<>();
    private final CountDownLatch done = new CountDownLatch(1);
    private final AtomicReference<Throwable> error = new AtomicReference<>();
    private final String buildId;

    Client(BesEndpoint endpoint) {
      this(endpoint, "build-1");
    }

    Client(BesEndpoint endpoint, String buildId) {
      this.buildId = buildId;
      this.channel =
          NettyChannelBuilder.forAddress(endpoint.host(), endpoint.port()).usePlaintext().build();
      this.requests =
          PublishBuildEventGrpc.newStub(channel)
              .publishBuildToolEventStream(
                  new StreamObserver<>() {
                    @Override
                    public void onNext(PublishBuildToolEventStreamResponse response) {
                      synchronized (acknowledged) {
                        acknowledged.add(response.getSequenceNumber());
                      }
                    }

                    @Override
                    public void onError(Throwable failure) {
                      error.set(failure);
                      done.countDown();
                    }

                    @Override
                    public void onCompleted() {
                      done.countDown();
                    }
                  });
    }

    void send(long sequence) {
      BuildEventStreamProtos.BuildEvent inner =
          BuildEventStreamProtos.BuildEvent.newBuilder()
              .setId(
                  BuildEventStreamProtos.BuildEventId.newBuilder()
                      .setProgress(
                          BuildEventStreamProtos.BuildEventId.ProgressId.newBuilder()
                              .setOpaqueCount((int) sequence)))
              .setProgress(
                  BuildEventStreamProtos.Progress.newBuilder().setStderr("event " + sequence))
              .build();
      requests.onNext(
          PublishBuildToolEventStreamRequest.newBuilder()
              .setOrderedBuildEvent(
                  OrderedBuildEvent.newBuilder()
                      .setStreamId(
                          StreamId.newBuilder()
                              .setBuildId(buildId)
                              .setInvocationId("inv-1")
                              .setComponent(StreamId.BuildComponent.TOOL))
                      .setSequenceNumber(sequence)
                      .setEvent(
                          BuildEvent.newBuilder()
                              .setBazelEvent(
                                  Any.newBuilder()
                                      .setTypeUrl(
                                          "type.googleapis.com/build_event_stream.BuildEvent")
                                      .setValue(inner.toByteString()))))
              .setProjectId("bbv-test")
              .build());
    }

    void halfCloseAndAwait(int seconds) throws InterruptedException {
      halfClose();
      awaitTermination(seconds);
    }

    void halfClose() {
      requests.onCompleted();
    }

    void halfCloseAndAwaitUnchecked(int seconds) {
      try {
        halfCloseAndAwait(seconds);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      }
    }

    void awaitTermination(int seconds) throws InterruptedException {
      assertThat(done.await(seconds, TimeUnit.SECONDS))
          .describedAs("the stream did not finish within %d seconds", seconds)
          .isTrue();
    }

    boolean awaitAcknowledgements(int count, int seconds) throws InterruptedException {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
      while (System.nanoTime() < deadline) {
        if (acknowledged().size() >= count) {
          return true;
        }
        Thread.sleep(5);
      }
      return acknowledged().size() >= count;
    }

    void cancel() {
      requests.onError(new IllegalStateException("client is going away"));
    }

    List<Long> acknowledged() {
      synchronized (acknowledged) {
        return List.copyOf(acknowledged);
      }
    }

    Throwable error() {
      return error.get();
    }

    boolean isDone() {
      return done.getCount() == 0;
    }

    @Override
    public void close() {
      channel.shutdownNow();
    }
  }
}
