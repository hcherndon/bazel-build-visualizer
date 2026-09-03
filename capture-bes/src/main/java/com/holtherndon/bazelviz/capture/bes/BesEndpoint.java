package com.holtherndon.bazelviz.capture.bes;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Objects;
import java.util.UUID;

/**
 * The address the embedded Build Event Service is listening on (plan 9.1).
 *
 * <h2>Loopback is not a default, it is the contract</h2>
 *
 * <p>Plan 22.1 requires the server to bind loopback only and to reject non-loopback configuration
 * in v1. That is enforced here, in the constructor, rather than at the one call site that happens
 * to build an endpoint today: a value of this type cannot exist for a LAN interface, so no later
 * code path can start a listener on one by passing a different string.
 *
 * <p>The port is asked of the operating system rather than fixed, because a fixed port collides
 * with the user's other tools and, worse, with a second copy of this application capturing a
 * different build.
 *
 * @param host the loopback host to bind and to advertise to Bazel
 * @param port the actual bound port, never 0 — an endpoint is only constructed after the listener
 *     is up and the OS has assigned one
 * @param captureToken a value unique to this capture. Bazel's BES client has no field that carries
 *     it to the server, so it is not used for authentication; it exists so that a session can prove
 *     which listener it belonged to when invocation ids are absent (plan 9.1, last bullet)
 */
public record BesEndpoint(String host, int port, String captureToken) {

  /** The only host this application will bind in v1. */
  public static final String LOOPBACK = "127.0.0.1";

  public BesEndpoint {
    Objects.requireNonNull(host, "host");
    Objects.requireNonNull(captureToken, "captureToken");
    if (port < 1 || port > 65535) {
      throw new IllegalArgumentException("port must be a bound port in 1..65535, got " + port);
    }
    if (!isLoopback(host)) {
      throw new IllegalArgumentException(
          "the embedded BES server binds loopback only (plan 22.1); refusing host " + host);
    }
  }

  /** An endpoint on the loopback interface with a fresh capture token. */
  public static BesEndpoint loopback(int port) {
    return new BesEndpoint(LOOPBACK, port, UUID.randomUUID().toString());
  }

  /**
   * The value for {@code --bes_backend}. {@code grpc://} selects plaintext, which is correct and
   * safe here precisely because the socket is loopback: TLS to oneself would add a certificate to
   * manage and protect nothing that the kernel does not already protect.
   */
  public String besBackendUri() {
    return "grpc://" + host + ":" + port;
  }

  public String hostPort() {
    return host + ":" + port;
  }

  private static boolean isLoopback(String host) {
    if (host.equals(LOOPBACK) || host.equals("::1") || host.equalsIgnoreCase("localhost")) {
      return true;
    }
    try {
      return InetAddress.getByName(host).isLoopbackAddress();
    } catch (UnknownHostException unresolvable) {
      return false;
    }
  }
}
