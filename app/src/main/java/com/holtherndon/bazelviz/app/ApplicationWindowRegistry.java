package com.holtherndon.bazelviz.app;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * EDT-owned registry of native workspace windows.
 *
 * <p>Workspace profile IDs are the identity boundary. Opening an ID already in the registry focuses
 * its existing window instead of creating a second SSH control connection, terminal, or capture
 * owner.
 */
final class ApplicationWindowRegistry<H extends ApplicationWindowRegistry.Handle> {

  interface Handle {
    void showAndFocus();
  }

  record OpenResult<H>(H handle, boolean created) {}

  private final LinkedHashMap<String, H> windows = new LinkedHashMap<>();
  private final Set<String> closing = new HashSet<>();
  private H active;

  OpenResult<H> open(String workspaceId, Supplier<H> factory) {
    Objects.requireNonNull(workspaceId, "workspaceId");
    Objects.requireNonNull(factory, "factory");
    H existing = windows.get(workspaceId);
    if (existing != null) {
      active = existing;
      existing.showAndFocus();
      return new OpenResult<>(existing, false);
    }
    H created = Objects.requireNonNull(factory.get(), "factory result");
    windows.put(workspaceId, created);
    active = created;
    created.showAndFocus();
    return new OpenResult<>(created, true);
  }

  void activated(H handle) {
    if (windows.containsValue(handle) && !closing.contains(idOf(handle))) {
      active = handle;
    }
  }

  boolean beginClose(String workspaceId, H handle) {
    if (windows.get(workspaceId) != handle) {
      return false;
    }
    closing.add(workspaceId);
    if (active == handle) {
      active = lastOpenHandle().orElse(null);
    }
    return true;
  }

  boolean remove(String workspaceId, H handle) {
    if (!windows.remove(workspaceId, handle)) {
      return false;
    }
    closing.remove(workspaceId);
    if (active == handle) {
      active = lastOpenHandle().orElse(null);
    }
    return true;
  }

  Optional<H> get(String workspaceId) {
    return Optional.ofNullable(windows.get(workspaceId));
  }

  Optional<H> active() {
    if (active != null && !closing.contains(idOf(active))) {
      return Optional.of(active);
    }
    return lastOpenHandle();
  }

  List<H> snapshot() {
    return List.copyOf(windows.values());
  }

  List<H> restorableSnapshot() {
    // A window that has accepted close is no longer a routing target, but
    // it remains restorable until its asynchronous teardown actually
    // reaches windowClosed. If the process exits during cleanup, keeping
    // the entry is safer than silently forgetting the workspace.
    return snapshot();
  }

  boolean isClosing(String workspaceId) {
    return closing.contains(workspaceId);
  }

  boolean hasOpenWindows() {
    return windows.keySet().stream().anyMatch(id -> !closing.contains(id));
  }

  boolean isEmpty() {
    return windows.isEmpty();
  }

  int size() {
    return windows.size();
  }

  private Optional<H> lastOpenHandle() {
    H found = null;
    for (var entry : windows.entrySet()) {
      if (!closing.contains(entry.getKey())) {
        found = entry.getValue();
      }
    }
    return Optional.ofNullable(found);
  }

  private String idOf(H handle) {
    for (var entry : windows.entrySet()) {
      if (entry.getValue() == handle) {
        return entry.getKey();
      }
    }
    return "";
  }
}
