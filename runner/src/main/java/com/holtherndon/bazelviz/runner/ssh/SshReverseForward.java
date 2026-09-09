package com.holtherndon.bazelviz.runner.ssh;

import java.net.URI;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** A loopback-only remote port forwarded to the desktop BES listener. */
public final class SshReverseForward implements AutoCloseable {

  private final int localPort;
  private final int remotePort;
  private final Runnable closer;
  private final AtomicBoolean closed = new AtomicBoolean();

  SshReverseForward(int localPort, int remotePort, Runnable closer) {
    this.localPort = requirePort(localPort, "local");
    this.remotePort = requirePort(remotePort, "remote");
    this.closer = Objects.requireNonNull(closer, "closer");
  }

  public int localPort() {
    return localPort;
  }

  public int remotePort() {
    return remotePort;
  }

  boolean isClosed() {
    return closed.get();
  }

  /** The address Bazel must use on the SSH host. */
  public URI besBackendUri() {
    return URI.create("grpc://127.0.0.1:" + remotePort);
  }

  @Override
  public void close() {
    if (closed.compareAndSet(false, true)) {
      closer.run();
    }
  }

  private static int requirePort(int value, String name) {
    if (value < 1 || value > 65_535) {
      throw new IllegalArgumentException(name + " port must be between 1 and 65535");
    }
    return value;
  }
}
