package com.holtherndon.bazelviz.capture.bes;

import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The embedded Build Event Service, listening on loopback (plan 9.1, 22.1).
 *
 * <h2>Loopback is enforced by construction</h2>
 *
 * <p>The bind address is the explicit IPv4 loopback literal, not a configurable host. This must
 * match the local target of the SSH reverse forward; using the JVM's preferred loopback address
 * could bind only {@code ::1} while OpenSSH connects to {@code 127.0.0.1}. There is no setting that
 * makes this listen on a LAN interface, because "bind loopback only" is a v1 requirement and a
 * setting is something that gets changed. The port is asked of the operating system so two captures
 * can run at once.
 *
 * <h2>Start before Bazel, always</h2>
 *
 * <p>{@link #start()} returns only once the listener is up and the port is known. Launching Bazel
 * before that produces a build that fails to connect and a session with no events, which is a
 * confusing way to say "we were not ready yet" (plan 9.1, "start and confirm readiness before
 * launching Bazel").
 *
 * <p>Handlers run on this server's own executor rather than a transport event loop, so a handler
 * that blocks on backpressure stalls only its own stream.
 */
public final class BesServer implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(BesServer.class);

  private final BesServerConfig config;
  private final PublishBuildEventService service;
  private final BesResources resources;
  private final BesHandlerExecutor executor;

  private Server server;
  private BesEndpoint endpoint;

  public BesServer(RawEventSink sink, BesServerConfig config) {
    this(sink, config, PublishBuildEventService.MicrosClock.system());
  }

  BesServer(RawEventSink sink, BesServerConfig config, PublishBuildEventService.MicrosClock clock) {
    this.config = Objects.requireNonNull(config, "config");
    this.resources = new BesResources(config.resourceLimits());
    this.service =
        new PublishBuildEventService(
            Objects.requireNonNull(sink, "sink"), config.maxMessageBytes(), clock, resources);
    this.executor = new BesHandlerExecutor(config.resourceLimits(), resources);
  }

  /**
   * Binds the listener and returns the endpoint Bazel should be pointed at.
   *
   * @throws IOException if the port could not be bound
   * @throws IllegalStateException if already started
   */
  public synchronized BesEndpoint start() throws IOException {
    if (server != null) {
      throw new IllegalStateException("this BES server is already listening on " + endpoint);
    }
    InetSocketAddress address = new InetSocketAddress("127.0.0.1", config.port());
    server =
        NettyServerBuilder.forAddress(address)
            .addService(service.bindService())
            .executor(executor)
            .maxInboundMessageSize(config.maxMessageBytes())
            // Bazel's client pings while a build is idle between event
            // batches. The default server policy would answer a keepalive
            // it considers too frequent with GOAWAY, which the user would
            // see as the capture dropping mid-build.
            .permitKeepAliveWithoutCalls(true)
            .permitKeepAliveTime(config.permitKeepAliveEvery().toMillis(), TimeUnit.MILLISECONDS)
            .build()
            .start();
    endpoint = BesEndpoint.loopback(server.getPort());
    log.info("embedded BES server listening on {}", endpoint.besBackendUri());
    return endpoint;
  }

  /** The bound endpoint. Only valid after {@link #start()}. */
  public synchronized BesEndpoint endpoint() {
    if (endpoint == null) {
      throw new IllegalStateException("the BES server has not been started");
    }
    return endpoint;
  }

  /** Streams currently being received. */
  public int openStreamCount() {
    return resources.activeRpcs();
  }

  /** Current bounded-resource use and refusal counters. */
  public BesResourceSnapshot resourceSnapshot() {
    return resources.snapshot(executor);
  }

  /** Waits for zero active RPCs to remain stable for the configured period. */
  public boolean awaitQuiescence(Duration timeout) throws InterruptedException {
    return resources.awaitQuiescence(timeout);
  }

  /**
   * Stops accepting connections and waits out the grace period for in-flight streams, then stops
   * regardless.
   *
   * <p>It stops regardless on purpose. A build that was killed can leave a half-open stream that
   * will never complete, and waiting forever for it would hang the application at exactly the
   * moment the user is trying to look at what was captured. Whatever was journaled is already safe.
   */
  @Override
  public synchronized void close() {
    if (!shutdownAndAwait()) {
      log.error(
          "embedded BES did not terminate its server and handler callbacks within {} after forced"
              + " shutdown",
          config.shutdownGrace());
    }
  }

  /**
   * Stops the listener and waits, including after a forced shutdown, for every callback to leave.
   *
   * @return true only when both the gRPC server and its bounded handler executor terminated
   */
  public synchronized boolean shutdownAndAwait() {
    if (server == null) {
      return true;
    }
    boolean interrupted = Thread.interrupted();
    boolean serverTerminated;
    boolean handlersTerminated;
    server.shutdown();
    try {
      AwaitResult graceful = awaitServer(server, config.shutdownGrace());
      interrupted |= graceful.interrupted();
      serverTerminated = graceful.terminated();
      if (!serverTerminated) {
        log.info(
            "BES server still had streams open after {}; stopping it anyway",
            config.shutdownGrace());
        server.shutdownNow();
        AwaitResult forcedServer = awaitServer(server, config.shutdownGrace());
        interrupted |= forcedServer.interrupted();
        serverTerminated = forcedServer.terminated();
      }
      // Server cancellation is what schedules the final stream callbacks. Keep the handler
      // executor alive until that shutdown has had its bounded chance to terminate the RPCs.
      executor.shutdown();
      AwaitResult handlers = awaitExecutor(executor, config.shutdownGrace());
      interrupted |= handlers.interrupted();
      handlersTerminated = handlers.terminated();
      if (!handlersTerminated) {
        executor.shutdownNow();
        AwaitResult forcedHandlers = awaitExecutor(executor, config.shutdownGrace());
        interrupted |= forcedHandlers.interrupted();
        handlersTerminated = forcedHandlers.terminated();
      }
    } finally {
      server = null;
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
    }
    return serverTerminated && handlersTerminated;
  }

  private static AwaitResult awaitServer(Server value, Duration timeout) {
    long deadline = System.nanoTime() + timeout.toNanos();
    boolean interrupted = false;
    while (true) {
      try {
        long remaining = Math.max(0L, deadline - System.nanoTime());
        return new AwaitResult(
            value.awaitTermination(remaining, TimeUnit.NANOSECONDS), interrupted);
      } catch (InterruptedException retry) {
        interrupted = true;
      }
    }
  }

  private static AwaitResult awaitExecutor(ThreadPoolExecutor value, Duration timeout) {
    long deadline = System.nanoTime() + timeout.toNanos();
    boolean interrupted = false;
    while (true) {
      try {
        long remaining = Math.max(0L, deadline - System.nanoTime());
        return new AwaitResult(
            value.awaitTermination(remaining, TimeUnit.NANOSECONDS), interrupted);
      } catch (InterruptedException retry) {
        interrupted = true;
      }
    }
  }

  private record AwaitResult(boolean terminated, boolean interrupted) {}
}
