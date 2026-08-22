package com.holtherndon.bazelviz.storage.entities;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.core.domain.ActionOutcome;
import com.holtherndon.bazelviz.core.entity.ActionTiming;
import com.holtherndon.bazelviz.core.entity.EntityCommand;
import com.holtherndon.bazelviz.storage.SessionDatabase;
import com.holtherndon.bazelviz.storage.schema.MigrationRunner;
import com.holtherndon.bazelviz.storage.schema.SchemaIndexes;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Paging, sorting and filtering the actions table.
 *
 * <p>The property that matters most is the boring one: walking every page under
 * every sort must visit every row exactly once. A keyset predicate that gets
 * NULL handling wrong does not throw — it quietly returns fewer rows than the
 * count promised, and the rows it drops are exactly the ones with unknown
 * values.
 */
final class ActionQueriesTest {

    /** Small enough that the fixture spans several pages under every sort. */
    private static final int PAGE = 3;

    @TempDir
    Path tempDir;

    private SessionDatabase database;
    private Connection connection;
    private long streamId;
    private long nextSequence = 1;

    @BeforeEach
    void buildFixture() throws Exception {
        database = SessionDatabase.open(tempDir.resolve("actions.db"));
        MigrationRunner.standard().migrate(database);
        connection = database.writerConnection();
        exec("INSERT INTO event_streams (stream_key, state) VALUES ('s', 'OPEN')");
        try (Statement s = connection.createStatement();
                var rows = s.executeQuery("SELECT last_insert_rowid()")) {
            rows.next();
            streamId = rows.getLong(1);
        }

        try (EntityWriter writer = new EntityWriter(connection)) {
            // A deliberately awkward mix: two mnemonics, a failure, actions with
            // no label at all, and -- the point of the exercise -- some with
            // timing and some without, so every sort has NULLs somewhere in it.
            add(writer, action("bazel-out/a1.o", "//pkg:a", "CppCompile", true, 300, 40));
            add(writer, action("bazel-out/a2.o", "//pkg:a", "CppCompile", true, 100, 10));
            add(writer, action("bazel-out/b1.jar", "//pkg:b", "Javac", true, 200, 90));
            add(writer, action("bazel-out/b2.jar", "//pkg:b", "Javac", false, 400, 5));
            add(writer, untimed("bazel-out/status.txt", Optional.empty(), Optional.empty()));
            add(writer, untimed("bazel-out/c1.o", Optional.of("//pkg:c"), Optional.of("Genrule")));
            add(writer, untimed("bazel-out/c2.o", Optional.of("//pkg:c"), Optional.empty()));
            writer.flush();
        }
        SchemaIndexes.createAll(connection);
    }

    @AfterEach
    void closeSession() throws Exception {
        database.close();
    }

    @ParameterizedTest
    @EnumSource(ActionSort.class)
    @DisplayName("walking every page under a sort visits every row exactly once")
    void pagingIsCompleteUnderEverySort(ActionSort sort) throws Exception {
        for (boolean descending : new boolean[] {false, true}) {
            try (ActionQueries queries = queries()) {
                List<Long> seen = walk(queries, ActionFilter.NONE, sort, descending);

                assertThat(seen)
                        .as("%s %s", sort, descending ? "descending" : "ascending")
                        .hasSize((int) queries.totalCount())
                        .doesNotHaveDuplicates();
            }
        }
    }

    @ParameterizedTest
    @EnumSource(ActionSort.class)
    @DisplayName("descending is the reverse of ascending, unknowns included")
    void descendingReversesAscending(ActionSort sort) throws Exception {
        try (ActionQueries queries = queries()) {
            List<Long> ascending = walk(queries, ActionFilter.NONE, sort, false);
            List<Long> descending = walk(queries, ActionFilter.NONE, sort, true);

            // Not merely the same set: the same sequence, backwards. Anything
            // else means the NULL term and the value term disagree about which
            // way round they are, which shows up as rows changing neighbours
            // when the user clicks a column header twice.
            List<Long> reversed = new ArrayList<>(descending);
            java.util.Collections.reverse(reversed);
            assertThat(reversed).as("%s", sort).isEqualTo(ascending);
        }
    }

    @Test
    @DisplayName("rows with no start time are kept, together, at one end")
    void unknownsAreKeptAtOneEnd() throws Exception {
        try (ActionQueries queries = queries()) {
            List<ActionRow> ascending =
                    pageAll(queries, ActionFilter.NONE, ActionSort.START_TIME, false);

            // Three actions were never timed. A predicate of `start > ?` would
            // have dropped all three from every page after the first, and the
            // table would have shown four rows above a count of seven.
            assertThat(ascending).hasSize(7);
            // Unknowns first ascending: SQLite's own NULL ordering, adopted
            // because it is the only version an index can seek. The
            // alternative -- unknowns last in both directions -- was measured
            // costing 18.8 ms a page at the tail of a 200,000-row table where
            // this costs 0.07 ms anywhere.
            assertThat(ascending.subList(0, 3)).allMatch(row -> row.startMicros().isEmpty());
            assertThat(ascending.subList(3, 7)).allMatch(row -> row.startMicros().isPresent());

            List<ActionRow> descending =
                    pageAll(queries, ActionFilter.NONE, ActionSort.START_TIME, true);
            assertThat(descending).hasSize(7);
            assertThat(descending.subList(0, 4)).allMatch(row -> row.startMicros().isPresent());
            assertThat(descending.subList(4, 7)).allMatch(row -> row.startMicros().isEmpty());
        }
    }

    @Test
    @DisplayName("a duration Bazel could not measure is unknown, and says why")
    void durationsCarryTheirReason() throws Exception {
        try (ActionQueries queries = queries()) {
            ActionRow untimed = queries.actionByOutput("bazel-out/status.txt").orElseThrow();

            assertThat(untimed.duration().isKnown()).isFalse();
            assertThat(untimed.duration().warning().orElseThrow())
                    .contains("does not report action timestamps");
            assertThat(untimed.label()).isEmpty();

            ActionRow timed = queries.actionByOutput("bazel-out/a1.o").orElseThrow();
            assertThat(timed.duration().isKnown()).isTrue();
            assertThat(timed.duration().value()).hasValue(40L);
        }
    }

    @Test
    @DisplayName("an action Bazel timed as zero-length sorts as unknown, not as fastest")
    void zeroLengthSpansDoNotWinTheDurationSort() throws Exception {
        try (EntityWriter writer = new EntityWriter(connection)) {
            // Bazel 8.4.x reports this for every action in the build. Stored as
            // a zero duration it would take every top place in "slowest first"
            // reversed, and bury the real timings.
            add(writer, new EntityCommand.ActionCompleted(
                    "bazel-out/zero.o",
                    Optional.of("//pkg:z"),
                    "cfg-1",
                    Optional.of("Genrule"),
                    true,
                    OptionalInt.empty(),
                    Optional.empty(),
                    ActionTiming.of(OptionalLong.of(5_000L), OptionalLong.of(5_000L)),
                    List.of(),
                    Optional.empty(),
                    Optional.empty()));
            writer.flush();
        }

        try (ActionQueries queries = queries()) {
            List<ActionRow> rows = pageAll(queries, ActionFilter.NONE, ActionSort.DURATION, false);
            int position = -1;
            for (int i = 0; i < rows.size(); i++) {
                if (rows.get(i).primaryOutput().equals("bazel-out/zero.o")) {
                    position = i;
                }
            }
            ActionRow zero = rows.get(position);

            assertThat(zero.durationMicros()).isEmpty();
            assertThat(zero.durationUnknownReason()).hasValue(ActionTiming.ZERO_LENGTH_SPAN);
            // It sits among the unknowns, not among the measured durations.
            // Stored as a zero it would take first place in "fastest first" and
            // last place in "slowest first", in a build where every action
            // reports the same fiction.
            assertThat(rows.subList(0, position + 1))
                    .allMatch(row -> row.durationMicros().isEmpty());
            assertThat(rows.getLast().durationMicros()).isPresent();
        }
    }

    @Test
    @DisplayName("a filtered count is the filtered count, and the total is still available")
    void filteringReportsBothNumbers() throws Exception {
        try (ActionQueries queries = queries()) {
            ActionFilter cpp = ActionFilter.NONE.withMnemonic(Optional.of("CppCompile"));

            assertThat(queries.count(cpp)).isEqualTo(2);
            assertThat(queries.totalCount()).isEqualTo(7);
            assertThat(pageAll(queries, cpp, ActionSort.ARRIVAL, false))
                    .allMatch(row -> row.mnemonic().equals(Optional.of("CppCompile")));

            ActionFilter failures =
                    ActionFilter.NONE.withOutcome(Optional.of(ActionOutcome.FAILED));
            assertThat(queries.count(failures)).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("a filter's wildcards are the user's text, not SQL's")
    void likeWildcardsAreEscaped() throws Exception {
        try (EntityWriter writer = new EntityWriter(connection)) {
            add(writer, untimed("bazel-out/a_b.o", Optional.of("//pkg:a_b"), Optional.empty()));
            add(writer, untimed("bazel-out/axb.o", Optional.of("//pkg:axb"), Optional.empty()));
            writer.flush();
        }

        try (ActionQueries queries = queries()) {
            // Unescaped, LIKE reads "_" as "any character" and this would match
            // both -- a filter quietly showing more than the user asked for.
            ActionFilter underscore = ActionFilter.NONE.withTextContains(Optional.of("a_b"));
            assertThat(queries.count(underscore)).isEqualTo(1);
            assertThat(pageAll(queries, underscore, ActionSort.ARRIVAL, false))
                    .singleElement()
                    .satisfies(row -> assertThat(row.primaryOutput()).isEqualTo("bazel-out/a_b.o"));
        }
    }

    @Test
    @DisplayName("filtering by label excludes actions that have none, visibly")
    void labelFilterExcludesUnlabelledActions() throws Exception {
        try (ActionQueries queries = queries()) {
            ActionFilter pkg = ActionFilter.NONE.withLabelContains(Optional.of("//pkg:"));

            // The workspace-status action has no label, so "contains" cannot be
            // true of it. It is excluded, and the filtered count says 6 rather
            // than pretending the 7th matched.
            assertThat(queries.count(pkg)).isEqualTo(6);
            assertThat(pageAll(queries, pkg, ActionSort.ARRIVAL, false))
                    .allMatch(row -> row.label().isPresent());
        }
    }

    @Test
    @DisplayName("the mnemonic list comes from the actions actually stored")
    void mnemonicsComeFromTheRows() throws Exception {
        try (ActionQueries queries = queries()) {
            List<ActionQueries.MnemonicCount> mnemonics = queries.mnemonics();

            // Bazel's own per-mnemonic breakdown drops fully-cached mnemonics on
            // 6.5 and 7.6, so a filter built from it would offer choices that
            // match nothing and omit ones on screen.
            assertThat(mnemonics).extracting(ActionQueries.MnemonicCount::mnemonic)
                    .containsExactly("CppCompile", "Javac", "Genrule");
            assertThat(mnemonics.getFirst().actions()).isEqualTo(2);
        }
    }

    @ParameterizedTest
    @EnumSource(ActionSort.class)
    @DisplayName("a page reached by its anchor is the same page reached by walking")
    void indexAnchorsAddressTheSamePages(ActionSort sort) throws Exception {
        for (boolean descending : new boolean[] {false, true}) {
            try (ActionQueries queries = queries()) {
                ActionQueries.Index index =
                        queries.buildIndex(ActionFilter.NONE, sort, descending, PAGE);
                List<ActionRow> walked = pageAll(queries, ActionFilter.NONE, sort, descending);

                assertThat(index.rowCount()).isEqualTo(walked.size());

                // Jumping straight to a page must give the same rows as
                // scrolling to it. This is the whole point of the anchor
                // array -- and the thing that breaks silently when the seek
                // predicate and the ORDER BY drift apart.
                long pages = (walked.size() + PAGE - 1) / PAGE;
                for (long page = 0; page < pages; page++) {
                    List<ActionRow> jumped = index.anchorFor(page)
                            .map(anchor -> fetch(queries, anchor, sort, descending))
                            .orElseGet(() -> fetchFirst(queries, sort, descending));
                    int from = (int) (page * PAGE);
                    int to = Math.min(from + PAGE, walked.size());
                    assertThat(jumped.stream().map(ActionRow::id).toList())
                            .as("%s %s page %d", sort, descending ? "desc" : "asc", page)
                            .isEqualTo(walked.subList(from, to).stream().map(ActionRow::id).toList());
                }
            }
        }
    }

    @Test
    @DisplayName("the index counts what the filter counts")
    void indexAgreesWithTheFilteredCount() throws Exception {
        try (ActionQueries queries = queries()) {
            ActionFilter cpp = ActionFilter.NONE.withMnemonic(Optional.of("CppCompile"));
            ActionQueries.Index index = queries.buildIndex(cpp, ActionSort.ARRIVAL, false, PAGE);

            // Two rows and a page size of three: one page, no anchors, and a
            // count that matches the separate COUNT query rather than the
            // unfiltered total.
            assertThat(index.rowCount()).isEqualTo(queries.count(cpp));
            assertThat(index.anchors()).isEmpty();
            assertThat(index.anchorFor(0)).isEmpty();
        }
    }

    // --- helpers ---------------------------------------------------------

    private static List<ActionRow> fetch(
            ActionQueries queries, ActionQueries.Anchor anchor, ActionSort sort, boolean descending) {
        try {
            return queries.pageAfter(anchor, ActionFilter.NONE, sort, descending, PAGE);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<ActionRow> fetchFirst(
            ActionQueries queries, ActionSort sort, boolean descending) {
        try {
            return queries.firstPage(ActionFilter.NONE, sort, descending, PAGE);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private ActionQueries queries() throws SQLException {
        return new ActionQueries(database.newReadConnection());
    }

    /** Every row, one page at a time, exactly as the table model would walk it. */
    private static List<ActionRow> pageAll(
            ActionQueries queries, ActionFilter filter, ActionSort sort, boolean descending)
            throws SQLException {
        List<ActionRow> all = new ArrayList<>();
        List<ActionRow> page = queries.firstPage(filter, sort, descending, PAGE);
        while (!page.isEmpty()) {
            all.addAll(page);
            page = queries.pageAfter(page.getLast(), filter, sort, descending, PAGE);
        }
        return all;
    }

    private static List<Long> walk(
            ActionQueries queries, ActionFilter filter, ActionSort sort, boolean descending)
            throws SQLException {
        return pageAll(queries, filter, sort, descending).stream().map(ActionRow::id).toList();
    }

    private void add(EntityWriter writer, EntityCommand command) throws SQLException {
        writer.apply(streamId, event(), command);
    }

    private EntityCommand.ActionCompleted action(
            String output, String label, String mnemonic, boolean success, long start, long length) {
        return new EntityCommand.ActionCompleted(
                output,
                Optional.of(label),
                "cfg-1",
                Optional.of(mnemonic),
                success,
                success ? OptionalInt.empty() : OptionalInt.of(1),
                Optional.empty(),
                ActionTiming.of(OptionalLong.of(start), OptionalLong.of(start + length)),
                List.of(),
                Optional.empty(),
                Optional.empty());
    }

    private EntityCommand.ActionCompleted untimed(
            String output, Optional<String> label, Optional<String> mnemonic) {
        return new EntityCommand.ActionCompleted(
                output,
                label,
                "cfg-1",
                mnemonic,
                true,
                OptionalInt.empty(),
                Optional.empty(),
                ActionTiming.NONE,
                List.of(),
                Optional.empty(),
                Optional.empty());
    }

    private long event() throws SQLException {
        long sequence = nextSequence++;
        exec("INSERT INTO bep_events (stream_id, sequence, event_type, raw_segment, raw_offset,"
                + " raw_length, decode_status, receive_micros) VALUES ("
                + streamId + ", " + sequence + ", 1, 0, 0, 0, 'OK', 0)");
        return sequence;
    }

    private void exec(String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
