package com.holtherndon.bazelviz.storage.events;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * Interns repeated strings into the {@code strings} table and hands back their integer ids.
 *
 * <h2>Why the cache is bounded</h2>
 *
 * <p>The obvious implementation — a {@code HashMap<String, Long>} filled as ingestion proceeds — is
 * a memory leak with a respectable name. A Tier 3 session has tens of millions of events
 * referencing millions of distinct paths and labels; retaining every one of them defeats the whole
 * point of putting the data in SQLite (ADR-007, plan rule 16). So the cache has a fixed capacity
 * and evicts.
 *
 * <h2>Eviction policy</h2>
 *
 * <p><b>Strict LRU by access, fixed capacity, default {@value #DEFAULT_CACHE_ENTRIES} entries.</b>
 * Implemented with an access-ordered {@link LinkedHashMap}, so a lookup counts as a use and the
 * entry unused longest is the one dropped. That fits BEP's access pattern: repeated values cluster
 * in time (one target's actions all name the same mnemonics and output roots), so a recency window
 * captures nearly all of the reuse.
 *
 * <p>Eviction is never a correctness event. The {@code UNIQUE} constraint on {@code strings.value}
 * is the authority on identity; the cache only decides whether answering costs a round trip. An
 * evicted-then-requested string re-reads its existing id and gets the same number back. That is
 * also why {@link #invalidate()} — called when a transaction rolls back and the rows the cache is
 * describing cease to exist — is safe to call at any time.
 *
 * <p>Not thread-safe; use from the single writer thread, like everything else on the writer
 * connection.
 */
public final class StringDictionary implements AutoCloseable {

  /**
   * Default capacity. 64Ki entries is roughly a few MiB of retained strings for typical path
   * lengths — small enough to be irrelevant next to the SQLite page cache, large enough to hold the
   * working set of one build phase.
   */
  public static final int DEFAULT_CACHE_ENTRIES = 65_536;

  private static final String SELECT = "SELECT id FROM strings WHERE value = ?";
  private static final String INSERT =
      "INSERT INTO strings (value) VALUES (?) ON CONFLICT (value) DO NOTHING";

  private final PreparedStatement select;
  private final PreparedStatement insert;
  private final LinkedHashMap<String, Long> cache;
  private final int capacity;

  private long hits;
  private long misses;
  private long evictions;
  private boolean closed;

  public StringDictionary(Connection connection) throws SQLException {
    this(connection, DEFAULT_CACHE_ENTRIES);
  }

  /**
   * @param cacheEntries maximum entries retained; must be at least 1
   */
  public StringDictionary(Connection connection, int cacheEntries) throws SQLException {
    if (cacheEntries < 1) {
      throw new IllegalArgumentException("cacheEntries must be >= 1, got " + cacheEntries);
    }
    this.capacity = cacheEntries;
    this.select = connection.prepareStatement(SELECT);
    this.insert = connection.prepareStatement(INSERT);
    this.cache =
        new LinkedHashMap<>(Math.min(cacheEntries, 1024), 0.75f, true) {
          @Override
          protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
            if (size() > capacity) {
              evictions++;
              return true;
            }
            return false;
          }
        };
  }

  /**
   * Returns the id for {@code value}, inserting it if this is the first time the database has seen
   * it.
   *
   * <p>When the connection has auto-commit suspended (the normal case during batched ingestion) the
   * inserted row belongs to the caller's transaction. If that transaction rolls back, call {@link
   * #invalidate()} — otherwise the cache would keep vouching for an id that no longer exists.
   */
  public long id(String value) throws SQLException {
    Objects.requireNonNull(value, "value");
    Long cached = cache.get(value);
    if (cached != null) {
      hits++;
      return cached;
    }
    misses++;
    OptionalLong existing = lookup(value);
    long id;
    if (existing.isPresent()) {
      id = existing.getAsLong();
    } else {
      insert.setString(1, value);
      insert.executeUpdate();
      // Re-select rather than trusting generated keys: when the row
      // already existed the conflict clause inserted nothing and there
      // is no generated key to read.
      id =
          lookup(value)
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          "interned string vanished immediately after insert: " + value));
    }
    cache.put(value, id);
    return id;
  }

  /** The id for {@code value} if the database already holds it, without inserting. */
  public OptionalLong lookup(String value) throws SQLException {
    select.setString(1, value);
    try (ResultSet rows = select.executeQuery()) {
      return rows.next() ? OptionalLong.of(rows.getLong(1)) : OptionalLong.empty();
    }
  }

  /**
   * Drops every cached entry. Call after a rollback: the cache may be vouching for rows the
   * rollback removed.
   */
  public void invalidate() {
    cache.clear();
  }

  /** Entries currently retained. Never exceeds the configured capacity. */
  public int cachedEntries() {
    return cache.size();
  }

  /** Maximum entries retained before eviction begins. */
  public int capacity() {
    return capacity;
  }

  /** Lookups answered from the cache. */
  public long cacheHits() {
    return hits;
  }

  /** Lookups that had to reach the database. */
  public long cacheMisses() {
    return misses;
  }

  /** Entries dropped by the LRU policy. */
  public long evictions() {
    return evictions;
  }

  @Override
  public void close() throws SQLException {
    if (closed) {
      return;
    }
    closed = true;
    cache.clear();
    try (PreparedStatement toCloseSelect = select;
        PreparedStatement toCloseInsert = insert) {
      // try-with-resources closes both, reporting the first failure.
    }
  }
}
