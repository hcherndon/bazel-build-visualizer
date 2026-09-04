package com.holtherndon.bazelviz.capture.bes;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeSet;

/** Mutable sequence and connection bookkeeping for one logical BES stream. */
final class BesStreamTracker {

  static final int DEFAULT_MAX_OUT_OF_ORDER = 1024;

  enum Decision {
    ACCEPTED,
    DUPLICATE_ACK_NOW,
    DUPLICATE_WAIT,
    TOO_FAR_AHEAD,
    INVALID
  }

  /** One RPC connection to this stream. Generations are never reused. */
  record Connection(BesStreamKey key, long generation) {}

  /** An acknowledgement owed to the connection that delivered this original or replay. */
  record PendingAck(Connection connection, long sequence, boolean replenishCredit) {

    PendingAck(Connection connection, long sequence) {
      this(connection, sequence, true);
    }
  }

  /** Work that became safe only after a frame reached the journal and closed any earlier gap. */
  record JournalResult(
      AckRange contiguousRange,
      List<PendingAck> readyAcks,
      Optional<BesStreamState> terminalState) {

    JournalResult {
      readyAcks = List.copyOf(readyAcks);
      terminalState = Objects.requireNonNull(terminalState, "terminalState");
    }
  }

  /** Connections whose pending duplicate must be failed when the original cannot be journaled. */
  record RejectedResult(
      List<Connection> waitingConnections, Optional<BesStreamState> terminalState) {

    RejectedResult {
      waitingConnections = List.copyOf(waitingConnections);
      terminalState = Objects.requireNonNull(terminalState, "terminalState");
    }
  }

  private final BesStreamKey key;
  private final int maxOutOfOrder;
  private final NavigableSet<Long> journaledAhead = new TreeSet<>();
  private final NavigableSet<Long> inFlight = new TreeSet<>();
  private final NavigableSet<Long> acknowledgedAhead = new TreeSet<>();
  private final Map<Long, Connection> originalConnections = new HashMap<>();
  // This is a list, not a set: gRPC can replay the same sequence more than once on one
  // connection. Every delivery increments that connection's outstanding count and therefore
  // needs its own acknowledgement/decrement when the original becomes durable.
  private final Map<Long, List<Connection>> pendingDuplicateAcks = new HashMap<>();
  private final Set<Connection> activeConnections = new HashSet<>();

  private long nextGeneration;
  private long highestReceived;
  private long highestContiguous;
  private long highestAcknowledged;
  private long eventsAccepted;
  private long duplicateCount;
  private long firstReceiveMicros = -1;
  private long lastReceiveMicros = -1;
  private BesStreamState.Completion completion = BesStreamState.Completion.OPEN;
  private BesStreamState.Completion epochEnding;
  private String error;
  private String epochError;
  private boolean epochTerminalEmitted;
  private boolean epochTerminalPublished;

  BesStreamTracker(BesStreamKey key) {
    this(key, DEFAULT_MAX_OUT_OF_ORDER);
  }

  BesStreamTracker(BesStreamKey key, int maxOutOfOrder) {
    this.key = Objects.requireNonNull(key, "key");
    if (maxOutOfOrder < 1) {
      throw new IllegalArgumentException("maxOutOfOrder must be positive, got " + maxOutOfOrder);
    }
    this.maxOutOfOrder = maxOutOfOrder;
  }

  BesStreamKey key() {
    return key;
  }

  /** Admits a new RPC generation and begins a new epoch if the last one was quiescent. */
  synchronized Connection openConnection() {
    if (epochTerminalEmitted && !epochTerminalPublished) {
      throw new IllegalStateException("the preceding BES epoch is still being published");
    }
    return openConnectionLocked();
  }

  /** Waits until the preceding epoch's terminal callback is visible before opening another. */
  synchronized Connection openConnectionInterruptibly() throws InterruptedException {
    while (epochTerminalEmitted && !epochTerminalPublished) {
      wait();
    }
    return openConnectionLocked();
  }

  private Connection openConnectionLocked() {
    if (activeConnections.isEmpty() && epochTerminalEmitted) {
      completion = BesStreamState.Completion.OPEN;
      error = null;
      epochEnding = null;
      epochError = null;
      epochTerminalEmitted = false;
      epochTerminalPublished = false;
    }
    Connection connection = new Connection(key, ++nextGeneration);
    activeConnections.add(connection);
    return connection;
  }

  synchronized Decision accept(Connection connection, long sequence, long receiveMicros) {
    requireActive(connection);
    if (sequence < BesStreamState.FIRST_SEQUENCE) {
      return Decision.INVALID;
    }
    if (sequence <= highestContiguous) {
      duplicateCount++;
      return Decision.DUPLICATE_ACK_NOW;
    }
    if (inFlight.contains(sequence) || journaledAhead.contains(sequence)) {
      duplicateCount++;
      pendingDuplicateAcks.computeIfAbsent(sequence, ignored -> new ArrayList<>()).add(connection);
      return Decision.DUPLICATE_WAIT;
    }
    if (inFlight.size() + journaledAhead.size() >= maxOutOfOrder) {
      return Decision.TOO_FAR_AHEAD;
    }
    inFlight.add(sequence);
    originalConnections.put(sequence, connection);
    eventsAccepted++;
    highestReceived = Math.max(highestReceived, sequence);
    if (firstReceiveMicros < 0) {
      firstReceiveMicros = receiveMicros;
    }
    lastReceiveMicros = receiveMicros;
    return Decision.ACCEPTED;
  }

  synchronized JournalResult journaled(long sequence) {
    inFlight.remove(sequence);
    if (sequence > highestContiguous) {
      journaledAhead.add(sequence);
      while (journaledAhead.remove(highestContiguous + 1)) {
        highestContiguous++;
      }
    }

    AckRange range =
        highestContiguous <= highestAcknowledged
            ? AckRange.empty()
            : new AckRange(highestAcknowledged + 1, highestContiguous);
    List<PendingAck> readyAcks = new ArrayList<>();
    for (long readySequence = range.from(); readySequence <= range.to(); readySequence++) {
      Connection original = originalConnections.remove(readySequence);
      if (original != null && activeConnections.contains(original)) {
        readyAcks.add(new PendingAck(original, readySequence, false));
      }
    }
    var ready = new ArrayList<>(pendingDuplicateAcks.keySet());
    ready.sort(Long::compare);
    for (long readySequence : ready) {
      if (readySequence > highestContiguous) {
        break;
      }
      List<Connection> connections = pendingDuplicateAcks.remove(readySequence);
      if (connections != null) {
        for (Connection connection : connections) {
          if (activeConnections.contains(connection)) {
            readyAcks.add(new PendingAck(connection, readySequence));
          }
        }
      }
    }
    return new JournalResult(range, readyAcks, terminalIfQuiescent());
  }

  /** Records an acknowledgement only after the response observer accepted it. */
  synchronized void acknowledged(long sequence) {
    if (sequence <= highestAcknowledged || sequence > highestContiguous) {
      return;
    }
    acknowledgedAhead.add(sequence);
    while (acknowledgedAhead.remove(highestAcknowledged + 1)) {
      highestAcknowledged++;
    }
  }

  /** Removes a failed original and returns every duplicate connection waiting for it. */
  synchronized RejectedResult rejected(long sequence, String detail) {
    inFlight.remove(sequence);
    originalConnections.remove(sequence);
    List<Connection> waiting = pendingDuplicateAcks.remove(sequence);
    recordEpochEnding(BesStreamState.Completion.FAILED, detail);
    return new RejectedResult(
        waiting == null ? List.of() : List.copyOf(waiting), terminalIfQuiescent());
  }

  /**
   * Ends one connection. A terminal state is emitted only after every generation in this epoch has
   * ended, using FAILED &gt; FINISHED &gt; ABORTED precedence.
   */
  synchronized Optional<BesStreamState> end(
      Connection connection, BesStreamState.Completion how, String detail) {
    if (!activeConnections.remove(connection)) {
      return Optional.empty();
    }
    pendingDuplicateAcks.values().forEach(connections -> connections.removeIf(connection::equals));
    pendingDuplicateAcks.values().removeIf(List::isEmpty);
    originalConnections.values().removeIf(connection::equals);
    recordEpochEnding(how, detail);
    return terminalIfQuiescent();
  }

  /** Upgrades an ending epoch when a late durability callback exposes a transport failure. */
  synchronized Optional<BesStreamState> failEpoch(String detail) {
    recordEpochEnding(BesStreamState.Completion.FAILED, detail);
    return terminalIfQuiescent();
  }

  /** Releases a reconnect only after the sink has observed the preceding terminal snapshot. */
  synchronized void terminalPublished() {
    if (epochTerminalEmitted) {
      epochTerminalPublished = true;
      notifyAll();
    }
  }

  synchronized int activeConnectionCount() {
    return activeConnections.size();
  }

  synchronized BesStreamState snapshot() {
    return snapshotLocked();
  }

  private BesStreamState snapshotLocked() {
    return new BesStreamState(
        key,
        highestReceived,
        highestContiguous,
        highestAcknowledged,
        eventsAccepted,
        duplicateCount,
        inFlight.size() + journaledAhead.size(),
        firstReceiveMicros < 0 ? OptionalLong.empty() : OptionalLong.of(firstReceiveMicros),
        lastReceiveMicros < 0 ? OptionalLong.empty() : OptionalLong.of(lastReceiveMicros),
        completion,
        Optional.ofNullable(error));
  }

  /** Returns an epoch's one terminal snapshot only after every accepted append has resolved. */
  private Optional<BesStreamState> terminalIfQuiescent() {
    if (epochTerminalEmitted
        || !activeConnections.isEmpty()
        || !inFlight.isEmpty()
        || epochEnding == null) {
      return Optional.empty();
    }
    completion = epochEnding;
    error = epochError;
    epochTerminalEmitted = true;
    return Optional.of(snapshotLocked());
  }

  private void recordEpochEnding(BesStreamState.Completion how, String detail) {
    Objects.requireNonNull(how, "how");
    if (epochTerminalEmitted) {
      return;
    }
    if (epochEnding == null || precedence(how) > precedence(epochEnding)) {
      epochEnding = how;
      epochError = detail;
    }
  }

  private void requireActive(Connection connection) {
    if (!activeConnections.contains(connection)) {
      throw new IllegalStateException(
          "BES connection generation is no longer active: " + connection);
    }
  }

  private static int precedence(BesStreamState.Completion completion) {
    return switch (completion) {
      case FAILED -> 3;
      case FINISHED -> 2;
      case ABORTED -> 1;
      case OPEN -> throw new IllegalArgumentException("OPEN is not a connection ending");
    };
  }

  record AckRange(long from, long to) {

    static AckRange empty() {
      return new AckRange(1, 0);
    }

    boolean isEmpty() {
      return from > to;
    }

    long count() {
      return isEmpty() ? 0 : to - from + 1;
    }
  }
}
