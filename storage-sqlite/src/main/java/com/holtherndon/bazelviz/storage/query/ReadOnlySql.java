package com.holtherndon.bazelviz.storage.query;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The application-layer half of "this connection cannot write": it decides
 * whether the text a user typed is <em>one</em> read-only statement before any
 * of it reaches SQLite.
 *
 * <h2>Why this exists when the connection is already read-only</h2>
 *
 * <p>{@code AdHocQueries} runs on a connection opened with SQLite's
 * {@code SQLITE_OPEN_READONLY} flag and with {@code PRAGMA query_only = ON}
 * (see {@code SessionDatabase.newQueryConnection}). Either of those alone
 * refuses a write. Neither of them refuses a <em>second statement</em>:
 * {@code Statement.execute} on a semicolon-joined string happily runs
 * everything in it, and a read-only connection can still be told to
 * {@code ATTACH} another database or to run an {@code integrity_check} over a
 * hundred gigabytes. Rule 15/22.4 asks for the connection to be incapable
 * rather than trusted, and this is the layer that decides <em>what shape of
 * request is even sensible</em> — one statement, one result grid, one
 * cancellable execution.
 *
 * <h2>What is allowed, and why</h2>
 *
 * <ul>
 *   <li>{@code SELECT}, {@code WITH …} and {@code VALUES} — the reason the
 *       feature exists. These are <b>tabular</b>: they can be wrapped in
 *       {@code SELECT … FROM (…)}, which is how the result grid counts and
 *       pages them without materializing anything.
 *   <li>{@code EXPLAIN} / {@code EXPLAIN QUERY PLAN} — the question "why is
 *       this query slow" is most of the reason to have a query surface over a
 *       build at all, and the answer is unreachable without it.
 *   <li>{@code PRAGMA}, restricted to the introspection list in
 *       {@link #INTROSPECTION_PRAGMAS}. The schema browser reads
 *       {@code table_info} at runtime because this codebase has 59 tables
 *       across five schema versions and no generated schema page; a user who
 *       wants the same answer for a table the browser is not showing should be
 *       able to ask for it directly.
 * </ul>
 *
 * <p>{@code EXPLAIN} and {@code PRAGMA} are <b>direct</b>: they cannot be
 * wrapped in a subquery, so their rows are read once and held. That is safe
 * because their size is a property of the schema or of the query text, never of
 * the build — {@code PRAGMA table_info} returns one row per column and
 * {@code EXPLAIN} one row per VM instruction.
 *
 * <h2>Pragmas that are read-only but excluded anyway</h2>
 *
 * <p>{@code user_version}, {@code schema_version}, {@code page_size} and
 * friends read harmlessly, but every one of them also has a <em>setter</em>
 * spelling — and not only the obvious {@code PRAGMA user_version = 5}: SQLite
 * accepts {@code PRAGMA user_version(5)} too, which no "reject an equals sign"
 * check would catch. Rather than curate which spellings of which pragma are
 * safe, the allowlist holds only pragmas that have no setter form at all.
 *
 * <h2>How the text is read</h2>
 *
 * <p>Comments, string literals and quoted identifiers are blanked into a
 * same-length skeleton first, so a {@code ;} inside {@code '…'} does not look
 * like a statement boundary and the word {@code delete} inside a search string
 * does not look like a {@code DELETE}. The scan then runs on the skeleton and
 * every offset still refers to the original text.
 *
 * <p>A leading keyword is not by itself proof of a read: SQLite accepts
 * {@code WITH c AS (…) DELETE FROM t}, so the skeleton is also scanned for
 * writing keywords anywhere in the statement.
 */
public final class ReadOnlySql {

    /** How the statement's rows can be reached. */
    public enum Shape {
        /**
         * Wrappable in {@code SELECT … FROM (…)}: counted and paged in SQL,
         * never materialized.
         */
        TABULAR,
        /**
         * Not wrappable. Executed once and its rows held, which is bounded
         * because these statements describe the schema or the query, not the
         * build.
         */
        DIRECT
    }

    /** One validated statement. */
    public record Statement(String sql, Shape shape) { }

    private static final Set<String> TABULAR_STARTS = Set.of("select", "with", "values");

    private static final Set<String> DIRECT_STARTS = Set.of("explain", "pragma");

    /**
     * Pragmas that only report. None of these has a setter spelling, which is
     * the property that lets them be allowed by name alone.
     */
    static final Set<String> INTROSPECTION_PRAGMAS = Set.of(
            "table_info",
            "table_xinfo",
            "table_list",
            "index_list",
            "index_info",
            "index_xinfo",
            "foreign_key_list",
            "database_list",
            "collation_list",
            "compile_options",
            "function_list",
            "module_list",
            "pragma_list",
            "page_count",
            "freelist_count",
            "integrity_check",
            "quick_check",
            "foreign_key_check");

    /**
     * Words that mean the statement changes something, wherever they appear.
     *
     * <p>{@code replace} is absent on purpose: {@code replace(x, 'a', 'b')} is
     * an ordinary scalar function and banning the word would break real
     * queries. {@code REPLACE INTO} is caught by the two-word rule below
     * instead.
     */
    private static final List<String> WRITING_WORDS = List.of(
            "insert", "update", "delete", "drop", "alter", "attach", "detach",
            "vacuum", "reindex", "analyze", "create", "trigger",
            "begin", "commit", "rollback", "savepoint", "release");

    private ReadOnlySql() {
    }

    /**
     * Checks {@code submitted} and returns the single statement it contains.
     *
     * @throws SqlNotAllowedException if it is empty, is more than one
     *     statement, or is not one of the allowed read-only forms
     */
    public static Statement check(String submitted) {
        if (submitted == null || submitted.isBlank()) {
            throw new SqlNotAllowedException(submitted == null ? "" : submitted,
                    "There is no statement to run.");
        }
        String skeleton = skeleton(submitted);

        List<int[]> segments = topLevelSegments(skeleton);
        if (segments.isEmpty()) {
            throw new SqlNotAllowedException(submitted,
                    "That is only a comment; there is no statement to run.");
        }
        if (segments.size() > 1) {
            // query_only would not have stopped this: Statement.execute runs
            // every statement in a semicolon-joined string, and only the first
            // one's rows would ever have reached the grid.
            throw new SqlNotAllowedException(submitted,
                    "Run one statement at a time. This text holds " + segments.size()
                            + ", starting with \""
                            + firstWords(submitted, segments.get(0)) + "\" and \""
                            + firstWords(submitted, segments.get(1)) + "\".");
        }

        int[] only = segments.get(0);
        String statement = submitted.substring(only[0], only[1]).strip();
        String bones = skeleton.substring(only[0], only[1]);
        String lead = firstWord(bones);

        Shape shape;
        if (TABULAR_STARTS.contains(lead)) {
            shape = Shape.TABULAR;
        } else if (DIRECT_STARTS.contains(lead)) {
            shape = Shape.DIRECT;
        } else {
            throw new SqlNotAllowedException(submitted,
                    "Only read-only statements run here, and \""
                            + (lead.isEmpty() ? statement : lead.toUpperCase(Locale.ROOT))
                            + "\" is not one. Allowed: SELECT, WITH, VALUES, EXPLAIN, and the"
                            + " introspection PRAGMAs (table_info, index_list, …).");
        }

        rejectWritingWords(submitted, bones);
        if ("pragma".equals(lead)) {
            rejectNonIntrospectionPragma(submitted, bones);
        }
        return new Statement(statement, shape);
    }

    private static void rejectWritingWords(String submitted, String bones) {
        String lower = bones.toLowerCase(Locale.ROOT);
        for (String word : WRITING_WORDS) {
            int at = wordIndex(lower, word, 0);
            if (at >= 0) {
                throw new SqlNotAllowedException(submitted,
                        "\"" + word.toUpperCase(Locale.ROOT) + "\" changes the session database,"
                                + " and this connection is opened read-only. Remove it. (If that"
                                + " is a column name, quote it: \"" + word + "\".)");
            }
        }
        // REPLACE is a scalar function and a statement. Only the statement
        // spelling — REPLACE INTO — is refused.
        int replace = wordIndex(lower, "replace", 0);
        while (replace >= 0) {
            String rest = lower.substring(replace + "replace".length()).stripLeading();
            if (rest.startsWith("into")) {
                throw new SqlNotAllowedException(submitted,
                        "\"REPLACE INTO\" writes rows, and this connection is opened read-only.");
            }
            replace = wordIndex(lower, "replace", replace + 1);
        }
    }

    private static void rejectNonIntrospectionPragma(String submitted, String bones) {
        String rest = bones.strip().substring("pragma".length()).stripLeading();
        // PRAGMA main.table_info(x) is legal; the schema qualifier is not the
        // pragma's name.
        int dot = rest.indexOf('.');
        int paren = rest.indexOf('(');
        int equals = rest.indexOf('=');
        if (dot >= 0 && (paren < 0 || dot < paren) && (equals < 0 || dot < equals)) {
            rest = rest.substring(dot + 1).stripLeading();
        }
        String name = firstWord(rest).toLowerCase(Locale.ROOT);
        if (!INTROSPECTION_PRAGMAS.contains(name)) {
            throw new SqlNotAllowedException(submitted,
                    "PRAGMA " + (name.isEmpty() ? "(nothing)" : name)
                            + " is not one of the introspection pragmas this view runs. Allowed: "
                            + String.join(", ", INTROSPECTION_PRAGMAS.stream().sorted().toList())
                            + ". Every other pragma either changes state or has a spelling that"
                            + " does.");
        }
    }

    /**
     * A same-length copy of {@code sql} with comments, string literals and
     * quoted identifiers replaced by spaces.
     *
     * <p>Same length on purpose: every index into the skeleton is also an index
     * into the original, so the statement text handed back is the user's own
     * and not a reconstruction.
     */
    static String skeleton(String sql) {
        char[] out = sql.toCharArray();
        int i = 0;
        int n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                while (i < n && sql.charAt(i) != '\n') {
                    out[i++] = ' ';
                }
            } else if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                out[i++] = ' ';
                out[i++] = ' ';
                while (i < n && !(sql.charAt(i) == '*' && i + 1 < n && sql.charAt(i + 1) == '/')) {
                    out[i++] = ' ';
                }
                if (i < n) {
                    out[i++] = ' ';
                    if (i < n) {
                        out[i++] = ' ';
                    }
                }
            } else if (c == '\'' || c == '"' || c == '`') {
                i = blankQuoted(sql, out, i, c);
            } else if (c == '[') {
                out[i++] = ' ';
                while (i < n && sql.charAt(i) != ']') {
                    out[i++] = ' ';
                }
                if (i < n) {
                    out[i++] = ' ';
                }
            } else {
                i++;
            }
        }
        return new String(out);
    }

    private static int blankQuoted(String sql, char[] out, int start, char quote) {
        int n = sql.length();
        int i = start;
        out[i++] = ' ';
        while (i < n) {
            if (sql.charAt(i) == quote) {
                out[i++] = ' ';
                // A doubled quote is an escaped quote, not the end.
                if (i < n && sql.charAt(i) == quote) {
                    out[i++] = ' ';
                    continue;
                }
                return i;
            }
            out[i++] = ' ';
        }
        // Unterminated. Everything after the opening quote is already blank, so
        // nothing can hide in it; SQLite would refuse the text anyway, but the
        // message it gives ("unrecognized token") is worse than this one.
        throw new SqlNotAllowedException(sql,
                "The " + describeQuote(quote) + " opened at character " + (start + 1)
                        + " is never closed.");
    }

    private static String describeQuote(char quote) {
        return switch (quote) {
            case '\'' -> "string literal";
            case '"' -> "quoted identifier";
            default -> "backquoted identifier";
        };
    }

    /** Start/end offsets of each non-empty top-level statement. */
    private static List<int[]> topLevelSegments(String skeleton) {
        List<int[]> segments = new java.util.ArrayList<>(2);
        int start = 0;
        for (int i = 0; i < skeleton.length(); i++) {
            if (skeleton.charAt(i) == ';') {
                addIfSubstantial(segments, skeleton, start, i);
                start = i + 1;
            }
        }
        addIfSubstantial(segments, skeleton, start, skeleton.length());
        return segments;
    }

    private static void addIfSubstantial(List<int[]> segments, String skeleton, int from, int to) {
        if (!skeleton.substring(from, to).isBlank()) {
            segments.add(new int[] {from, to});
        }
    }

    private static String firstWord(String text) {
        int i = 0;
        while (i < text.length() && !Character.isLetter(text.charAt(i))) {
            i++;
        }
        int start = i;
        while (i < text.length()
                && (Character.isLetterOrDigit(text.charAt(i)) || text.charAt(i) == '_')) {
            i++;
        }
        return text.substring(start, i).toLowerCase(Locale.ROOT);
    }

    private static String firstWords(String submitted, int[] segment) {
        String text = submitted.substring(segment[0], segment[1]).strip().replaceAll("\\s+", " ");
        return text.length() <= 40 ? text : text.substring(0, 40) + "…";
    }

    /** Index of {@code word} in {@code lower} as a whole identifier token. */
    private static int wordIndex(String lower, String word, int from) {
        int at = lower.indexOf(word, from);
        while (at >= 0) {
            boolean beforeOk = at == 0 || !isWordChar(lower.charAt(at - 1));
            int after = at + word.length();
            boolean afterOk = after >= lower.length() || !isWordChar(lower.charAt(after));
            if (beforeOk && afterOk) {
                return at;
            }
            at = lower.indexOf(word, at + 1);
        }
        return -1;
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }
}
