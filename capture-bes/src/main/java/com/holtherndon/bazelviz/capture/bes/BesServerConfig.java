package com.holtherndon.bazelviz.capture.bes;

import com.holtherndon.bazelviz.core.journal.JournalFormat;
import java.time.Duration;

/**
 * Tunables for the embedded BES server.
 *
 * @param port the port to bind, or 0 to let the operating system choose. Zero is the default: a
 *     fixed port collides with the user's other tools and, worse, with a second copy of this
 *     application capturing a different build (plan 9.1)
 * @param maxMessageBytes the largest BES message accepted. Defaults to the journal's payload
 *     ceiling, because a message this server accepts but the journal refuses would be an event
 *     acknowledged and then lost
 * @param shutdownGrace how long a graceful shutdown waits for in-flight streams before it stops
 *     waiting; the capture is finalized either way
 * @param permitKeepAliveEvery the shortest client keepalive interval tolerated before the server
 *     treats the pings as abuse. Generous on purpose: this server has exactly one client, on
 *     loopback, and a GOAWAY sent to Bazel mid-build would look to the user like the capture broke
 */
public record BesServerConfig(
    int port,
    int maxMessageBytes,
    Duration shutdownGrace,
    Duration permitKeepAliveEvery,
    BesResourceLimits resourceLimits) {

  public BesServerConfig(
      int port, int maxMessageBytes, Duration shutdownGrace, Duration permitKeepAliveEvery) {
    this(port, maxMessageBytes, shutdownGrace, permitKeepAliveEvery, BesResourceLimits.defaults());
  }

  public BesServerConfig {
    if (port < 0 || port > 65535) {
      throw new IllegalArgumentException("port must be 0..65535, got " + port);
    }
    if (maxMessageBytes <= 0) {
      throw new IllegalArgumentException(
          "maxMessageBytes must be positive, got " + maxMessageBytes);
    }
    if (shutdownGrace.isNegative()) {
      throw new IllegalArgumentException("shutdownGrace must not be negative: " + shutdownGrace);
    }
    if (permitKeepAliveEvery.isNegative()) {
      throw new IllegalArgumentException(
          "permitKeepAliveEvery must not be negative: " + permitKeepAliveEvery);
    }
    if (resourceLimits == null) {
      throw new NullPointerException("resourceLimits");
    }
  }

  public static BesServerConfig defaults() {
    return new BesServerConfig(
        0,
        JournalFormat.DEFAULT_MAX_PAYLOAD_BYTES,
        Duration.ofSeconds(10),
        Duration.ofSeconds(10),
        BesResourceLimits.defaults());
  }

  public BesServerConfig withPort(int value) {
    return new BesServerConfig(
        value, maxMessageBytes, shutdownGrace, permitKeepAliveEvery, resourceLimits);
  }

  public BesServerConfig withMaxMessageBytes(int value) {
    return new BesServerConfig(port, value, shutdownGrace, permitKeepAliveEvery, resourceLimits);
  }

  public BesServerConfig withResourceLimits(BesResourceLimits value) {
    return new BesServerConfig(port, maxMessageBytes, shutdownGrace, permitKeepAliveEvery, value);
  }
}
