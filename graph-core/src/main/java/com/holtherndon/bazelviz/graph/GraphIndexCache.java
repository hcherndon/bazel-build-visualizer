package com.holtherndon.bazelviz.graph;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Access-ordered, ref-counted mapped-index cache shared by all readers of one open session. */
public final class GraphIndexCache implements AutoCloseable {

  /** At most one forward/reverse pair is retained for an open session. */
  public static final int MAX_CACHED_INDEXES = 2;

  private final GraphResourceBudget budget;
  private final Map<Key, Entry> entries = new LinkedHashMap<>(4, 0.75f, true);
  private boolean closed;

  public GraphIndexCache(GraphResourceBudget budget) {
    this.budget = Objects.requireNonNull(budget, "budget");
  }

  /** Acquires one mapping. The graph is valid only until the returned lease closes. */
  public synchronized Lease acquire(CsrFile.Descriptor descriptor) throws IOException {
    ensureOpen();
    Key key = Key.of(descriptor);
    Entry existing = entries.get(key);
    if (existing != null) {
      existing.references++;
      return new Lease(this, existing);
    }
    makeRoom(1, descriptor.fileBytes(), List.of());
    GraphResourceBudget.Reservation reservation =
        budget.reserve(descriptor.fileBytes(), purpose(descriptor));
    try {
      Entry loaded = new Entry(key, CsrFile.open(descriptor), reservation);
      loaded.references = 1;
      entries.put(key, loaded);
      return new Lease(this, loaded);
    } catch (IOException | RuntimeException failure) {
      reservation.close();
      throw failure;
    }
  }

  /** Acquires a forward/reverse pair as one operation; no one-sided lease can escape on failure. */
  public synchronized PairLease acquirePair(CsrFile.Descriptor forward, CsrFile.Descriptor reverse)
      throws IOException {
    ensureOpen();
    Key forwardKey = Key.of(forward);
    Key reverseKey = Key.of(reverse);
    if (forwardKey.equals(reverseKey)) {
      throw new IOException("forward and reverse graph descriptors name the same index");
    }
    Entry forwardEntry = entries.get(forwardKey);
    Entry reverseEntry = entries.get(reverseKey);
    int missing = (forwardEntry == null ? 1 : 0) + (reverseEntry == null ? 1 : 0);
    long requested = 0;
    try {
      if (forwardEntry == null) {
        requested = Math.addExact(requested, forward.fileBytes());
      }
      if (reverseEntry == null) {
        requested = Math.addExact(requested, reverse.fileBytes());
      }
    } catch (ArithmeticException overflow) {
      throw new GraphResourceBudget.RefusedException(
          Long.MAX_VALUE, "mapped forward/reverse graph index pair", budget.snapshot());
    }
    makeRoom(missing, requested, List.of(forwardKey, reverseKey));

    List<GraphResourceBudget.Request> charges = new ArrayList<>();
    if (forwardEntry == null) {
      charges.add(new GraphResourceBudget.Request(forward.fileBytes(), purpose(forward)));
    }
    if (reverseEntry == null) {
      charges.add(new GraphResourceBudget.Request(reverse.fileBytes(), purpose(reverse)));
    }
    List<GraphResourceBudget.Reservation> reservations = budget.reserveAll(charges);
    List<Entry> inserted = new ArrayList<>(missing);
    int reservationIndex = 0;
    try {
      if (forwardEntry == null) {
        forwardEntry =
            new Entry(forwardKey, CsrFile.open(forward), reservations.get(reservationIndex++));
        entries.put(forwardKey, forwardEntry);
        inserted.add(forwardEntry);
      }
      if (reverseEntry == null) {
        reverseEntry =
            new Entry(reverseKey, CsrFile.open(reverse), reservations.get(reservationIndex));
        entries.put(reverseKey, reverseEntry);
        inserted.add(reverseEntry);
      }
      forwardEntry.references++;
      reverseEntry.references++;
      return new PairLease(new Lease(this, forwardEntry), new Lease(this, reverseEntry));
    } catch (IOException | RuntimeException failure) {
      for (Entry entry : inserted) {
        entries.remove(entry.key);
        entry.close();
      }
      for (int index = inserted.size(); index < reservations.size(); index++) {
        reservations.get(index).close();
      }
      throw failure;
    }
  }

  public GraphResourceBudget budget() {
    return budget;
  }

  public synchronized int cachedCount() {
    return entries.size();
  }

  private void makeRoom(int newEntries, long requestedBytes, List<Key> protectedKeys)
      throws IOException {
    while (entries.size() + newEntries > MAX_CACHED_INDEXES
        || budget.snapshot().availableBytes() < requestedBytes) {
      if (!evictOne(protectedKeys)) {
        GraphResourceBudget.Snapshot snapshot = budget.snapshot();
        throw new GraphResourceBudget.RefusedException(
            requestedBytes,
            "mapped graph index cache; every eviction candidate is leased",
            snapshot);
      }
    }
  }

  private boolean evictOne(List<Key> protectedKeys) {
    Iterator<Map.Entry<Key, Entry>> iterator = entries.entrySet().iterator();
    while (iterator.hasNext()) {
      Entry candidate = iterator.next().getValue();
      if (candidate.references == 0 && !protectedKeys.contains(candidate.key)) {
        iterator.remove();
        candidate.close();
        return true;
      }
    }
    return false;
  }

  private static String purpose(CsrFile.Descriptor descriptor) {
    return "mapped graph index " + descriptor.path().getFileName();
  }

  private void ensureOpen() throws IOException {
    if (closed) {
      throw new IOException("graph index cache is closed");
    }
  }

  private synchronized void release(Entry entry) {
    if (entry.references <= 0) {
      throw new IllegalStateException("graph index lease released more than once");
    }
    entry.references--;
    if (closed && entry.references == 0) {
      entries.remove(entry.key);
      entry.close();
    }
  }

  @Override
  public synchronized void close() {
    if (closed) {
      return;
    }
    closed = true;
    Iterator<Map.Entry<Key, Entry>> iterator = entries.entrySet().iterator();
    while (iterator.hasNext()) {
      Entry entry = iterator.next().getValue();
      if (entry.references == 0) {
        iterator.remove();
        entry.close();
      }
    }
  }

  private record Key(
      Path path, long nodeCount, long edgeCount, long checksum, boolean reverse, long fileBytes) {
    static Key of(CsrFile.Descriptor descriptor) {
      CsrFile.Header header = descriptor.header();
      return new Key(
          descriptor.path(),
          header.nodeCount(),
          header.edgeCount(),
          header.checksum(),
          header.reverseDirection(),
          descriptor.fileBytes());
    }
  }

  private static final class Entry {
    private final Key key;
    private final CsrGraph graph;
    private final GraphResourceBudget.Reservation reservation;
    private int references;

    Entry(Key key, CsrGraph graph, GraphResourceBudget.Reservation reservation) {
      this.key = key;
      this.graph = graph;
      this.reservation = reservation;
    }

    void close() {
      graph.close();
      reservation.close();
    }
  }

  /** One scoped reference to a mapped graph. */
  public static final class Lease implements AutoCloseable {
    private GraphIndexCache owner;
    private Entry entry;

    private Lease(GraphIndexCache owner, Entry entry) {
      this.owner = owner;
      this.entry = entry;
    }

    public CsrGraph graph() {
      if (entry == null) {
        throw new IllegalStateException("graph index lease is closed");
      }
      return entry.graph;
    }

    @Override
    public void close() {
      Entry releasing = entry;
      if (releasing == null) {
        return;
      }
      entry = null;
      owner.release(releasing);
      owner = null;
    }
  }

  /** Atomic pair of scoped graph references. */
  public record PairLease(Lease forward, Lease reverse) implements AutoCloseable {
    public PairLease {
      Objects.requireNonNull(forward, "forward");
      Objects.requireNonNull(reverse, "reverse");
    }

    @Override
    public void close() {
      reverse.close();
      forward.close();
    }
  }
}
