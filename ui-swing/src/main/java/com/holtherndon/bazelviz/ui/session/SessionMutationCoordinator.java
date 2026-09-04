package com.holtherndon.bazelviz.ui.session;

import com.holtherndon.bazelviz.core.id.SessionId;
import com.holtherndon.bazelviz.format.portable.BvizFormatException;
import com.holtherndon.bazelviz.format.portable.BvizLimits;
import com.holtherndon.bazelviz.format.portable.BvizReader;
import com.holtherndon.bazelviz.storage.catalog.CatalogEntry;
import com.holtherndon.bazelviz.storage.catalog.RetentionPolicy;
import com.holtherndon.bazelviz.storage.catalog.SessionCatalog;
import com.holtherndon.bazelviz.storage.graph.GraphQueries;
import com.holtherndon.bazelviz.storage.metrics.MetricQueries;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import javax.swing.SwingUtilities;

/**
 * Coordinates process-wide operations that can remove or adopt session directories.
 *
 * <p>Every native window receives the same instance. Opening a session takes a lease before its
 * database is opened. Retention takes the same session lock and rechecks those leases immediately
 * before deleting anything, so a plan made in one window cannot delete a session subsequently
 * opened in another. Portable archive adoption uses that lock too, preventing two windows from
 * sharing or removing the same UUID's staging data.
 *
 * <p>The fixed stripe table bounds coordinator memory. A hash collision may serialize unrelated
 * session mutations, but can never make them unsafe. All methods which can wait or perform I/O
 * reject the Swing event thread.
 */
public final class SessionMutationCoordinator {

  /** Fixed lock table size; collisions serialize work but do not cap session count. */
  public static final int LOCK_STRIPES = 64;

  private final ReentrantLock[] locks = new ReentrantLock[LOCK_STRIPES];
  private final Map<String, Integer> activeSessions = new HashMap<>();
  private final ArchiveAdopter archiveAdopter;

  public SessionMutationCoordinator() {
    this(ArchiveImport::into);
  }

  SessionMutationCoordinator(ArchiveAdopter archiveAdopter) {
    this.archiveAdopter = Objects.requireNonNull(archiveAdopter, "archiveAdopter");
    for (int index = 0; index < locks.length; index++) {
      locks[index] = new ReentrantLock();
    }
  }

  /**
   * Marks one session active until the returned lease is closed.
   *
   * <p>Acquire this before opening the session database. If opening fails, close the lease.
   */
  public ActiveSession activate(String sessionUuid, Path directory) throws InterruptedException {
    requireBackground("session activation");
    String checkedUuid = requireUuid(sessionUuid);
    Path checkedDirectory = normalize(directory);
    ReentrantLock lock = lockFor(checkedUuid);
    lock.lockInterruptibly();
    try {
      synchronized (activeSessions) {
        activeSessions.merge(checkedUuid, 1, Math::addExact);
      }
      return new ActiveSession(this, checkedUuid, checkedDirectory);
    } finally {
      lock.unlock();
    }
  }

  /**
   * Applies the exact confirmed retention plan, except for sessions that became active, pinned,
   * missing, or relocated after the plan was shown.
   */
  public SessionCatalog.SweepResult applyCleanup(Path catalogDirectory, RetentionPolicy.Plan plan)
      throws Exception {
    requireBackground("session cleanup");
    Objects.requireNonNull(catalogDirectory, "catalogDirectory");
    Objects.requireNonNull(plan, "plan");

    List<ReentrantLock> held =
        acquireLocks(
            plan.candidates().stream().map(candidate -> candidate.entry().sessionUuid()).toList());
    try {
      return CatalogAccess.withCatalog(
          catalogDirectory,
          catalog -> {
            List<RetentionPolicy.Candidate> eligible = new ArrayList<>();
            List<String> skipped = new ArrayList<>();
            for (RetentionPolicy.Candidate candidate : plan.candidates()) {
              CatalogEntry planned = candidate.entry();
              if (isActive(planned.sessionUuid())) {
                skipped.add(
                    planned.displayName() + " is open in an application window and was kept");
                continue;
              }
              Optional<CatalogEntry> current = catalog.find(planned.sessionUuid());
              if (current.isEmpty()) {
                skipped.add(
                    planned.displayName() + " is no longer in the session catalog and was kept");
                continue;
              }
              CatalogEntry fresh = current.orElseThrow();
              if (fresh.pinned()) {
                skipped.add(
                    fresh.displayName() + " was pinned after the plan was made and was kept");
                continue;
              }
              if (!normalize(fresh.directory()).equals(normalize(planned.directory()))) {
                skipped.add(fresh.displayName() + " moved after the plan was made and was kept");
                continue;
              }
              eligible.add(new RetentionPolicy.Candidate(fresh, candidate.reason()));
            }

            SessionCatalog.SweepResult applied =
                eligible.isEmpty()
                    ? new SessionCatalog.SweepResult(0, 0, List.of())
                    : catalog.apply(
                        new RetentionPolicy.Plan(
                            eligible,
                            0,
                            eligible.stream()
                                .mapToLong(candidate -> candidate.entry().totalBytes().orElse(0))
                                .sum()));
            List<String> failures = new ArrayList<>(skipped);
            failures.addAll(applied.failures());
            return new SessionCatalog.SweepResult(
                applied.removed(), applied.bytesFreed(), failures);
          });
    } finally {
      unlockReverse(held);
    }
  }

  /** Validates and adopts a portable archive while excluding the same session UUID. */
  public ArchiveImport.Result importArchive(Path archive, Path sessionsRoot, BvizLimits limits)
      throws IOException, InterruptedException {
    requireBackground("portable archive import");
    Objects.requireNonNull(archive, "archive");
    Objects.requireNonNull(sessionsRoot, "sessionsRoot");
    Objects.requireNonNull(limits, "limits");

    // Validation writes nothing, so expensive checksum work need not serialize unrelated
    // imports. Only extraction and adoption hold the UUID's mutation lock.
    BvizReader.Validation validation = BvizReader.validate(archive, limits);
    String sessionUuid = requireUuid(validation.index().sessionId());
    ReentrantLock lock = lockFor(sessionUuid);
    lock.lockInterruptibly();
    try {
      if (isActive(sessionUuid)) {
        throw new BvizFormatException(
            "this session is open in another window; close it before importing"
                + " another archive with the same identity");
      }
      return archiveAdopter.adopt(validation, sessionsRoot, limits);
    } finally {
      lock.unlock();
    }
  }

  private boolean isActive(String sessionUuid) {
    synchronized (activeSessions) {
      return activeSessions.getOrDefault(sessionUuid, 0) > 0;
    }
  }

  private void deactivate(String sessionUuid) {
    // The protected source is already fully closed. Deactivation need not wait behind an
    // unrelated striped mutation: a cleanup that already observed this lease may safely keep
    // the session once more, while a later cleanup may remove it.
    synchronized (activeSessions) {
      int remaining = activeSessions.getOrDefault(sessionUuid, 0) - 1;
      if (remaining < 0) {
        throw new IllegalStateException(
            "session activation accounting became negative for " + sessionUuid);
      }
      if (remaining == 0) {
        activeSessions.remove(sessionUuid);
      } else {
        activeSessions.put(sessionUuid, remaining);
      }
    }
  }

  private List<ReentrantLock> acquireLocks(Collection<String> sessionUuids)
      throws InterruptedException {
    LinkedHashSet<Integer> indexes = new LinkedHashSet<>();
    sessionUuids.stream()
        .map(SessionMutationCoordinator::requireUuid)
        .map(this::lockIndex)
        .sorted()
        .forEach(indexes::add);
    List<ReentrantLock> held = new ArrayList<>(indexes.size());
    try {
      for (int index : indexes) {
        ReentrantLock lock = locks[index];
        lock.lockInterruptibly();
        held.add(lock);
      }
      return held;
    } catch (InterruptedException interrupted) {
      unlockReverse(held);
      throw interrupted;
    }
  }

  private ReentrantLock lockFor(String sessionUuid) {
    return locks[lockIndex(sessionUuid)];
  }

  private int lockIndex(String sessionUuid) {
    return Math.floorMod(sessionUuid.hashCode(), locks.length);
  }

  private static void unlockReverse(List<ReentrantLock> held) {
    for (int index = held.size() - 1; index >= 0; index--) {
      held.get(index).unlock();
    }
  }

  private static String requireUuid(String sessionUuid) {
    Objects.requireNonNull(sessionUuid, "sessionUuid");
    try {
      return SessionId.parseCanonical(sessionUuid).toString();
    } catch (IllegalArgumentException malformed) {
      throw new IllegalArgumentException("sessionUuid must be a canonical UUID", malformed);
    }
  }

  private static Path normalize(Path path) {
    return Objects.requireNonNull(path, "directory").toAbsolutePath().normalize();
  }

  private static void requireBackground(String operation) {
    if (SwingUtilities.isEventDispatchThread()) {
      throw new IllegalStateException(operation + " must not run on the EDT");
    }
  }

  @FunctionalInterface
  interface ArchiveAdopter {
    ArchiveImport.Result adopt(
        BvizReader.Validation validation, Path sessionsRoot, BvizLimits limits) throws IOException;
  }

  /** A counted, idempotent claim that keeps one session out of retention cleanup. */
  public static final class ActiveSession implements AutoCloseable {

    private final SessionMutationCoordinator owner;
    private final String sessionUuid;
    private final Path directory;
    private final AtomicBoolean guarded = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    private ActiveSession(SessionMutationCoordinator owner, String sessionUuid, Path directory) {
      this.owner = owner;
      this.sessionUuid = sessionUuid;
      this.directory = directory;
    }

    public String sessionUuid() {
      return sessionUuid;
    }

    public Path directory() {
      return directory;
    }

    /** Wraps a newly opened source so its ordinary close path releases this lease exactly once. */
    public SessionSource guard(SessionSource source) {
      Objects.requireNonNull(source, "source");
      if (closed.get()) {
        throw new IllegalStateException("the active-session lease is already closed");
      }
      if (!sessionUuid.equals(source.info().sessionId())
          || !directory.equals(normalize(source.info().root()))) {
        throw new IllegalArgumentException(
            "the source does not match active session " + sessionUuid + " at " + directory);
      }
      if (!guarded.compareAndSet(false, true)) {
        throw new IllegalStateException("the active-session lease already guards a source");
      }
      return new GuardedSessionSource(source, this);
    }

    @Override
    public void close() {
      if (closed.compareAndSet(false, true)) {
        owner.deactivate(sessionUuid);
      }
    }
  }

  /** Delegates every read operation and releases its lease after the source is truly closed. */
  private static final class GuardedSessionSource implements SessionSource {

    private final SessionSource delegate;
    private final ActiveSession activeSession;
    private final AtomicBoolean closed = new AtomicBoolean();

    private GuardedSessionSource(SessionSource delegate, ActiveSession activeSession) {
      this.delegate = delegate;
      this.activeSession = activeSession;
    }

    @Override
    public SessionInfo info() {
      return delegate.info();
    }

    @Override
    public SessionReader openReader() {
      return delegate.openReader();
    }

    @Override
    public EntityReader openEntityReader() {
      return delegate.openEntityReader();
    }

    @Override
    public GraphQueries openGraphQueries() {
      return delegate.openGraphQueries();
    }

    @Override
    public MetricQueries openMetricQueries() {
      return delegate.openMetricQueries();
    }

    @Override
    public StarlarkProfileReader openStarlarkProfileReader() {
      return delegate.openStarlarkProfileReader();
    }

    @Override
    public QueryReader openQueryReader() {
      return delegate.openQueryReader();
    }

    @Override
    public Connection openTimelineConnection() {
      return delegate.openTimelineConnection();
    }

    @Override
    public void close() {
      if (!closed.compareAndSet(false, true)) {
        return;
      }
      try {
        delegate.close();
      } finally {
        activeSession.close();
      }
    }
  }
}
