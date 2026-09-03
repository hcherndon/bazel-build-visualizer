package com.holtherndon.bazelviz.ui.capture;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class LauncherHistoryTest {

  @Test
  void keepsExactlyFiftyUniqueCommandsNewestFirstAndPromotesDuplicates() {
    LauncherHistory history = new LauncherHistory();
    for (int i = 0; i < 55; i++) {
      history.record("build //pkg:" + i);
    }

    assertThat(history.entries())
        .hasSize(LauncherHistory.MAX_ENTRIES)
        .first()
        .isEqualTo("build //pkg:54");
    assertThat(history.entries()).last().isEqualTo("build //pkg:5");

    history.record("build //pkg:20");
    assertThat(history.entries())
        .hasSize(LauncherHistory.MAX_ENTRIES)
        .startsWith("build //pkg:20", "build //pkg:54")
        .doesNotHaveDuplicates();
  }

  @Test
  void upAndDownWalkNewestFirstThenRestoreTheEditableDraft() {
    LauncherHistory history = new LauncherHistory();
    history.record("build //old");
    history.record("test //new");

    assertThat(history.olderThan("query //draft")).contains("test //new");
    assertThat(history.olderThan("ignored")).contains("build //old");
    assertThat(history.olderThan("ignored")).contains("build //old");
    assertThat(history.newerThan("ignored")).contains("test //new");
    assertThat(history.newerThan("ignored")).contains("query //draft");
    assertThat(history.newerThan("query //draft")).isEmpty();
  }

  @Test
  void persistedNewestFirstValuesStayInOrderAndAreNormalized() {
    LauncherHistory history = new LauncherHistory();
    history.replaceNewestFirst(List.of(" test //... ", "build //...", "test //...", " "));

    assertThat(history.entries()).containsExactly("test //...", "build //...");
  }
}
