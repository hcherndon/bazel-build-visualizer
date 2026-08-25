package com.holtherndon.bazelviz.benchmarks.spike;

import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos;
import com.google.devtools.build.v1.OrderedBuildEvent;
import com.google.devtools.build.v1.PublishBuildEventGrpc;
import com.google.devtools.build.v1.PublishBuildToolEventStreamRequest;
import com.google.devtools.build.v1.PublishBuildToolEventStreamResponse;
import com.google.devtools.build.v1.StreamId;
import com.google.protobuf.Any;
import com.holtherndon.bazelviz.capture.bes.BesEndpoint;
import com.holtherndon.bazelviz.capture.bes.BesServer;
import com.holtherndon.bazelviz.capture.bes.BesServerConfig;
import com.holtherndon.bazelviz.capture.live.CaptureOptions;
import com.holtherndon.bazelviz.capture.live.CaptureProgressListener;
import com.holtherndon.bazelviz.capture.live.CaptureSummary;
import com.holtherndon.bazelviz.capture.live.LiveCapturePipeline;
import com.holtherndon.bazelviz.capture.normalize.EventNormalizer;
import com.holtherndon.bazelviz.format.journal.ImportCheckpointStore;
import com.holtherndon.bazelviz.format.journal.JournalWriter;
import com.holtherndon.bazelviz.format.journal.JournalWriterConfig;
import com.holtherndon.bazelviz.storage.SessionDatabase;
import com.holtherndon.bazelviz.storage.entities.EntityWriter;
import com.holtherndon.bazelviz.storage.events.EventWriter;
import com.holtherndon.bazelviz.storage.events.StreamRegistry;
import com.holtherndon.bazelviz.storage.schema.MigrationRunner;
import io.grpc.ManagedChannel;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.StreamObserver;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Measures how fast the embedded BES server and the live pipeline can take
 * events, end to end, over a real socket.
 *
 * <p>Objective 1 in {@code docs/performance.md} is 100,000 events per second
 * on the capture path. This is what measures it, and it exists in particular
 * because of an open question recorded in ADR-008: grpc-netty disables
 * {@code sun.misc.Unsafe} on Java 25, which removes a fast path it has always
 * used for buffer access, and nothing had established what that costs on the
 * receive path this application depends on.
 *
 * <p>Everything real: a real gRPC client over loopback, the real raw-byte
 * marshaller, the real journal on disk, the real SQLite writer. A benchmark
 * that stubbed any of those would measure something this application never
 * does.
 *
 * <pre>{@code
 *   bazel run //benchmarks:bes_throughput_spike -- --events=200000
 * }</pre>
 */
public final class BesThroughputSpike {

    private static final int DEFAULT_EVENTS = 200_000;

    private BesThroughputSpike() {}

    public static void main(String[] args) throws Exception {
        int events = intArg(args, "--events=", DEFAULT_EVENTS);
        int payloadBytes = intArg(args, "--payload=", 512);
        // --transport-only replaces the pipeline with a sink that acknowledges
        // immediately and stores nothing. The difference between the two runs
        // is what the journal and the indexer cost; without it, a slow result
        // could be blamed on gRPC or on our own storage with equal confidence
        // and no evidence.
        if (java.util.Arrays.asList(args).contains("--transport-only")) {
            transportOnly(events, payloadBytes);
            return;
        }

        boolean passed = false;
        Path root = Files.createTempDirectory("bbv-bes-throughput");
        Path raw = Files.createDirectories(root.resolve("raw"));
        Path checkpoints = Files.createDirectories(root.resolve("checkpoints"));

        System.out.printf("BES throughput spike: %,d events, ~%d-byte payloads%n", events, payloadBytes);
        System.out.println("session: " + root);
        System.out.println("jvm:     " + Runtime.version()
                + "  " + System.getProperty("java.vm.name"));
        System.out.println("unsafe:  grpc-netty reports hasUnsafe=" + nettyHasUnsafe());
        System.out.println();

        try (SessionDatabase database = SessionDatabase.open(root.resolve("session.sqlite"))) {
            new MigrationRunner(MigrationRunner.standard().migrations()).migrate(database);
            try (EventWriter writer = new EventWriter(database.writerConnection(),
                            CaptureOptions.defaults().batchSize(), 4096);
                    EntityWriter entities = new EntityWriter(database.writerConnection());
                    StreamRegistry streams = new StreamRegistry(database.writerConnection());
                    JournalWriter journal = JournalWriter.create(
                            raw, UUID.randomUUID(), JournalWriterConfig.defaults())) {

                LiveCapturePipeline pipeline = new LiveCapturePipeline(
                        journal, writer, entities, streams,
                        new EventNormalizer(64 * 1024 * 1024),
                        new ImportCheckpointStore(checkpoints),
                        CaptureOptions.defaults(),
                        CaptureProgressListener.ignoring());
                pipeline.start();

                try (BesServer server = new BesServer(pipeline, BesServerConfig.defaults())) {
                    BesEndpoint endpoint = server.start();
                    Result result = drive(endpoint, events, payloadBytes);
                    CaptureSummary summary = pipeline.finish();
                    passed = report(result, summary, events);
                } finally {
                    pipeline.close();
                }
            }
        } finally {
            deleteTree(root);
        }
        // The shared spike contract (README): a budget breach is a nonzero exit,
        // so a regression fails a script rather than scrolling past in a log.
        if (!passed) {
            System.exit(1);
        }
    }

    /** Measures the server and the wire with nothing behind them. */
    private static void transportOnly(int events, int payloadBytes) throws Exception {
        System.out.printf("BES transport-only: %,d events, ~%d-byte payloads%n", events, payloadBytes);
        System.out.println("jvm:     " + Runtime.version());
        System.out.println("unsafe:  grpc-netty reports hasUnsafe=" + nettyHasUnsafe());
        com.holtherndon.bazelviz.capture.bes.RawEventSink sink =
                new com.holtherndon.bazelviz.capture.bes.RawEventSink() {
                    @Override
                    public void submit(
                            com.holtherndon.bazelviz.capture.bes.RawBesEvent event,
                            Runnable onJournaled) {
                        onJournaled.run();
                    }

                    @Override
                    public void streamOpened(
                            com.holtherndon.bazelviz.capture.bes.BesStreamKey key) {}

                    @Override
                    public void streamEnded(
                            com.holtherndon.bazelviz.capture.bes.BesStreamState finalState) {
                        System.out.printf("accepted:      %,d events%n", finalState.eventsAccepted());
                    }
                };
        try (BesServer server = new BesServer(sink, BesServerConfig.defaults())) {
            BesEndpoint endpoint = server.start();
            Result result = drive(endpoint, events, payloadBytes);
            System.out.printf("acknowledged:  %,d events in %.2fs  =  %,.0f events/sec%n",
                    result.acknowledged(), result.ackNanos() / 1e9,
                    result.acknowledged() / (result.ackNanos() / 1e9));
        }
    }

    private record Result(long sendNanos, long ackNanos, long acknowledged) {}

    /**
     * Sends {@code count} events and waits for every acknowledgement.
     *
     * <p>Two timings are taken because they answer different questions. The
     * send time is how fast the wire and the receive path accept events; the
     * acknowledgement time is how fast they become durable, which is what Bazel
     * actually waits for at the end of a build.
     */
    /** The monitor the transport's readiness is signalled through. */
    private static final Object READY = new Object();

    private static Result drive(BesEndpoint endpoint, int count, int payloadBytes) throws Exception {
        @SuppressWarnings("unchecked")
        io.grpc.stub.ClientCallStreamObserver<PublishBuildToolEventStreamRequest>[] outbound =
                new io.grpc.stub.ClientCallStreamObserver[1];
        ManagedChannel channel = NettyChannelBuilder
                .forAddress(endpoint.host(), endpoint.port())
                .usePlaintext()
                .build();
        try {
            AtomicLong acknowledged = new AtomicLong();
            CountDownLatch done = new CountDownLatch(1);
            io.grpc.stub.ClientResponseObserver<
                    PublishBuildToolEventStreamRequest,
                    PublishBuildToolEventStreamResponse> responses =
                    new io.grpc.stub.ClientResponseObserver<>() {

                        @Override
                        public void beforeStart(
                                io.grpc.stub.ClientCallStreamObserver<
                                        PublishBuildToolEventStreamRequest> stream) {
                            // Manual flow control. Without it gRPC buffers
                            // everything the transport cannot yet write, and at
                            // Tier 3 that is fifty million messages in heap:
                            // the first Tier 3 run of this spike died with an
                            // OutOfMemoryError inside DelayedStream, in the
                            // client, before the server had done anything at
                            // all. A benchmark that cannot reach the scale it
                            // is meant to measure is measuring its own defect.
                            stream.setOnReadyHandler(() -> {
                                synchronized (READY) {
                                    READY.notifyAll();
                                }
                            });
                            outbound[0] = stream;
                        }

                        @Override
                        public void onNext(PublishBuildToolEventStreamResponse response) {
                            acknowledged.incrementAndGet();
                        }

                        @Override
                        public void onError(Throwable failure) {
                            System.err.println("stream failed: " + failure);
                            done.countDown();
                        }

                        @Override
                        public void onCompleted() {
                            done.countDown();
                        }
                    };
            PublishBuildEventGrpc.newStub(channel).publishBuildToolEventStream(responses);
            io.grpc.stub.ClientCallStreamObserver<PublishBuildToolEventStreamRequest> requests =
                    outbound[0];

            byte[] filler = new byte[Math.max(0, payloadBytes)];
            java.util.Arrays.fill(filler, (byte) 'x');
            StreamId streamId = StreamId.newBuilder()
                    .setBuildId(UUID.randomUUID().toString())
                    .setInvocationId(UUID.randomUUID().toString())
                    .setComponent(StreamId.BuildComponent.TOOL)
                    .build();

            long sendStart = System.nanoTime();
            for (int i = 1; i <= count; i++) {
                // Wait for the transport rather than handing gRPC a message it
                // will hold. This is what makes the send rate a measurement of
                // the pipeline instead of a measurement of how fast a loop can
                // allocate protobufs.
                synchronized (READY) {
                    while (!requests.isReady()) {
                        READY.wait();
                    }
                }
                requests.onNext(request(streamId, i, filler));
            }
            long sendNanos = System.nanoTime() - sendStart;

            requests.onCompleted();
            // Scaled to the run: ten minutes is generous for 200,000 events and
            // not enough for fifty million.
            long minutes = Math.max(10, count / 100_000L);
            if (!done.await(minutes, TimeUnit.MINUTES)) {
                throw new IllegalStateException(
                        "the stream did not finish within " + minutes + " minutes");
            }
            long ackNanos = System.nanoTime() - sendStart;
            return new Result(sendNanos, ackNanos, acknowledged.get());
        } finally {
            channel.shutdownNow();
            channel.awaitTermination(30, TimeUnit.SECONDS);
        }
    }

    private static PublishBuildToolEventStreamRequest request(
            StreamId streamId, long sequence, byte[] filler) {
        BuildEventStreamProtos.BuildEvent inner = BuildEventStreamProtos.BuildEvent.newBuilder()
                .setId(BuildEventStreamProtos.BuildEventId.newBuilder()
                        .setProgress(BuildEventStreamProtos.BuildEventId.ProgressId.newBuilder()
                                .setOpaqueCount((int) sequence)))
                .setProgress(BuildEventStreamProtos.Progress.newBuilder()
                        .setStderr(new String(filler, java.nio.charset.StandardCharsets.ISO_8859_1)))
                .build();
        return PublishBuildToolEventStreamRequest.newBuilder()
                .setOrderedBuildEvent(OrderedBuildEvent.newBuilder()
                        .setStreamId(streamId)
                        .setSequenceNumber(sequence)
                        .setEvent(com.google.devtools.build.v1.BuildEvent.newBuilder()
                                .setBazelEvent(Any.newBuilder()
                                        .setTypeUrl("type.googleapis.com/build_event_stream.BuildEvent")
                                        .setValue(inner.toByteString()))))
                .setProjectId("bbv-spike")
                .build();
    }

    /** Objective 1 in docs/performance.md, in events per second. */
    private static final double OBJECTIVE_EVENTS_PER_SECOND = 100_000;

    private static boolean report(Result result, CaptureSummary summary, int expected) {
        double ackSeconds = result.ackNanos() / 1e9;
        double rate = result.acknowledged() / ackSeconds;

        // One throughput number, and it is the end-to-end one. There used to be
        // an "accepted" figure here dividing the server's received count by the
        // client's send duration -- two different intervals -- which reported
        // 869k-1.3M events/sec for a path that cannot have accepted more than
        // the flow-control window plus the receive queue by the time the client
        // stopped sending. It read as though the transport were fast and our
        // storage slow, and both halves of that were false.
        System.out.printf("client sent:   %,d events in %.2fs (enqueue only, not a capture rate)%n",
                expected, result.sendNanos() / 1e9);
        System.out.printf("acknowledged:  %,d events in %.2fs  =  %,.0f events/sec%n",
                result.acknowledged(), ackSeconds, rate);
        System.out.printf("journaled:     %,d frames, %,d bytes%n",
                summary.journaled(), summary.bytesJournaled());
        System.out.printf("indexed:       %,d rows (+%,d stream-control)%n",
                summary.normalized(), summary.nonEventEnvelopes());
        System.out.printf("complete:      %s   lagged: %s%n",
                summary.isComplete(), summary.lagged());
        for (String problem : summary.discrepancies()) {
            System.out.println("  ! " + problem);
        }

        boolean lossFree = summary.isComplete() && summary.received() == expected;
        if (summary.received() != expected) {
            System.out.printf("MISMATCH:      expected %,d, received %,d%n", expected, summary.received());
        }
        boolean pass = lossFree && rate >= OBJECTIVE_EVENTS_PER_SECOND;
        System.out.println();
        System.out.printf("%s: objective 1 is %,.0f events/sec without loss; measured %,.0f%s%n",
                pass ? "PASS" : "FAIL",
                OBJECTIVE_EVENTS_PER_SECOND,
                rate,
                lossFree ? "" : " WITH LOSS");
        return pass;
    }

    /**
     * Whether grpc-netty found {@code sun.misc.Unsafe} usable.
     *
     * <p>Read reflectively because the class is shaded and internal. It is the
     * one number that explains a change in these results between JDKs, so it is
     * printed with them rather than left to be guessed at afterwards.
     */
    private static String nettyHasUnsafe() {
        try {
            Class<?> platform = Class.forName(
                    "io.grpc.netty.shaded.io.netty.util.internal.PlatformDependent");
            return String.valueOf(platform.getMethod("hasUnsafe").invoke(null));
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            return "unknown (" + unavailable.getClass().getSimpleName() + ")";
        }
    }

    private static int intArg(String[] args, String prefix, int fallback) {
        for (String arg : args) {
            if (arg.startsWith(prefix)) {
                return Integer.parseInt(arg.substring(prefix.length()));
            }
        }
        return fallback;
    }

    private static void deleteTree(Path root) {
        try (var paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (java.io.IOException ignored) {
                    // A leftover temp directory is not worth failing a spike over.
                }
            });
        } catch (java.io.IOException ignored) {
            // Same.
        }
    }
}
