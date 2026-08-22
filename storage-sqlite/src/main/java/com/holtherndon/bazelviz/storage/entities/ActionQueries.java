package com.holtherndon.bazelviz.storage.entities;

import com.holtherndon.bazelviz.core.domain.ActionOutcome;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * The actions table's read path: a filtered, sorted, keyset-paged view over
 * {@code actions}.
 *
 * <h2>Keyset under every sort</h2>
 *
 * <p>Sorting and keyset paging are usually presented as alternatives, because
 * the seek predicate has to know the sort. Here it does: {@link Keyset} builds
 * the predicate from the same column expression the {@code ORDER BY} uses, so
 * changing the sort changes both together and no path falls back to
 * {@code OFFSET}. See {@link Keyset} for why NULL needs its own term, and what
 * goes wrong without it.
 *
 * <h2>Filtered counts are separate from total counts</h2>
 *
 * <p>{@link #count} answers "how many rows match", {@link #totalCount} answers
 * "how many rows are there". The view shows both whenever a filter is on,
 * because a table showing 12 rows and the number 12 is indistinguishable from a
 * build that ran 12 actions.
 *
 * <h2>Threading</h2>
 *
 * <p>One instance wraps one connection and is not thread-safe; each reader
 * thread gets its own. Nothing here writes, and the connection stays in
 * auto-commit so each statement is its own short read transaction (plan 10.9).
 * None of it may run on the EDT.
 */
public final class ActionQueries implements AutoCloseable {

    private static final String COLUMNS =
            "a.id, a.primary_output, l.value, m.value, a.outcome, a.start_micros,"
                    + " CASE WHEN a.duration_unknown_reason IS NULL"
                    + "   THEN a.end_micros - a.start_micros END,"
                    + " a.duration_unknown_reason, a.bazel_exit_code, a.spawn_exit_code,"
                    + " a.failure_category, a.failure_message, c.bep_id, a.bep_event_id";

    private static final String FROM =
            " FROM actions a"
                    + " LEFT JOIN labels l ON l.id = a.label_id"
                    + " LEFT JOIN mnemonics m ON m.id = a.mnemonic_id"
                    + " LEFT JOIN configurations c ON c.id = a.configuration_id";

    private static final String SELECT_ONE = "SELECT " + COLUMNS + FROM + " WHERE a.id = ?";

    private static final String SELECT_BY_OUTPUT =
            "SELECT " + COLUMNS + FROM + " WHERE a.primary_output = ?";

    private static final String DISTINCT_MNEMONICS =
            "SELECT m.value, COUNT(*) FROM actions a"
                    + " JOIN mnemonics m ON m.id = a.mnemonic_id"
                    + " GROUP BY m.value ORDER BY COUNT(*) DESC, m.value ASC";

    private final Connection connection;
    private volatile Statement running;

    public ActionQueries(Connection connection) {
        this.connection = Objects.requireNonNull(connection, "connection");
    }

    /** Asks the in-flight query to stop, from another thread. */
    public void cancel() {
        Statement statement = running;
        if (statement == null) {
            return;
        }
        try {
            statement.cancel();
        } catch (SQLException ignored) {
            // Already finished, or the driver declines. Either way there is
            // nothing left to stop and nothing useful to tell the caller.
        }
    }

    /** How many actions the session holds, regardless of any filter. */
    public long totalCount() throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM actions")) {
            return scalar(statement);
        }
    }

    /** How many actions match {@code filter}. */
    public long count(ActionFilter filter) throws SQLException {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*)").append(FROM).append(" WHERE 1=1");
        appendFilter(sql, filter);
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            bindFilter(statement, 1, filter);
            return scalar(statement);
        }
    }

    /**
     * The first page under {@code sort}.
     *
     * @param descending reverse the ordering; the same rows in the same
     *     sequence, read from the other end
     */
    public List<ActionRow> firstPage(ActionFilter filter, ActionSort sort, boolean descending, int limit)
            throws SQLException {
        return page(filter, sort, descending, limit, Optional.empty());
    }

    /**
     * The page after {@code anchor} under the same sort the anchor came from.
     *
     * <p>The anchor is a row, not an offset: it carries both its sort value and
     * its id, which is what lets the seek land exactly where the last page
     * ended even when many rows share a sort value.
     */
    public List<ActionRow> pageAfter(
            Anchor anchor, ActionFilter filter, ActionSort sort, boolean descending, int limit)
            throws SQLException {
        return page(filter, sort, descending, limit, Optional.of(anchor));
    }

    /** Convenience for walking forward from a row already on screen. */
    public List<ActionRow> pageAfter(
            ActionRow anchor, ActionFilter filter, ActionSort sort, boolean descending, int limit)
            throws SQLException {
        return pageAfter(Anchor.of(anchor, sort), filter, sort, descending, limit);
    }

    /**
     * The row a page can be sought from: its position in the sort, and its id.
     *
     * <p>Deliberately not a row index. An index would have to be turned back
     * into a position by counting, which is {@code OFFSET} wearing a different
     * name.
     */
    public record Anchor(Optional<Object> sortValue, long id) {

        public Anchor {
            Objects.requireNonNull(sortValue, "sortValue");
        }

        public static Anchor of(ActionRow row, ActionSort sort) {
            return new Anchor(sortValueOf(row, sort), row.id());
        }
    }

    /**
     * Anchors for every page boundary, plus the row count, from one ordered
     * scan.
     *
     * <p>This is what lets a table model jump to page 20,000 without walking
     * the 19,999 before it and without {@code OFFSET}. It reads only the sort
     * value and the id — not the rows — so the scan stays inside the index for
     * the sorts that have one.
     *
     * @param pageSize rows per page; an anchor is kept for the last row of each
     */
    public Index buildIndex(
            ActionFilter filter, ActionSort sort, boolean descending, int pageSize)
            throws SQLException {
        if (pageSize < 1) {
            throw new IllegalArgumentException("pageSize must be positive, got " + pageSize);
        }
        String column = sort.column();
        StringBuilder sql = new StringBuilder("SELECT ").append(column).append(", a.id")
                .append(FROM).append(" WHERE 1=1");
        appendFilter(sql, filter);
        sql.append(Keyset.orderBy(Keyset.Segment.FROM_START, column, "a.id", descending));

        List<Anchor> anchors = new ArrayList<>();
        long rowCount = 0;
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            running = statement;
            try {
                bindFilter(statement, 1, filter);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        rowCount++;
                        if (rowCount % pageSize == 0) {
                            Object value = rows.getObject(1);
                            anchors.add(new Anchor(
                                    rows.wasNull() ? Optional.empty() : Optional.ofNullable(value),
                                    rows.getLong(2)));
                        }
                    }
                }
            } finally {
                running = null;
            }
        }
        // The last anchor is only useful if a page follows it.
        if (!anchors.isEmpty() && rowCount % pageSize == 0) {
            anchors.removeLast();
        }
        return new Index(rowCount, anchors);
    }

    /**
     * A row count and the anchors that address every page of it.
     *
     * @param anchors the last row of page {@code i}, for {@code i} from 0; page
     *     0 needs none
     */
    public record Index(long rowCount, List<Anchor> anchors) {
        public Index {
            anchors = List.copyOf(anchors);
        }

        /** The anchor for {@code pageIndex}, empty for the first page. */
        public Optional<Anchor> anchorFor(long pageIndex) {
            if (pageIndex <= 0) {
                return Optional.empty();
            }
            int previous = (int) (pageIndex - 1);
            return previous < anchors.size() ? Optional.of(anchors.get(previous)) : Optional.empty();
        }
    }

    /**
     * One page, drawn from as many seekable segments as it takes to fill.
     *
     * <p>See {@link Keyset} for why a page is composed rather than expressed as
     * one predicate, and what the two obvious single-predicate forms cost.
     */
    private List<ActionRow> page(
            ActionFilter filter,
            ActionSort sort,
            boolean descending,
            int limit,
            Optional<Anchor> anchor)
            throws SQLException {
        List<Keyset.Segment> segments = segmentsFor(sort, descending, anchor);
        List<ActionRow> rows = new ArrayList<>(limit);
        for (Keyset.Segment segment : segments) {
            if (rows.size() >= limit) {
                break;
            }
            if (segment == Keyset.Segment.SAME_VALUE && sort.column().equals("a.id")) {
                // A row id's value group holds one row, the anchor itself.
                continue;
            }
            rows.addAll(query(
                    filter, sort, descending, limit - rows.size(), segment, anchor));
        }
        return rows;
    }

    /**
     * Which ranges a page can come from, in the order the sort visits them.
     *
     * <p>Ascending, SQLite puts the unknowns first, so an anchor with a value
     * has already passed them and an anchor without one has not. Descending,
     * the unknowns are the tail and every anchor with a value still has them
     * ahead of it. Naming the ranges rather than encoding the reasoning in a
     * predicate is what keeps each of them seekable.
     */
    private static List<Keyset.Segment> segmentsFor(
            ActionSort sort, boolean descending, Optional<Anchor> anchor) {
        if (anchor.isEmpty()) {
            return List.of(Keyset.Segment.FROM_START);
        }
        boolean anchorIsUnknown = anchor.get().sortValue().isEmpty() && sort.nullable();
        if (descending) {
            if (anchorIsUnknown) {
                return List.of(Keyset.Segment.NULL_SIDE_AFTER);
            }
            return sort.nullable()
                    ? List.of(Keyset.Segment.SAME_VALUE, Keyset.Segment.PAST_VALUE,
                            Keyset.Segment.NULL_SIDE)
                    : List.of(Keyset.Segment.SAME_VALUE, Keyset.Segment.PAST_VALUE);
        }
        if (anchorIsUnknown) {
            return List.of(Keyset.Segment.NULL_SIDE_AFTER, Keyset.Segment.VALUE_SIDE);
        }
        return List.of(Keyset.Segment.SAME_VALUE, Keyset.Segment.PAST_VALUE);
    }

    private List<ActionRow> query(
            ActionFilter filter,
            ActionSort sort,
            boolean descending,
            int limit,
            Keyset.Segment segment,
            Optional<Anchor> anchor)
            throws SQLException {
        String column = sort.column();
        StringBuilder sql = new StringBuilder("SELECT ").append(COLUMNS).append(FROM)
                .append(" WHERE 1=1");
        appendFilter(sql, filter);
        sql.append(Keyset.where(segment, column, "a.id", descending))
                .append(Keyset.orderBy(segment, column, "a.id", descending))
                .append(" LIMIT ?");

        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            running = statement;
            try {
                int index = bindFilter(statement, 1, filter);
                index = bindSegment(statement, index, segment, sort, anchor);
                statement.setInt(index, limit);
                return readRows(statement);
            } finally {
                running = null;
            }
        }
    }

    /** Binds whatever {@link Keyset#where} declared for this segment. */
    private static int bindSegment(
            PreparedStatement statement,
            int from,
            Keyset.Segment segment,
            ActionSort sort,
            Optional<Anchor> anchor)
            throws SQLException {
        int index = from;
        boolean columnIsId = sort.column().equals("a.id");
        switch (segment) {
            case SAME_VALUE -> {
                bindSortValue(statement, index++, anchor.orElseThrow().sortValue());
                statement.setLong(index++, anchor.orElseThrow().id());
            }
            case PAST_VALUE -> {
                if (columnIsId) {
                    statement.setLong(index++, anchor.orElseThrow().id());
                } else {
                    bindSortValue(statement, index++, anchor.orElseThrow().sortValue());
                }
            }
            case NULL_SIDE_AFTER -> statement.setLong(index++, anchor.orElseThrow().id());
            case FROM_START, VALUE_SIDE, NULL_SIDE -> { }
        }
        return index;
    }

    private static void bindSortValue(
            PreparedStatement statement, int index, Optional<Object> value) throws SQLException {
        if (value.isEmpty()) {
            statement.setNull(index, java.sql.Types.OTHER);
        } else if (value.get() instanceof Long number) {
            statement.setLong(index, number);
        } else if (value.get() instanceof Integer number) {
            statement.setInt(index, number);
        } else {
            statement.setString(index, value.get().toString());
        }
    }

    /** One action by row id. */
    public Optional<ActionRow> action(long id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(SELECT_ONE)) {
            statement.setLong(1, id);
            List<ActionRow> rows = readRows(statement);
            return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
        }
    }

    /** One action by its primary output, which is its identity. */
    public Optional<ActionRow> actionByOutput(String primaryOutput) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(SELECT_BY_OUTPUT)) {
            statement.setString(1, primaryOutput);
            List<ActionRow> rows = readRows(statement);
            return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
        }
    }

    /**
     * The mnemonics present, most common first, for the filter control.
     *
     * <p>Read from the actions actually stored rather than from
     * {@code mnemonic_metrics}: on Bazel 6.5 and 7.6 a mnemonic whose actions
     * were all cache hits is missing from Bazel's own breakdown, so a filter
     * built from that would offer choices that match nothing and omit ones that
     * match rows on screen.
     */
    public List<MnemonicCount> mnemonics() throws SQLException {
        List<MnemonicCount> counts = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(DISTINCT_MNEMONICS);
                ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                counts.add(new MnemonicCount(rows.getString(1), rows.getLong(2)));
            }
        }
        return counts;
    }

    /** A mnemonic and how many actions carry it. */
    public record MnemonicCount(String mnemonic, long actions) {}

    /** The anchor's value for the sort column, empty when that value is NULL. */
    private static Optional<Object> sortValueOf(ActionRow row, ActionSort sort) {
        return switch (sort) {
            case ARRIVAL -> Optional.of(row.id());
            case START_TIME -> box(row.startMicros());
            case DURATION -> box(row.durationMicros());
            case MNEMONIC -> row.mnemonic().map(value -> value);
            case LABEL -> row.label().map(value -> value);
            case OUTCOME -> Optional.of(row.outcome().name());
        };
    }

    private static Optional<Object> box(OptionalLong value) {
        return value.isPresent() ? Optional.of(value.getAsLong()) : Optional.empty();
    }

    private static void appendFilter(StringBuilder sql, ActionFilter filter) {
        if (filter.mnemonic().isPresent()) {
            sql.append(" AND m.value = ?");
        }
        if (filter.outcome().isPresent()) {
            sql.append(" AND a.outcome = ?");
        }
        if (filter.labelContains().isPresent()) {
            sql.append(" AND l.value LIKE ? ESCAPE '\\'");
        }
        if (filter.textContains().isPresent()) {
            sql.append(" AND a.primary_output LIKE ? ESCAPE '\\'");
        }
    }

    private static int bindFilter(PreparedStatement statement, int from, ActionFilter filter)
            throws SQLException {
        int index = from;
        if (filter.mnemonic().isPresent()) {
            statement.setString(index++, filter.mnemonic().get());
        }
        if (filter.outcome().isPresent()) {
            statement.setString(index++, filter.outcome().get().name());
        }
        if (filter.labelContains().isPresent()) {
            statement.setString(index++, contains(filter.labelContains().get()));
        }
        if (filter.textContains().isPresent()) {
            statement.setString(index++, contains(filter.textContains().get()));
        }
        return index;
    }

    /**
     * A LIKE pattern matching anything containing {@code text}.
     *
     * <p>The wildcards are escaped. A label can legitimately contain
     * {@code %} or {@code _}, and without this a user searching for
     * {@code foo_bar} would silently also match {@code fooXbar} — a filter that
     * shows more than it says it does.
     */
    private static String contains(String text) {
        String escaped = text
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return "%" + escaped + "%";
    }

    private static List<ActionRow> readRows(PreparedStatement statement) throws SQLException {
        List<ActionRow> rows = new ArrayList<>();
        try (ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                rows.add(readRow(result));
            }
        }
        return rows;
    }

    private static ActionRow readRow(ResultSet result) throws SQLException {
        return new ActionRow(
                result.getLong(1),
                result.getString(2),
                text(result, 3),
                text(result, 4),
                outcomeOf(result.getString(5)),
                number(result, 6),
                number(result, 7),
                text(result, 8),
                integer(result, 9),
                integer(result, 10),
                text(result, 11),
                text(result, 12),
                text(result, 13),
                number(result, 14));
    }

    /**
     * The stored outcome name.
     *
     * <p>A value this build does not recognise becomes {@link
     * ActionOutcome#RECEIVED} — "an event named this action and did not say how
     * it ended" — rather than a failure. A newer schema's extra state must not
     * make a successful build look broken.
     */
    private static ActionOutcome outcomeOf(String stored) {
        for (ActionOutcome outcome : ActionOutcome.values()) {
            if (outcome.name().equals(stored)) {
                return outcome;
            }
        }
        return ActionOutcome.RECEIVED;
    }

    private static Optional<String> text(ResultSet result, int index) throws SQLException {
        String value = result.getString(index);
        return result.wasNull() || value == null ? Optional.empty() : Optional.of(value);
    }

    private static OptionalLong number(ResultSet result, int index) throws SQLException {
        long value = result.getLong(index);
        return result.wasNull() ? OptionalLong.empty() : OptionalLong.of(value);
    }

    private static OptionalInt integer(ResultSet result, int index) throws SQLException {
        int value = result.getInt(index);
        return result.wasNull() ? OptionalInt.empty() : OptionalInt.of(value);
    }

    private static long scalar(PreparedStatement statement) throws SQLException {
        try (ResultSet rows = statement.executeQuery()) {
            return rows.next() ? rows.getLong(1) : 0L;
        }
    }

    @Override
    public void close() throws SQLException {
        connection.close();
    }
}
