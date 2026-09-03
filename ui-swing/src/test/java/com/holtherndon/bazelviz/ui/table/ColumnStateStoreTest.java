package com.holtherndon.bazelviz.ui.table;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The column-state file: what a view remembers across restarts, and — just as important — what
 * happens when the file is not what it should be. A corrupt preferences file must cost the user
 * their column widths, and nothing else.
 */
final class ColumnStateStoreTest {

  private static ColumnState sample() {
    return new ColumnState(
        List.of("Target", "Duration", "Mnemonic"),
        Map.of("Target", 320, "Duration", 90, "Mnemonic", 140),
        Set.of("Mnemonic"),
        Optional.of(new ColumnState.Sort("Duration", true)));
  }

  @Test
  @DisplayName("what was saved is what loads back, member for member")
  void roundTrips(@TempDir Path settings) {
    ColumnStateStore store = new ColumnStateStore(settings, "actions");
    store.save(sample());

    assertThat(new ColumnStateStore(settings, "actions").load()).isEqualTo(sample());
    assertThat(store.file()).isEqualTo(settings.resolve("columns").resolve("actions.json"));
  }

  @Test
  @DisplayName("no file yet is simply the defaults, not an error")
  void absentFileLoadsEmpty(@TempDir Path settings) {
    assertThat(new ColumnStateStore(settings, "events").load()).isEqualTo(ColumnState.empty());
  }

  @Test
  @DisplayName("a corrupt file degrades to the defaults, silently")
  void corruptFileLoadsEmpty(@TempDir Path settings) throws Exception {
    ColumnStateStore store = new ColumnStateStore(settings, "errors");
    Files.createDirectories(store.file().getParent());
    Files.writeString(store.file(), "{not json at all", StandardCharsets.UTF_8);

    assertThat(store.load()).isEqualTo(ColumnState.empty());
  }

  @Test
  @DisplayName("a file that is JSON but the wrong shape keeps what it can")
  void wrongShapeDegradesMemberByMember(@TempDir Path settings) throws Exception {
    ColumnStateStore store = new ColumnStateStore(settings, "tests");
    Files.createDirectories(store.file().getParent());
    // widths is an array (wrong), hidden holds a number (wrong), sort has
    // no key (wrong) — but order is fine and must survive.
    Files.writeString(
        store.file(),
        """
        {"order": ["Test", "Status"],
         "widths": [1, 2],
         "hidden": [42],
         "sort": {"descending": true}}
        """,
        StandardCharsets.UTF_8);

    ColumnState loaded = store.load();
    assertThat(loaded.order()).containsExactly("Test", "Status");
    assertThat(loaded.widths()).isEmpty();
    assertThat(loaded.hidden()).isEmpty();
    assertThat(loaded.sort()).isEmpty();
  }

  @Test
  @DisplayName("a nonsensical width is dropped rather than applied")
  void badWidthsAreDropped(@TempDir Path settings) throws Exception {
    ColumnStateStore store = new ColumnStateStore(settings, "query");
    Files.createDirectories(store.file().getParent());
    Files.writeString(
        store.file(),
        """
        {"widths": {"good": 200, "zero": 0, "negative": -4, "text": "wide"}}
        """,
        StandardCharsets.UTF_8);

    assertThat(store.load().widths()).containsExactly(Map.entry("good", 200));
  }

  @Test
  @DisplayName("the view id is tamed into a plain file name")
  void viewIdBecomesAPlainFileName(@TempDir Path settings) {
    assertThat(new ColumnStateStore(settings, "My View/2").file().getFileName().toString())
        .isEqualTo("my-view-2.json");
  }
}
