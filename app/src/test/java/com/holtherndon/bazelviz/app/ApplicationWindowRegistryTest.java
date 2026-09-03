package com.holtherndon.bazelviz.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

final class ApplicationWindowRegistryTest {

  @Test
  @DisplayName("opening the same workspace ID focuses one native window")
  void sameWorkspaceFocusesExistingWindow() {
    ApplicationWindowRegistry<FakeWindow> registry = new ApplicationWindowRegistry<>();
    AtomicInteger creations = new AtomicInteger();

    ApplicationWindowRegistry.OpenResult<FakeWindow> first =
        registry.open("workspace-a", () -> new FakeWindow(creations.incrementAndGet()));
    ApplicationWindowRegistry.OpenResult<FakeWindow> second =
        registry.open("workspace-a", () -> new FakeWindow(creations.incrementAndGet()));

    assertThat(first.created()).isTrue();
    assertThat(second.created()).isFalse();
    assertThat(second.handle()).isSameAs(first.handle());
    assertThat(creations).hasValue(1);
    assertThat(first.handle().focuses).isEqualTo(2);
    assertThat(registry.size()).isEqualTo(1);
    assertThat(registry.active()).contains(first.handle());
  }

  @Test
  @DisplayName("distinct workspaces coexist and closing one preserves the other")
  void distinctWorkspacesHaveIndependentLifecycles() {
    ApplicationWindowRegistry<FakeWindow> registry = new ApplicationWindowRegistry<>();
    FakeWindow first = registry.open("workspace-a", () -> new FakeWindow(1)).handle();
    FakeWindow second = registry.open("workspace-b", () -> new FakeWindow(2)).handle();

    assertThat(registry.snapshot()).containsExactly(first, second);
    assertThat(registry.active()).contains(second);
    assertThat(registry.remove("workspace-a", first)).isTrue();
    assertThat(registry.snapshot()).containsExactly(second);
    assertThat(registry.active()).contains(second);
    assertThat(registry.isEmpty()).isFalse();
  }

  @Test
  @DisplayName("a stale close callback cannot remove a replacement window")
  void staleCloseCannotRemoveReplacement() {
    ApplicationWindowRegistry<FakeWindow> registry = new ApplicationWindowRegistry<>();
    FakeWindow old = registry.open("workspace-a", () -> new FakeWindow(1)).handle();
    assertThat(registry.remove("workspace-a", old)).isTrue();
    FakeWindow replacement = registry.open("workspace-a", () -> new FakeWindow(2)).handle();

    assertThat(registry.remove("workspace-a", old)).isFalse();
    assertThat(registry.get("workspace-a")).contains(replacement);
  }

  @Test
  @DisplayName("a closing workspace stops routing but remains restorable until teardown")
  void closingWorkspaceStopsRoutingBeforeTeardownCompletes() {
    ApplicationWindowRegistry<FakeWindow> registry = new ApplicationWindowRegistry<>();
    FakeWindow first = registry.open("workspace-a", () -> new FakeWindow(1)).handle();
    FakeWindow second = registry.open("workspace-b", () -> new FakeWindow(2)).handle();

    assertThat(registry.beginClose("workspace-b", second)).isTrue();

    assertThat(registry.isClosing("workspace-b")).isTrue();
    assertThat(registry.active()).contains(first);
    assertThat(registry.restorableSnapshot()).containsExactly(first, second);
    assertThat(registry.hasOpenWindows()).isTrue();

    assertThat(registry.beginClose("workspace-a", first)).isTrue();
    assertThat(registry.active()).isEmpty();
    assertThat(registry.restorableSnapshot()).containsExactly(first, second);
    assertThat(registry.hasOpenWindows()).isFalse();
    assertThat(registry.snapshot()).containsExactly(first, second);

    assertThat(registry.remove("workspace-b", second)).isTrue();
    assertThat(registry.restorableSnapshot()).containsExactly(first);
  }

  private static final class FakeWindow implements ApplicationWindowRegistry.Handle {
    private final int number;
    private int focuses;

    private FakeWindow(int number) {
      this.number = number;
    }

    @Override
    public void showAndFocus() {
      focuses++;
    }

    @Override
    public String toString() {
      return "window-" + number;
    }
  }
}
