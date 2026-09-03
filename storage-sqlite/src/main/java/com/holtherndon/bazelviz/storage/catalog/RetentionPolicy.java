package com.holtherndon.bazelviz.storage.catalog;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * When a session may be cleaned up, and the plan that says which ones.
 *
 * <h2>Nothing is deleted without a plan being shown first</h2>
 *
 * <p>A session is a capture of work somebody did, sometimes the only record of a failure that has
 * since stopped reproducing. So retention produces a {@link Plan} — the exact list, with why each
 * one is on it and how much space it would free — and deletes only when that plan is handed back. A
 * sweep that ran on a timer and reported afterwards would be the wrong shape for the thing being
 * swept.
 *
 * <h2>A pinned session is never on the list</h2>
 *
 * <p>Pinning is the only mechanism a user has for saying "this one matters", and a retention rule
 * that could override it would make pinning advisory. {@link Plan} carries the pinned count it
 * skipped, so a user whose disk is still full can see why.
 *
 * @param maxSessions keep at most this many, newest first; absent for no limit
 * @param maxAgeMicros drop sessions older than this; absent for no limit
 * @param maxTotalBytes drop oldest until the total fits; absent for no limit
 */
public record RetentionPolicy(
    OptionalLong maxSessions, OptionalLong maxAgeMicros, OptionalLong maxTotalBytes) {

  public RetentionPolicy {
    Objects.requireNonNull(maxSessions, "maxSessions");
    Objects.requireNonNull(maxAgeMicros, "maxAgeMicros");
    Objects.requireNonNull(maxTotalBytes, "maxTotalBytes");
  }

  /**
   * Keep everything.
   *
   * <p>The default, deliberately. Disk is cheap and a build capture the user did not know they
   * would need is the one they will ask for; an application that quietly deleted them by default
   * would be optimising the wrong thing.
   */
  public static RetentionPolicy keepEverything() {
    return new RetentionPolicy(OptionalLong.empty(), OptionalLong.empty(), OptionalLong.empty());
  }

  public RetentionPolicy withMaxSessions(long value) {
    return new RetentionPolicy(OptionalLong.of(value), maxAgeMicros, maxTotalBytes);
  }

  public RetentionPolicy withMaxAgeMicros(long value) {
    return new RetentionPolicy(maxSessions, OptionalLong.of(value), maxTotalBytes);
  }

  public RetentionPolicy withMaxTotalBytes(long value) {
    return new RetentionPolicy(maxSessions, maxAgeMicros, OptionalLong.of(value));
  }

  /** True when this policy would never remove anything. */
  public boolean keepsEverything() {
    return maxSessions.isEmpty() && maxAgeMicros.isEmpty() && maxTotalBytes.isEmpty();
  }

  /** One session proposed for removal, and the rule that proposed it. */
  public record Candidate(CatalogEntry entry, String reason) {

    public Candidate {
      Objects.requireNonNull(entry, "entry");
      Objects.requireNonNull(reason, "reason");
    }
  }

  /**
   * What a sweep would do.
   *
   * @param pinnedSkipped sessions a rule selected and pinning protected
   * @param bytesFreed the total of what the candidates report, which is a figure from the catalog
   *     rather than a fresh measurement of the disk
   */
  public record Plan(List<Candidate> candidates, long pinnedSkipped, long bytesFreed) {

    public Plan {
      candidates = List.copyOf(candidates);
    }

    public boolean isEmpty() {
      return candidates.isEmpty();
    }

    /** The sentences shown before anything is deleted. */
    public List<String> lines() {
      List<String> lines = new ArrayList<>();
      if (candidates.isEmpty()) {
        lines.add(
            pinnedSkipped == 0
                ? "Nothing matches the retention policy."
                : "Nothing to remove: the "
                    + pinnedSkipped
                    + " sessions the policy selected are all pinned.");
        return List.copyOf(lines);
      }
      lines.add(
          "Would remove "
              + candidates.size()
              + (candidates.size() == 1 ? " session" : " sessions")
              + ", freeing about "
              + bytesFreed
              + " bytes.");
      for (Candidate candidate : candidates) {
        lines.add("  " + candidate.entry().displayName() + " — " + candidate.reason());
      }
      if (pinnedSkipped > 0) {
        lines.add(
            pinnedSkipped
                + " further "
                + (pinnedSkipped == 1 ? "session was" : "sessions were")
                + " selected by the policy and kept because they are pinned.");
      }
      lines.add(
          "A session is a capture of work somebody did. Deleting one is not" + " reversible.");
      return List.copyOf(lines);
    }
  }
}
