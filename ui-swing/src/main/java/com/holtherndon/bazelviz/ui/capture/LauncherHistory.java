package com.holtherndon.bazelviz.ui.capture;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

/**
 * The launcher's bounded command history, newest first.
 *
 * <p>Commands are values, not a log of clicks: recording the same command promotes it to newest
 * rather than adding another copy. Navigation keeps the editable draft that was present before the
 * first Up press and restores it after Down passes the newest entry.
 */
public final class LauncherHistory {

  /** Commands retained, persisted, and disclosed beside the command field. */
  public static final int MAX_ENTRIES = 50;

  private final List<String> entries = new ArrayList<>();
  private int selection = -1;
  private String draft = "";

  /** Records a non-blank command as the newest entry. */
  public void record(String command) {
    String normalized = normalize(command);
    if (normalized.isEmpty()) {
      return;
    }
    entries.remove(normalized);
    entries.add(0, normalized);
    if (entries.size() > MAX_ENTRIES) {
      entries.subList(MAX_ENTRIES, entries.size()).clear();
    }
    resetNavigation();
  }

  /**
   * Replaces the model from persisted newest-first values, removing corrupt duplicates without
   * changing the first occurrence's position.
   */
  public void replaceNewestFirst(List<String> commands) {
    entries.clear();
    LinkedHashSet<String> unique = new LinkedHashSet<>();
    for (String command : commands) {
      String normalized = normalize(command);
      if (!normalized.isEmpty()) {
        unique.add(normalized);
      }
      if (unique.size() == MAX_ENTRIES) {
        break;
      }
    }
    entries.addAll(unique);
    resetNavigation();
  }

  /** Moves from the draft toward older entries. */
  public Optional<String> olderThan(String currentText) {
    if (entries.isEmpty()) {
      return Optional.empty();
    }
    if (selection < 0) {
      draft = currentText == null ? "" : currentText;
      selection = 0;
    } else if (selection + 1 < entries.size()) {
      selection++;
    }
    return Optional.of(entries.get(selection));
  }

  /** Moves toward newer entries, then back to the editable draft. */
  public Optional<String> newerThan(String currentText) {
    if (selection < 0) {
      return Optional.empty();
    }
    if (selection > 0) {
      selection--;
      return Optional.of(entries.get(selection));
    }
    selection = -1;
    return Optional.of(draft);
  }

  /** Ends a history walk after the user edits the recalled text. */
  public void resetNavigation() {
    selection = -1;
    draft = "";
  }

  /** A persistence/selection snapshot, newest first. */
  public List<String> entries() {
    return List.copyOf(entries);
  }

  private static String normalize(String command) {
    return command == null ? "" : command.strip();
  }
}
