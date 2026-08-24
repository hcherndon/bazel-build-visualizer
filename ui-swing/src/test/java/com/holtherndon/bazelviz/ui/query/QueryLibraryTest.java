package com.holtherndon.bazelviz.ui.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The saved-query store as files a person can edit.
 *
 * <p>No Swing and no database: this is a directory of {@code .sql} files with
 * a JSON index, and the property that matters is that a human with a text
 * editor and this class always agree about what is saved.
 */
final class QueryLibraryTest {

    @TempDir
    Path temporary;

    private QueryLibrary library() {
        return new QueryLibrary(temporary);
    }

    @Test
    @DisplayName("a saved query round-trips: name, SQL, order")
    void savedQueriesRoundTrip() {
        QueryLibrary library = library();
        library.saveQuery("Slow actions", "SELECT * FROM actions ORDER BY 1");
        library.saveQuery("Counts", "SELECT COUNT(*) FROM actions");

        assertThat(library.queries())
                .extracting(QueryLibrary.SavedQuery::name)
                .containsExactly("Slow actions", "Counts");
        assertThat(library.queries().get(0).sql()).contains("ORDER BY 1");

        // Saving under an existing name overwrites the SQL, not adds a copy.
        library.saveQuery("Counts", "SELECT COUNT(*) FROM targets");
        assertThat(library.queries()).hasSize(2);
        assertThat(library.queries().get(1).sql()).contains("targets");
    }

    @Test
    @DisplayName("the files on disk are the user's: plain .sql plus a JSON index")
    void theStoreIsHumanEditable() throws Exception {
        QueryLibrary library = library();
        library.saveQuery("Slow actions!", "SELECT 1");

        Path queries = temporary.resolve("queries");
        assertThat(queries.resolve("slow-actions.sql")).exists();
        assertThat(Files.readString(queries.resolve("slow-actions.sql"))).isEqualTo("SELECT 1");
        String index = Files.readString(queries.resolve("index.json"));
        assertThat(index).contains("\"Slow actions!\"").contains("\"slow-actions.sql\"");

        // Editing the .sql by hand is editing the saved query.
        Files.writeString(queries.resolve("slow-actions.sql"), "SELECT 2");
        assertThat(library.queries().get(0).sql()).isEqualTo("SELECT 2");
    }

    @Test
    @DisplayName("a directory without an index is listed from its .sql files")
    void aMissingIndexIsRebuiltFromFiles() throws Exception {
        Path views = temporary.resolve("views");
        Files.createDirectories(views);
        Files.writeString(views.resolve("dropped_in.sql"), "SELECT id FROM actions");

        assertThat(library().views())
                .singleElement()
                .satisfies(view -> {
                    assertThat(view.name()).isEqualTo("dropped_in");
                    assertThat(view.select()).contains("FROM actions");
                });
    }

    @Test
    @DisplayName("an index entry that points outside the directory is ignored")
    void anEscapingIndexEntryIsIgnored() throws Exception {
        Path queries = temporary.resolve("queries");
        Files.createDirectories(queries);
        Files.writeString(queries.resolve("index.json"),
                "[{\"name\": \"escape\", \"file\": \"../../etc/passwd.sql\"},"
                        + " {\"name\": \"fine\", \"file\": \"fine.sql\"}]");
        Files.writeString(queries.resolve("fine.sql"), "SELECT 1");

        assertThat(library().queries())
                .extracting(QueryLibrary.SavedQuery::name)
                .containsExactly("fine");
    }

    @Test
    @DisplayName("rename changes the display name; delete removes file and entry")
    void renameAndDelete() {
        QueryLibrary library = library();
        library.saveView("v_one", "SELECT 1");
        library.saveView("v_two", "SELECT 2");

        library.renameView("v_one", "v_first");
        assertThat(library.views())
                .extracting(QueryLibrary.SavedView::name)
                .containsExactly("v_first", "v_two");
        assertThatThrownBy(() -> library.renameView("v_two", "v_first"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already exists");

        library.deleteView("v_first");
        assertThat(library.views())
                .extracting(QueryLibrary.SavedView::name)
                .containsExactly("v_two");
        assertThat(temporary.resolve("views").resolve("v_one.sql")).doesNotExist();
    }

    @Test
    @DisplayName("the examples ship once; an emptied library stays empty")
    void examplesSeedOnceOnly() {
        QueryLibrary library = library();
        library.seedExampleViewsIfNeverUsed();
        assertThat(library.views())
                .extracting(QueryLibrary.SavedView::name)
                .containsExactly("actions_with_labels", "mnemonic_totals");

        library.deleteView("actions_with_labels");
        library.deleteView("mnemonic_totals");
        library.seedExampleViewsIfNeverUsed();
        assertThat(library.views())
                .as("deleting the examples is a choice; a reseed would overrule it")
                .isEmpty();
    }
}
