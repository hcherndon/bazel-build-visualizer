package com.holtherndon.bazelviz.storage.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The session library: what survives a restart, what survives a move, and what
 * retention is allowed to delete.
 *
 * <p>The move is the interesting one. Plan 24's Phase 9 exit criterion pairs
 * "restart" with "relocation", and only the second can go wrong in a way that
 * looks like data loss: every path in the catalog is stale and every session is
 * still there.
 */
final class SessionCatalogTest {

    @TempDir
    Path tempDir;

    private static final long HOUR = 3_600_000_000L;

    private CatalogEntry entry(String uuid, Path directory, long openedMicros, long bytes) {
        return new CatalogEntry(
                uuid, "build //… (" + uuid + ")", directory,
                Optional.of("/Users/someone/code/p"), Optional.of("build //..."),
                Optional.of("8.4.1"), "READY",
                OptionalLong.of(openedMicros), OptionalLong.of(openedMicros + 1_000),
                OptionalLong.of(12), OptionalLong.of(340), OptionalLong.of(bytes),
                0, OptionalLong.of(openedMicros), false, false,
                Optional.of("12 actions · 1 ms"));
    }

    private Path sessionDirectory(String uuid) throws Exception {
        Path directory = tempDir.resolve("sessions").resolve("session-" + uuid);
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("manifest.json"), "{}");
        return directory;
    }

    @Test
    @DisplayName("a session recorded before a restart is there after one")
    void sessionsSurviveRestart() throws Exception {
        Path catalogDirectory = tempDir.resolve("catalog");
        Path directory = sessionDirectory("aaa");
        try (SessionCatalog catalog = SessionCatalog.open(catalogDirectory)) {
            catalog.record(entry("aaa", directory, 1_000, 4_096));
            catalog.setPinned("aaa", true);
        }

        try (SessionCatalog reopened = SessionCatalog.open(catalogDirectory)) {
            CatalogEntry found = reopened.find("aaa").orElseThrow();
            assertThat(found.directory()).isEqualTo(directory);
            assertThat(found.pinned()).isTrue();
            assertThat(found.summary()).contains("12 actions · 1 ms");
            assertThat(reopened.recent(10)).hasSize(1);
        }
    }

    @Test
    @DisplayName("moving the sessions root relocates every session, and loses none")
    void sessionsSurviveRelocation() throws Exception {
        Path catalogDirectory = tempDir.resolve("catalog");
        Path before = sessionDirectory("bbb");
        try (SessionCatalog catalog = SessionCatalog.open(catalogDirectory)) {
            catalog.record(entry("bbb", before, 1_000, 4_096));
            catalog.setPinned("bbb", true);
        }

        // The user moves their sessions root to an external disk.
        Path newRoot = tempDir.resolve("elsewhere");
        Files.createDirectories(newRoot);
        Path after = newRoot.resolve("session-bbb");
        Files.move(before, after);

        try (SessionCatalog catalog = SessionCatalog.open(catalogDirectory)) {
            SessionCatalog.RescanResult result = catalog.rescan(newRoot,
                    directory -> Optional.of(entry(
                            directory.getFileName().toString().substring("session-".length()),
                            directory, 1_000, 4_096)));

            // Matched by UUID, which is the one thing a move does not change.
            assertThat(result.relocated()).isEqualTo(1);
            assertThat(result.missing()).isZero();
            CatalogEntry found = catalog.find("bbb").orElseThrow();
            assertThat(found.directory()).isEqualTo(after);
            assertThat(found.missing()).isFalse();
            // And the user's own decision survived the rescan.
            assertThat(found.pinned()).isTrue();
        }
    }

    @Test
    @DisplayName("a session that is not there is marked, not deleted")
    void missingSessionsAreMarked() throws Exception {
        Path root = tempDir.resolve("sessions");
        Files.createDirectories(root);
        try (SessionCatalog catalog = SessionCatalog.open(tempDir.resolve("catalog"))) {
            catalog.record(entry("ccc", root.resolve("session-ccc"), 1_000, 10));

            SessionCatalog.RescanResult result = catalog.rescan(root, directory -> Optional.empty());

            // An unmounted external disk has not lost anybody's sessions, and a
            // list that silently shrank would tell them it had.
            assertThat(result.missing()).isEqualTo(1);
            assertThat(catalog.find("ccc").orElseThrow().missing()).isTrue();
            assertThat(result.describe()).contains("listed here and not found");
        }
    }

    @Test
    @DisplayName("re-recording a session does not unpin it or forget when it was opened")
    void userDecisionsSurviveARefresh() throws Exception {
        Path directory = sessionDirectory("ddd");
        try (SessionCatalog catalog = SessionCatalog.open(tempDir.resolve("catalog"))) {
            catalog.record(entry("ddd", directory, 1_000, 10));
            catalog.setPinned("ddd", true);
            catalog.touch("ddd", 9_999);

            // An enrichment finishes and the counts are refreshed.
            catalog.record(entry("ddd", directory, 1_000, 20));

            CatalogEntry found = catalog.find("ddd").orElseThrow();
            assertThat(found.totalBytes()).hasValue(20);
            assertThat(found.pinned()).isTrue();
            assertThat(found.lastOpenedMicros()).hasValue(9_999);
        }
    }

    @Test
    @DisplayName("forgetting a session leaves its directory alone")
    void forgettingIsNotDeleting() throws Exception {
        Path directory = sessionDirectory("eee");
        try (SessionCatalog catalog = SessionCatalog.open(tempDir.resolve("catalog"))) {
            catalog.record(entry("eee", directory, 1_000, 10));

            catalog.forget("eee");

            assertThat(catalog.find("eee")).isEmpty();
            assertThat(Files.exists(directory.resolve("manifest.json"))).isTrue();
        }
    }

    // --- retention ---------------------------------------------------------

    @Test
    @DisplayName("the default policy removes nothing")
    void theDefaultKeepsEverything() throws Exception {
        try (SessionCatalog catalog = SessionCatalog.open(tempDir.resolve("catalog"))) {
            catalog.record(entry("fff", sessionDirectory("fff"), 1_000, 10));

            RetentionPolicy.Plan plan =
                    catalog.plan(RetentionPolicy.keepEverything(), 100 * HOUR);

            assertThat(plan.isEmpty()).isTrue();
        }
    }

    @Test
    @DisplayName("a plan says exactly what it would remove, and removes nothing by itself")
    void aPlanIsNotASweep() throws Exception {
        try (SessionCatalog catalog = SessionCatalog.open(tempDir.resolve("catalog"))) {
            for (int i = 0; i < 5; i++) {
                String uuid = "s" + i;
                catalog.record(entry(uuid, sessionDirectory(uuid), i * HOUR, 1_000));
            }

            RetentionPolicy.Plan plan =
                    catalog.plan(RetentionPolicy.keepEverything().withMaxSessions(2), 10 * HOUR);

            assertThat(plan.candidates()).hasSize(3);
            assertThat(plan.bytesFreed()).isEqualTo(3_000);
            assertThat(String.join("\n", plan.lines()))
                    .contains("Would remove 3 sessions")
                    .contains("beyond the newest 2")
                    .contains("not reversible");
            // Nothing has happened yet.
            assertThat(Files.exists(tempDir.resolve("sessions/session-s0"))).isTrue();
        }
    }

    @Test
    @DisplayName("a pinned session is never removed, and the plan says how many it spared")
    void pinningWins() throws Exception {
        try (SessionCatalog catalog = SessionCatalog.open(tempDir.resolve("catalog"))) {
            for (int i = 0; i < 4; i++) {
                String uuid = "p" + i;
                catalog.record(entry(uuid, sessionDirectory(uuid), i * HOUR, 1_000));
            }
            catalog.setPinned("p0", true);

            RetentionPolicy.Plan plan =
                    catalog.plan(RetentionPolicy.keepEverything().withMaxSessions(1), 10 * HOUR);

            assertThat(plan.candidates()).extracting(candidate -> candidate.entry().sessionUuid())
                    .containsExactly("p1", "p2");
            assertThat(plan.pinnedSkipped()).isEqualTo(1);
            assertThat(String.join("\n", plan.lines())).contains("kept because they are pinned");
        }
    }

    @Test
    @DisplayName("a sweep deletes the directories the plan named, and only those")
    void aSweepDeletesWhatWasShown() throws Exception {
        try (SessionCatalog catalog = SessionCatalog.open(tempDir.resolve("catalog"))) {
            for (int i = 0; i < 3; i++) {
                String uuid = "d" + i;
                catalog.record(entry(uuid, sessionDirectory(uuid), i * HOUR, 500));
            }

            RetentionPolicy.Plan plan =
                    catalog.plan(RetentionPolicy.keepEverything().withMaxSessions(1), 10 * HOUR);
            SessionCatalog.SweepResult result = catalog.apply(plan);

            assertThat(result.removed()).isEqualTo(2);
            assertThat(result.bytesFreed()).isEqualTo(1_000);
            assertThat(Files.exists(tempDir.resolve("sessions/session-d0"))).isFalse();
            assertThat(Files.exists(tempDir.resolve("sessions/session-d1"))).isFalse();
            assertThat(Files.exists(tempDir.resolve("sessions/session-d2"))).isTrue();
            assertThat(catalog.recent(10)).hasSize(1);
        }
    }

    @Test
    @DisplayName("pinning a session after the plan was made still spares it")
    void pinningAfterThePlanStillWins() throws Exception {
        try (SessionCatalog catalog = SessionCatalog.open(tempDir.resolve("catalog"))) {
            catalog.record(entry("g0", sessionDirectory("g0"), 0, 100));
            catalog.record(entry("g1", sessionDirectory("g1"), HOUR, 100));
            RetentionPolicy.Plan plan =
                    catalog.plan(RetentionPolicy.keepEverything().withMaxSessions(1), 10 * HOUR);

            // The user changes their mind between seeing the plan and confirming.
            catalog.setPinned("g0", true);
            SessionCatalog.SweepResult result = catalog.apply(
                    new RetentionPolicy.Plan(
                            List.of(new RetentionPolicy.Candidate(
                                    catalog.find("g0").orElseThrow(), "beyond the newest 1")),
                            0, 100));

            assertThat(result.removed()).isZero();
            assertThat(result.failures()).anyMatch(text -> text.contains("pinned"));
            assertThat(Files.exists(tempDir.resolve("sessions/session-g0"))).isTrue();
            assertThat(plan.candidates()).hasSize(1);
        }
    }

    @Test
    @DisplayName("an age limit and a byte budget each select from the oldest end")
    void otherRulesSelectOldestFirst() throws Exception {
        try (SessionCatalog catalog = SessionCatalog.open(tempDir.resolve("catalog"))) {
            for (int i = 0; i < 4; i++) {
                String uuid = "a" + i;
                catalog.record(entry(uuid, sessionDirectory(uuid), i * HOUR, 1_000));
            }

            RetentionPolicy.Plan byAge = catalog.plan(
                    RetentionPolicy.keepEverything().withMaxAgeMicros(2 * HOUR), 4 * HOUR);
            assertThat(byAge.candidates())
                    .extracting(candidate -> candidate.entry().sessionUuid())
                    .containsExactly("a0", "a1");

            RetentionPolicy.Plan byBytes = catalog.plan(
                    RetentionPolicy.keepEverything().withMaxTotalBytes(2_500), 4 * HOUR);
            assertThat(byBytes.candidates())
                    .extracting(candidate -> candidate.entry().sessionUuid())
                    .containsExactly("a0", "a1");
        }
    }

    @Test
    @DisplayName("the catalog holds an index and not a second copy of the build")
    void theCatalogIsAnIndex() throws Exception {
        // Plan 10.6: "the catalog must not contain the full build data". A
        // table for actions or events here would be a second source of truth
        // that can disagree with the session and has to be migrated with it.
        try (SessionCatalog catalog = SessionCatalog.open(tempDir.resolve("catalog"))) {
            List<String> tables = new ArrayList<>();
            try (java.sql.Connection connection = java.sql.DriverManager.getConnection(
                            "jdbc:sqlite:" + catalog.file().toAbsolutePath());
                    Statement statement = connection.createStatement();
                    ResultSet rows = statement.executeQuery(
                            "SELECT name FROM sqlite_master WHERE type = 'table'"
                                    + " AND name NOT LIKE 'sqlite_%'")) {
                while (rows.next()) {
                    tables.add(rows.getString(1));
                }
            }

            assertThat(tables).containsExactlyInAnyOrder("catalog_metadata", "sessions");
        }
    }
}
