package com.holtherndon.bazelviz.capture.bes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos;
import com.google.devtools.build.v1.BuildEvent;
import com.google.devtools.build.v1.OrderedBuildEvent;
import com.google.devtools.build.v1.PublishBuildEventGrpc;
import com.google.devtools.build.v1.PublishBuildToolEventStreamRequest;
import com.google.devtools.build.v1.PublishBuildToolEventStreamResponse;
import com.google.devtools.build.v1.StreamId;
import com.google.protobuf.Any;
import io.grpc.ManagedChannel;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.StreamObserver;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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

  /** A real gRPC client speaking the same protocol Bazel does. */
  private static final class Client implements AutoCloseable {

    private final ManagedChannel channel;
    private final StreamObserver<PublishBuildToolEventStreamRequest> requests;
    private final List<Long> acknowledged = new ArrayList<>();
    private final CountDownLatch done = new CountDownLatch(1);
    private final AtomicReference<Throwable> error = new AtomicReference<>();

    Client(BesEndpoint endpoint) {
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
                              .setBuildId("build-1")
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
      requests.onCompleted();
      awaitTermination(seconds);
    }

    void awaitTermination(int seconds) throws InterruptedException {
      assertThat(done.await(seconds, TimeUnit.SECONDS))
          .describedAs("the stream did not finish within %d seconds", seconds)
          .isTrue();
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

    @Override
    public void close() {
      channel.shutdownNow();
    }
  }
}
