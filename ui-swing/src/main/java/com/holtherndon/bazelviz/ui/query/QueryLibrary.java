package com.holtherndon.bazelviz.ui.query;

import com.holtherndon.bazelviz.format.session.json.JsonReader;
import com.holtherndon.bazelviz.format.session.json.JsonValue;
import com.holtherndon.bazelviz.format.session.json.JsonWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The Query card's saved queries and saved views, as files a person can edit.
 *
 * <h2>Layout</h2>
 *
 * <p>Two sections under the application settings directory, one per kind:
 *
 * <pre>
 * settings/queries/&lt;file&gt;.sql   the statement, exactly as saved
 * settings/queries/index.json   [{"name": …, "file": …}, …] in display order
 * settings/views/&lt;file&gt;.sql     the view's SELECT body (not the CREATE)
 * settings/views/index.json     same shape
 * </pre>
 *
 * <p>Plain {@code .sql} files on purpose: the saved SQL is the user's own text
 * and gets to be edited, diffed and version-controlled with ordinary tools.
 * The JSON index carries the display name — which may hold characters a
 * filename cannot — and the ordering. A directory whose index is missing is
 * rebuilt from its {@code .sql} files with the file stem as the name, so
 * deleting the index loses ordering and display names but never the SQL.
 *
 * <p>A saved view stores only the SELECT body. The {@code CREATE TEMP VIEW}
 * around it is reconstructed at replay time by {@code AdHocQueries}, which is
 * what makes a rename a one-line index edit rather than SQL surgery.
 *
 * <h2>Threading</h2>
 *
 * <p>Every method does file I/O and blocks; callers keep them off the EDT
 * (QueryView routes them through its library executor). Methods are
 * synchronized so a save racing a list sees whole files.
 */
final class QueryLibrary {

    /** One saved query: a display name and the statement it stands for. */
    record SavedQuery(String name, String sql) { }

    /** One saved view: a display name that is also the view's name, and its body. */
    record SavedView(String name, String select) { }

    private record Entry(String name, String file) { }

    private static final String INDEX_FILE = "index.json";

    private final Path queriesDirectory;
    private final Path viewsDirectory;

    QueryLibrary(Path settingsDirectory) {
        Objects.requireNonNull(settingsDirectory, "settingsDirectory");
        this.queriesDirectory = settingsDirectory.resolve("queries");
        this.viewsDirectory = settingsDirectory.resolve("views");
    }

    // -------------------------------------------------------------- queries

    synchronized List<SavedQuery> queries() {
        List<SavedQuery> queries = new ArrayList<>();
        for (Entry entry : entries(queriesDirectory)) {
            readSql(queriesDirectory, entry)
                    .ifPresent(sql -> queries.add(new SavedQuery(entry.name(), sql)));
        }
        return List.copyOf(queries);
    }

    synchronized void saveQuery(String name, String sql) {
        save(queriesDirectory, name, sql);
    }

    synchronized void renameQuery(String oldName, String newName) {
        rename(queriesDirectory, oldName, newName);
    }

    synchronized void deleteQuery(String name) {
        delete(queriesDirectory, name);
    }

    // ---------------------------------------------------------------- views

    synchronized List<SavedView> views() {
        List<SavedView> views = new ArrayList<>();
        for (Entry entry : entries(viewsDirectory)) {
            readSql(viewsDirectory, entry)
                    .ifPresent(select -> views.add(new SavedView(entry.name(), select)));
        }
        return List.copyOf(views);
    }

    synchronized void saveView(String name, String select) {
        save(viewsDirectory, name, select);
    }

    synchronized void renameView(String oldName, String newName) {
        rename(viewsDirectory, oldName, newName);
    }

    synchronized void deleteView(String name) {
        delete(viewsDirectory, name);
    }

    /**
     * Ships the example views on a first run — a views section that has never
     * held anything, not one the user emptied, which is why the check is for
     * the directory rather than for the index: deleting your last view leaves
     * the index behind, and the examples stay gone.
     */
    synchronized void seedExampleViewsIfNeverUsed() {
        if (Files.isDirectory(viewsDirectory)) {
            return;
        }
        save(viewsDirectory, "actions_with_labels", """
                -- Actions with their interned label and mnemonic text resolved.
                -- LEFT JOIN, so an action whose label or mnemonic was never
                -- reported still appears, with NULL where the value is unknown.
                SELECT actions.id,
                       labels.value AS label,
                       mnemonics.value AS mnemonic,
                       actions.outcome,
                       actions.start_micros,
                       actions.end_micros,
                       actions.end_micros - actions.start_micros AS duration_micros
                FROM actions
                LEFT JOIN labels ON labels.id = actions.label_id
                LEFT JOIN mnemonics ON mnemonics.id = actions.mnemonic_id""");
        save(viewsDirectory, "mnemonic_totals", """
                -- Total execution time by mnemonic. timed_actions counts the
                -- rows whose duration is known; total_ms sums exactly those.
                SELECT mnemonics.value AS mnemonic,
                       COUNT(*) AS actions,
                       COUNT(actions.end_micros - actions.start_micros) AS timed_actions,
                       SUM(actions.end_micros - actions.start_micros) / 1000 AS total_ms
                FROM actions
                JOIN mnemonics ON mnemonics.id = actions.mnemonic_id
                GROUP BY mnemonics.value""");
    }

    // ------------------------------------------------------------- plumbing

    private void save(Path directory, String name, String sql) {
        requireName(name);
        Objects.requireNonNull(sql, "sql");
        try {
            Files.createDirectories(directory);
            List<Entry> entries = new ArrayList<>(entries(directory));
            Entry existing = find(entries, name);
            String file = existing != null ? existing.file() : freshFileName(directory, entries, name);
            Files.writeString(directory.resolve(file), sql, StandardCharsets.UTF_8);
            if (existing == null) {
                entries.add(new Entry(name, file));
                writeIndex(directory, entries);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot save \"" + name + "\" under " + directory, e);
        }
    }

    private void rename(Path directory, String oldName, String newName) {
        requireName(newName);
        List<Entry> entries = new ArrayList<>(entries(directory));
        Entry entry = find(entries, oldName);
        if (entry == null) {
            throw new IllegalArgumentException("nothing named \"" + oldName + "\" to rename");
        }
        if (find(entries, newName) != null) {
            throw new IllegalArgumentException("\"" + newName + "\" already exists");
        }
        entries.set(entries.indexOf(entry), new Entry(newName, entry.file()));
        try {
            writeIndex(directory, entries);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot rename \"" + oldName + "\"", e);
        }
    }

    private void delete(Path directory, String name) {
        List<Entry> entries = new ArrayList<>(entries(directory));
        Entry entry = find(entries, name);
        if (entry == null) {
            return;
        }
        entries.remove(entry);
        try {
            Files.deleteIfExists(directory.resolve(entry.file()));
            writeIndex(directory, entries);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot delete \"" + name + "\"", e);
        }
    }

    /**
     * The index's entries, or — when there is no index — one entry per
     * {@code .sql} file found, named by its stem and sorted, so hand-dropped
     * files show up without ceremony.
     */
    private List<Entry> entries(Path directory) {
        Path index = directory.resolve(INDEX_FILE);
        if (Files.isRegularFile(index)) {
            try {
                JsonValue parsed = JsonReader.parseFile(index);
                if (parsed instanceof JsonValue.JsonArray array) {
                    List<Entry> entries = new ArrayList<>();
                    for (JsonValue element : array.elements()) {
                        if (element instanceof JsonValue.JsonObject object) {
                            Optional<String> name = string(object, "name");
                            Optional<String> file = string(object, "file");
                            if (name.isPresent() && file.isPresent()
                                    && safeFileName(file.get())) {
                                entries.add(new Entry(name.get(), file.get()));
                            }
                        }
                    }
                    return entries;
                }
            } catch (IOException | RuntimeException unreadable) {
                // Fall through to the directory scan: a broken index must not
                // hide the SQL files, which are the data.
            }
        }
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (var listing = Files.list(directory)) {
            return listing
                    .filter(path -> path.getFileName().toString().endsWith(".sql"))
                    .sorted()
                    .map(path -> {
                        String file = path.getFileName().toString();
                        return new Entry(file.substring(0, file.length() - 4), file);
                    })
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot list " + directory, e);
        }
    }

    private Optional<String> readSql(Path directory, Entry entry) {
        Path file = directory.resolve(entry.file());
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
    }

    private void writeIndex(Path directory, List<Entry> entries) throws IOException {
        List<JsonValue> elements = new ArrayList<>(entries.size());
        for (Entry entry : entries) {
            Map<String, JsonValue> members = new LinkedHashMap<>();
            members.put("name", new JsonValue.JsonString(entry.name()));
            members.put("file", new JsonValue.JsonString(entry.file()));
            elements.add(new JsonValue.JsonObject(members));
        }
        Files.writeString(directory.resolve(INDEX_FILE),
                JsonWriter.writePretty(new JsonValue.JsonArray(elements)) + "\n",
                StandardCharsets.UTF_8);
    }

    private static Entry find(List<Entry> entries, String name) {
        for (Entry entry : entries) {
            if (entry.name().equals(name)) {
                return entry;
            }
        }
        return null;
    }

    private static Optional<String> string(JsonValue.JsonObject object, String key) {
        return object.member(key)
                .filter(value -> value instanceof JsonValue.JsonString)
                .map(value -> ((JsonValue.JsonString) value).value());
    }

    /**
     * A new file name for {@code name}: the name lower-cased with everything
     * but letters, digits, {@code -} and {@code _} squeezed to {@code -},
     * uniquified against both the index and the directory.
     */
    private static String freshFileName(Path directory, List<Entry> entries, String name) {
        String stem = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]+", "-")
                .replaceAll("^-+|-+$", "");
        if (stem.isEmpty()) {
            stem = "query";
        }
        String candidate = stem + ".sql";
        int counter = 2;
        while (fileTaken(directory, entries, candidate)) {
            candidate = stem + "-" + counter++ + ".sql";
        }
        return candidate;
    }

    private static boolean fileTaken(Path directory, List<Entry> entries, String file) {
        for (Entry entry : entries) {
            if (entry.file().equals(file)) {
                return true;
            }
        }
        return Files.exists(directory.resolve(file));
    }

    /**
     * True when an index-declared file name is a plain name rather than a
     * path: the index is user-editable, and an entry must not be able to point
     * the reader outside its own directory.
     */
    private static boolean safeFileName(String file) {
        return file.endsWith(".sql")
                && !file.contains("/")
                && !file.contains("\\")
                && !file.contains("..");
    }

    private static void requireName(String name) {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("a saved entry needs a name");
        }
    }
}
