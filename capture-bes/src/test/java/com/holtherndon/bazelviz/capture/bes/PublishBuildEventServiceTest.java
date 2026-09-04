package com.holtherndon.bazelviz.capture.bes;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.devtools.build.v1.BuildEvent;
import com.google.devtools.build.v1.OrderedBuildEvent;
import com.google.devtools.build.v1.PublishBuildToolEventStreamRequest;
import com.google.devtools.build.v1.PublishBuildToolEventStreamResponse;
import com.google.devtools.build.v1.PublishLifecycleEventRequest;
import com.google.devtools.build.v1.StreamId;
import com.google.protobuf.Empty;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

final class PublishBuildEventServiceTest {

  @Test
  @DisplayName(
      "initial flow-control refusal releases the admitted RPC even when onError also throws")
  void initialFlowControlFailureReleasesRpc() {
    HoldingSink sink = new HoldingSink();
    BesResources resources = resources();
    PublishBuildEventService service = service(sink, resources);
    ThrowingResponses responses = new ThrowingResponses(1, true);

    service.publishBuildToolEventStream(responses);

    assertThat(resources.snapshot().activeRpcs()).isZero();
    assertThat(responses.failure.get()).isNotNull();
  }

  @Test
  @DisplayName("a request failure after submit does not release the sink-owned payload")
  void postSubmitFlowControlFailurePreservesSinkOwnership() throws Exception {
    HoldingSink sink = new HoldingSink();
    BesResources resources = resources();
    PublishBuildEventService service = service(sink, resources);
    ThrowingResponses responses = new ThrowingResponses(2, false);
    StreamObserver<RawPayloadLease> requests = service.publishBuildToolEventStream(responses);
    byte[] bytes = toolRequest(1).toByteArray();

    requests.onNext(resources.payloadBudget().acquire(bytes));

    assertThat(sink.submitted.await(5, TimeUnit.SECONDS))
        .withFailMessage(
            "submission did not arrive; response failure was %s", responses.failure.get())
        .isTrue();
    assertThat(resources.snapshot().activeRpcs()).isEqualTo(1);
    assertThat(resources.payloadBudget().retainedBytes()).isEqualTo(bytes.length);
    sink.journal();
    assertThat(resources.payloadBudget().retainedBytes()).isZero();
    assertThat(resources.snapshot().activeRpcs()).isZero();
  }

  @Test
  @DisplayName("interrupting lifecycle durability wait leaves the accepted payload with its sink")
  void interruptedLifecycleWaitPreservesSinkOwnership() throws Exception {
    HoldingSink sink = new HoldingSink();
    BesResources resources = resources();
    PublishBuildEventService service = service(sink, resources);
    byte[] bytes = lifecycleRequest().toByteArray();
    UnaryResponses responses = new UnaryResponses();
    Thread handler =
        Thread.ofPlatform()
            .start(
                () -> {
                  try {
                    service.publishLifecycleEvent(
                        resources.payloadBudget().acquire(bytes), responses);
                  } catch (InterruptedException impossibleBeforeSubmit) {
                    Thread.currentThread().interrupt();
                  } catch (RetainedPayloadBudget.PayloadRefusedException impossible) {
                    throw new AssertionError(impossible);
                  }
                });

    assertThat(sink.submitted.await(2, TimeUnit.SECONDS)).isTrue();
    handler.interrupt();
    handler.join(2_000);

    assertThat(handler.isAlive()).isFalse();
    assertThat(responses.failure.get()).isNotNull();
    assertThat(resources.snapshot().activeRpcs()).isZero();
    assertThat(resources.payloadBudget().retainedBytes()).isEqualTo(bytes.length);
    sink.journal();
    assertThat(resources.payloadBudget().retainedBytes()).isZero();
  }

  @Test
  @DisplayName("an unchecked sink submission failure rejects tracker state and releases the lease")
  void uncheckedSinkFailureCleansUpAcceptedSequence() throws Exception {
    HoldingSink sink = new HoldingSink();
    sink.throwOnSubmit = true;
    BesResources resources = resources();
    PublishBuildEventService service = service(sink, resources);
    ThrowingResponses responses = new ThrowingResponses(Integer.MAX_VALUE, false);
    StreamObserver<RawPayloadLease> requests = service.publishBuildToolEventStream(responses);
    byte[] bytes = toolRequest(1).toByteArray();

    requests.onNext(resources.payloadBudget().acquire(bytes));

    assertThat(resources.snapshot().activeRpcs()).isZero();
    assertThat(resources.payloadBudget().retainedBytes()).isZero();
    assertThat(responses.failure.get()).isNotNull();
  }

  @Test
  @DisplayName("a rejected acknowledgement never advances the visible watermark")
  void acknowledgementFailureKeepsWatermarkHonestAndReleasesOwnership() throws Exception {
    HoldingSink sink = new HoldingSink();
    BesResources resources = resources();
    PublishBuildEventService service = service(sink, resources);
    ThrowingResponses responses = new ThrowingResponses(Integer.MAX_VALUE, false, true);
    StreamObserver<RawPayloadLease> requests = service.publishBuildToolEventStream(responses);
    byte[] bytes = toolRequest(1).toByteArray();

    requests.onNext(resources.payloadBudget().acquire(bytes));
    assertThat(sink.submitted.await(5, TimeUnit.SECONDS))
        .withFailMessage(
            "submission did not arrive; response failure was %s", responses.failure.get())
        .isTrue();
    sink.journal();

    assertThat(responses.failure.get()).isNotNull();
    assertThat(resources.snapshot().activeRpcs()).isZero();
    assertThat(resources.payloadBudget().retainedBytes()).isZero();
    assertThat(sink.finalState.get()).isNotNull();
    assertThat(sink.finalState.get().highestAcknowledged()).isZero();
    assertThat(sink.finalState.get().completion()).isEqualTo(BesStreamState.Completion.FAILED);
  }

  @Test
  @DisplayName("cancellation cannot end or leak a connection while stream-open publication runs")
  void cancellationDuringStreamOpenIsOrderedAndReleased() throws Exception {
    HoldingSink sink = new HoldingSink();
    sink.blockStreamOpen();
    BesResources resources = resources();
    PublishBuildEventService service = service(sink, resources);
    ThrowingResponses responses = new ThrowingResponses(Integer.MAX_VALUE, false);
    StreamObserver<RawPayloadLease> requests = service.publishBuildToolEventStream(responses);
    byte[] bytes = toolRequest(1).toByteArray();
    RawPayloadLease lease = resources.payloadBudget().acquire(bytes);
    Thread delivery = Thread.ofPlatform().start(() -> requests.onNext(lease));

    assertThat(sink.streamOpenEntered.await(5, TimeUnit.SECONDS))
        .withFailMessage(
            "stream-open callback did not run; response failure was %s", responses.failure.get())
        .isTrue();
    responses.cancelHandler.run();
    assertThat(sink.finalState).hasValue(null);
    assertThat(resources.snapshot().activeRpcs()).isEqualTo(1);

    sink.releaseStreamOpen.countDown();
    delivery.join(5_000);
    assertThat(delivery.isAlive()).isFalse();
    assertThat(sink.finalState.get().completion()).isEqualTo(BesStreamState.Completion.ABORTED);
    assertThat(resources.snapshot().activeRpcs()).isZero();
    assertThat(resources.payloadBudget().retainedBytes()).isZero();
  }

  @Test
  @DisplayName("cancellation before the first message does not consume a stream-key admission")
  void cancellationBeforeFirstMessageDoesNotAdmitKey() throws Exception {
    HoldingSink sink = new HoldingSink();
    BesResources resources = resources();
    PublishBuildEventService service = service(sink, resources);
    ThrowingResponses responses = new ThrowingResponses(Integer.MAX_VALUE, false);
    StreamObserver<RawPayloadLease> requests = service.publishBuildToolEventStream(responses);
    byte[] bytes = toolRequest(1).toByteArray();

    responses.cancelHandler.run();
    requests.onNext(resources.payloadBudget().acquire(bytes));

    assertThat(resources.snapshot().activeRpcs()).isZero();
    assertThat(resources.snapshot().admittedStreamKeys()).isZero();
    assertThat(resources.payloadBudget().retainedBytes()).isZero();
    assertThat(sink.submitted.getCount()).isEqualTo(1);
  }

  private static BesResources resources() {
    return new BesResources(new BesResourceLimits(1_048_576, 2, 4, 2, 4, Duration.ofMillis(20)));
  }

  private static PublishBuildEventService service(HoldingSink sink, BesResources resources) {
    return new PublishBuildEventService(sink, 1_048_576, () -> 1L, resources);
  }

  private static PublishBuildToolEventStreamRequest toolRequest(long sequence) {
    return PublishBuildToolEventStreamRequest.newBuilder()
        .setOrderedBuildEvent(ordered(sequence))
        .setProjectId("bbv-test")
        .build();
  }

  private static PublishLifecycleEventRequest lifecycleRequest() {
    return PublishLifecycleEventRequest.newBuilder()
        .setBuildEvent(ordered(1))
        .setProjectId("bbv-test")
        .build();
  }

  private static OrderedBuildEvent ordered(long sequence) {
    return OrderedBuildEvent.newBuilder()
        .setStreamId(
            StreamId.newBuilder()
                .setBuildId("build")
                .setInvocationId("invocation")
                .setComponent(StreamId.BuildComponent.TOOL))
        .setSequenceNumber(sequence)
        .setEvent(BuildEvent.getDefaultInstance())
        .build();
  }

  private static final class HoldingSink implements RawEventSink {

    private final CountDownLatch submitted = new CountDownLatch(1);
    private RawBesEvent event;
    private SubmissionCallback callback;
    private boolean throwOnSubmit;
    private final AtomicReference<BesStreamState> finalState = new AtomicReference<>();
    private CountDownLatch streamOpenEntered = new CountDownLatch(0);
    private CountDownLatch releaseStreamOpen = new CountDownLatch(0);

    @Override
    public synchronized void submit(RawBesEvent value, SubmissionCallback completion) {
      if (throwOnSubmit) {
        throw new IllegalStateException("sink failed while accepting");
      }
      event = value;
      callback = completion;
      submitted.countDown();
    }

    synchronized void journal() {
      try {
        callback.onJournaled();
      } finally {
        event.close();
      }
    }

    @Override
    public void streamOpened(BesStreamKey key) {
      streamOpenEntered.countDown();
      awaitUninterruptibly(releaseStreamOpen);
    }

    @Override
    public void streamEnded(BesStreamState state) {
      finalState.set(state);
    }

    private void blockStreamOpen() {
      streamOpenEntered = new CountDownLatch(1);
      releaseStreamOpen = new CountDownLatch(1);
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
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

  private static final class UnaryResponses implements StreamObserver<Empty> {

    private final AtomicReference<Throwable> failure = new AtomicReference<>();

    @Override
    public void onNext(Empty value) {}

    @Override
    public void onError(Throwable problem) {
      failure.set(problem);
    }

    @Override
    public void onCompleted() {}
  }

  private static final class ThrowingResponses
      extends ServerCallStreamObserver<PublishBuildToolEventStreamResponse> {

    private final int throwOnRequest;
    private final boolean throwOnError;
    private final boolean throwOnAck;
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private int requestCount;
    private Runnable cancelHandler;

    private ThrowingResponses(int throwOnRequest, boolean throwOnError) {
      this(throwOnRequest, throwOnError, false);
    }

    private ThrowingResponses(int throwOnRequest, boolean throwOnError, boolean throwOnAck) {
      this.throwOnRequest = throwOnRequest;
      this.throwOnError = throwOnError;
      this.throwOnAck = throwOnAck;
    }

    @Override
    public boolean isCancelled() {
      return false;
    }

    @Override
    public void setOnCancelHandler(Runnable handler) {
      cancelHandler = handler;
    }

    @Override
    public void setCompression(String compression) {}

    @Override
    public boolean isReady() {
      return true;
    }

    @Override
    public void setOnReadyHandler(Runnable handler) {}

    @Override
    public void disableAutoInboundFlowControl() {}

    @Override
    public void disableAutoRequest() {}

    @Override
    public void request(int count) {
      requestCount++;
      if (requestCount == throwOnRequest) {
        throw new IllegalStateException("request failed");
      }
    }

    @Override
    public void setMessageCompression(boolean enabled) {}

    @Override
    public void onNext(PublishBuildToolEventStreamResponse response) {
      if (throwOnAck) {
        throw new IllegalStateException("acknowledgement failed");
      }
    }

    @Override
    public void onError(Throwable problem) {
      failure.set(problem);
      if (throwOnError) {
        throw new IllegalStateException("onError failed");
      }
    }

    @Override
    public void onCompleted() {}
  }
}
