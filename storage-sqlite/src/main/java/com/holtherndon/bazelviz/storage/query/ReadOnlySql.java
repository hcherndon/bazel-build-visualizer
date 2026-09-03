package com.holtherndon.bazelviz.storage.query;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The application-layer half of "this connection cannot write": it decides whether the text a user
 * typed is <em>one</em> read-only statement before any of it reaches SQLite.
 *
 * <h2>What is guaranteed, and what this layer is for</h2>
 *
 * <p>Exactly one thing is guaranteed: the connection cannot write to the database file, because it
 * was opened with SQLite's {@code SQLITE_OPEN_READONLY} flag and that flag is not reachable from
 * SQL. No statement typed into the query card can change a byte of the session.
 *
 * <p>Everything else is defence in depth around that one guarantee, and is defeasible on
 * purpose-built input. {@code PRAGMA query_only = ON} is <em>connection state</em>, and connection
 * state <em>is</em> reachable from SQL: {@code EXPLAIN PRAGMA query_only = OFF} clears it, because
 * SQLite applies flag pragmas in {@code sqlite3Pragma()} at <b>prepare</b> time and {@code EXPLAIN}
 * does not suppress that. This class is what keeps {@code query_only} in place, which is why {@link
 * #check} treats {@code EXPLAIN} as <em>transparent</em> — it classifies whatever the {@code
 * EXPLAIN} was put in front of — and why {@code AdHocQueries} re-reads {@code query_only} before
 * every execution rather than trusting the check it made once at construction.
 *
 * <p>Two more things only this layer can refuse. A <em>second statement</em>: {@code
 * Statement.execute} on a semicolon-joined string runs everything in it, and only the first
 * statement's rows would ever reach the grid. And a request whose <em>shape</em> makes no sense
 * here — an {@code ATTACH} of somebody else's database, or an {@code integrity_check} over a
 * hundred gigabytes. Rule 15/22.4 asks for the connection to be incapable rather than trusted; the
 * open mode delivers that for writes, and this decides what shape of request is even sensible.
 *
 * <h2>What is allowed, and why</h2>
 *
 * <ul>
 *   <li>{@code SELECT}, {@code WITH …} and {@code VALUES} — the reason the feature exists. These
 *       are <b>tabular</b>: they can be wrapped in {@code SELECT … FROM (…)}, which is how the
 *       result grid counts and pages them without materializing anything.
 *   <li>{@code EXPLAIN} / {@code EXPLAIN QUERY PLAN} — the question "why is this query slow" is
 *       most of the reason to have a query surface over a build at all, and the answer is
 *       unreachable without it. It is a <em>prefix</em>, never a statement of its own: the words
 *       after it are checked by exactly the rules that would apply without it, repeatedly, so
 *       {@code EXPLAIN EXPLAIN PRAGMA …} is checked too.
 *   <li>{@code PRAGMA}, restricted to the introspection list in {@link #INTROSPECTION_PRAGMAS}. The
 *       schema browser reads {@code table_info} at runtime because this codebase has many tables
 *       across evolving schema versions and no generated schema page; a user who wants the same
 *       answer for a table the browser is not showing should be able to ask for it directly.
 *   <li>{@code CREATE TEMP VIEW <name> AS <tabular>} — the one admitted non-read, because a temp
 *       view lives in the connection's own temp schema: per-connection, gone at close, incapable of
 *       holding rows, and creatable even though the main database is opened {@code
 *       SQLITE_OPEN_READONLY}. Every other {@code CREATE} — TABLE, non-temp VIEW, INDEX, TRIGGER,
 *       VIRTUAL TABLE, and the TEMP spellings of TABLE and TRIGGER — is refused by name in {@link
 *       #checkTempViewDefinition}.
 * </ul>
 *
 * <p>An {@code EXPLAIN} of anything, and a {@code PRAGMA}, are <b>direct</b>: they cannot be
 * wrapped in a subquery, so their rows are read once and held. That is safe because their size is a
 * property of the schema or of the query text, never of the build — {@code PRAGMA table_info}
 * returns one row per column and {@code EXPLAIN} one row per VM instruction.
 *
 * <h2>Pragmas that are read-only but excluded anyway</h2>
 *
 * <p>{@code user_version}, {@code schema_version}, {@code page_size} and friends read harmlessly,
 * but every one of them also has a <em>setter</em> spelling — and not only the obvious {@code
 * PRAGMA user_version = 5}: SQLite accepts {@code PRAGMA user_version(5)} too, which no "reject an
 * equals sign" check would catch. Rather than curate which spellings of which pragma are safe, the
 * allowlist holds only pragmas that have no setter form at all.
 *
 * <p>This list is load-bearing well beyond tidiness, and {@code soft_heap_limit} is why. {@code
 * sqlite3_soft_heap_limit64} is <b>process-global</b>: one {@code PRAGMA soft_heap_limit = 1} typed
 * into a text box would degrade every SQLite connection in the JVM, including the writer ingesting
 * a build — a cross-connection effect that neither the row cap nor the query deadline touches. That
 * is the shape of damage this allowlist exists to prevent, and the reason {@code EXPLAIN} must not
 * be allowed to route around it.
 *
 * <h2>How the text is read</h2>
 *
 * <p>Comments, string literals and quoted identifiers are blanked into a same-length skeleton
 * first, so a {@code ;} inside {@code '…'} does not look like a statement boundary and the word
 * {@code delete} inside a search string does not look like a {@code DELETE}. The scan then runs on
 * the skeleton and every offset still refers to the original text.
 *
 * <p>A leading keyword is not by itself proof of a read: SQLite accepts {@code WITH c AS (…) DELETE
 * FROM t}, so the skeleton is also scanned for writing keywords anywhere in the statement.
 */
public final class ReadOnlySql {

  /** How the statement's rows can be reached. */
  public enum Shape {
    /** Wrappable in {@code SELECT … FROM (…)}: counted and paged in SQL, never materialized. */
    TABULAR,
    /**
     * Not wrappable. Executed once and its rows held, which is bounded because these statements
     * describe the schema or the query, not the build.
     */
    DIRECT,
    /**
     * {@code CREATE TEMP VIEW <name> AS <tabular>} — the one statement this layer admits that is
     * not a read. It writes only the connection's own temp schema, which is per-connection and
     * never the session file: the main database stays opened {@code SQLITE_OPEN_READONLY} and a
     * non-temp {@code CREATE} of any kind is still refused here. Returns no rows; {@code
     * AdHocQueries} executes it and reports the definition.
     */
    DEFINE
  }

  /**
   * One validated statement.
   *
   * @param sql the statement, exactly as the user wrote it
   * @param shape how its rows can be reached
   * @param tempViewName for {@link Shape#DEFINE} only, the view's name with any quoting removed;
   *     {@code null} otherwise
   * @param tempViewSelect for {@link Shape#DEFINE} only, the tabular body after {@code AS}; {@code
   *     null} otherwise
   */
  public record Statement(String sql, Shape shape, String tempViewName, String tempViewSelect) {

    public Statement(String sql, Shape shape) {
      this(sql, shape, null, null);
    }
  }

  private static final Set<String> TABULAR_STARTS = Set.of("select", "with", "values");

  /**
   * Pragmas that only report. None of these has a setter spelling, which is the property that lets
   * them be allowed by name alone.
   */
  static final Set<String> INTROSPECTION_PRAGMAS =
      Set.of(
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
   * <p>{@code replace} is absent on purpose: {@code replace(x, 'a', 'b')} is an ordinary scalar
   * function and banning the word would break real queries. {@code REPLACE INTO} is caught by the
   * two-word rule below instead.
   *
   * <p>{@code create} is here even though {@code CREATE TEMP VIEW} is admitted: a statement whose
   * <em>first</em> word is {@code CREATE} takes the {@link #checkTempViewDefinition} path before
   * this scan runs, and that path re-runs the scan over the view's body — so {@code create}
   * anywhere it could actually do harm (inside a SELECT, after an EXPLAIN, in a view body) is still
   * refused.
   */
  private static final List<String> WRITING_WORDS =
      List.of(
          "insert",
          "update",
          "delete",
          "drop",
          "alter",
          "attach",
          "detach",
          "vacuum",
          "reindex",
          "analyze",
          "create",
          "trigger",
          "begin",
          "commit",
          "rollback",
          "savepoint",
          "release");

  private ReadOnlySql() {}

  /**
   * Checks {@code submitted} and returns the single statement it contains.
   *
   * @throws SqlNotAllowedException if it is empty, is more than one statement, or is not one of the
   *     allowed read-only forms
   */
  public static Statement check(String submitted) {
    if (submitted == null || submitted.isBlank()) {
      throw new SqlNotAllowedException(
          submitted == null ? "" : submitted, "There is no statement to run.");
    }
    String skeleton = skeleton(submitted);

    List<int[]> segments = topLevelSegments(skeleton);
    if (segments.isEmpty()) {
      throw new SqlNotAllowedException(
          submitted, "That is only a comment; there is no statement to run.");
    }
    if (segments.size() > 1) {
      // query_only would not have stopped this: Statement.execute runs
      // every statement in a semicolon-joined string, and only the first
      // one's rows would ever have reached the grid.
      throw new SqlNotAllowedException(
          submitted,
          "Run one statement at a time. This text holds "
              + segments.size()
              + ", starting with \""
              + firstWords(submitted, segments.get(0))
              + "\" and \""
              + firstWords(submitted, segments.get(1))
              + "\".");
    }

    int[] only = segments.get(0);
    String statement = submitted.substring(only[0], only[1]).strip();
    String bones = skeleton.substring(only[0], only[1]);
    String original = submitted.substring(only[0], only[1]);

    // CREATE first, because the writing-word scan below bans the word
    // outright and the one CREATE this view admits needs its own parse.
    if ("create".equals(firstWord(bones))) {
      return checkTempViewDefinition(submitted, statement, bones, original);
    }

    rejectWritingWords(submitted, bones);

    // EXPLAIN is transparent, not terminal. It prefixes another statement
    // and does not suppress it -- see the class javadoc -- so what is
    // classified is whatever EXPLAIN was put in front of.
    int at = 0;
    String lead = wordAt(bones, at);
    boolean explained = false;
    while ("explain".equals(lead)) {
      explained = true;
      at = afterExplainKeyword(bones, at);
      lead = wordAt(bones, at);
    }

    if ("pragma".equals(lead)) {
      rejectNonIntrospectionPragma(submitted, bones, original, afterWordAt(bones, at));
      return new Statement(statement, Shape.DIRECT);
    }
    if (TABULAR_STARTS.contains(lead)) {
      // An explained SELECT produces the plan, not the rows, and a plan
      // cannot be wrapped in SELECT * FROM (...).
      return new Statement(statement, explained ? Shape.DIRECT : Shape.TABULAR);
    }
    throw new SqlNotAllowedException(
        submitted,
        explained
            ? "EXPLAIN only reports how a statement would run, so what follows it is"
                + " checked as if it were the statement -- and \""
                + (lead.isEmpty() ? "nothing at all" : lead.toUpperCase(Locale.ROOT))
                + "\" is not one this view runs. Allowed after EXPLAIN: SELECT, WITH,"
                + " VALUES, and the introspection PRAGMAs (table_info, index_list, …)."
            : "Only read-only statements run here, and \""
                + (lead.isEmpty() ? statement : lead.toUpperCase(Locale.ROOT))
                + "\" is not one. Allowed: SELECT, WITH, VALUES, EXPLAIN,"
                + " CREATE TEMP VIEW, and the introspection PRAGMAs (table_info,"
                + " index_list, …).");
  }

  // ------------------------------------------------------- CREATE TEMP VIEW

  /**
   * Admits exactly {@code CREATE TEMP VIEW <name> AS <tabular>} (or {@code TEMPORARY}), and refuses
   * every other {@code CREATE} by name.
   *
   * <p>The narrowness is the point. A temp view is a named SELECT in the connection's own temp
   * schema: per-connection, gone at close, incapable of holding data, and creatable even though the
   * main database is opened {@code SQLITE_OPEN_READONLY} — the temp schema is a different database.
   * Everything else CREATE can make is refused with the reason: a plain TABLE, VIEW, INDEX, TRIGGER
   * or VIRTUAL TABLE writes the session file (and the open mode would refuse it anyway), and a TEMP
   * TABLE or TEMP TRIGGER can hold data or run statements, which a view cannot.
   *
   * <p>The head is parsed token by token — {@code CREATE}, the temp word, {@code VIEW}, one name,
   * {@code AS} — and the body after {@code AS} is then checked by the same rules as a standalone
   * statement: the writing-word scan runs over it and it must classify as tabular. Nothing can hide
   * between the fixed tokens, because anything unexpected there is a refusal rather than a skip.
   */
  private static Statement checkTempViewDefinition(
      String submitted, String statement, String bones, String original) {
    int at = afterWordAt(bones, 0);
    String second = wordAt(bones, at);
    if (!"temp".equals(second) && !"temporary".equals(second)) {
      throw new SqlNotAllowedException(
          submitted,
          "CREATE "
              + (second.isEmpty() ? "(nothing)" : second.toUpperCase(Locale.ROOT))
              + " would write the session database, and this connection is opened"
              + " read-only. The one CREATE this view runs is CREATE TEMP VIEW"
              + " <name> AS SELECT …, which lives in this connection's temp schema"
              + " and touches no file.");
    }
    at = afterWordAt(bones, at);
    String third = wordAt(bones, at);
    if (!"view".equals(third)) {
      throw new SqlNotAllowedException(
          submitted,
          "CREATE TEMP "
              + (third.isEmpty() ? "(nothing)" : third.toUpperCase(Locale.ROOT))
              + " is not run here. Only a TEMP VIEW is: a view is a named SELECT"
              + " and cannot hold rows or run statements, which is what keeps the"
              + " temp schema harmless. A temp table or temp trigger can.");
    }
    at = afterWordAt(bones, at);
    if ("if".equals(wordAt(bones, at))) {
      int afterIf = afterWordAt(bones, at);
      if ("not".equals(wordAt(bones, afterIf))
          && "exists".equals(wordAt(bones, afterWordAt(bones, afterIf)))) {
        throw new SqlNotAllowedException(
            submitted,
            "IF NOT EXISTS is not accepted: redefining is what running a"
                + " CREATE TEMP VIEW again does here, so \"already"
                + " exists\" is never the situation.");
      }
    }
    int nameStart = skipBlanked(bones, original, at);
    ParsedName name = parseViewName(submitted, original, nameStart);
    if (name.name().isBlank()) {
      throw new SqlNotAllowedException(
          submitted, "The view has no name. Write CREATE TEMP VIEW <name> AS SELECT ….");
    }
    int afterName = skipBlanked(bones, original, name.end());
    if (afterName < original.length() && original.charAt(afterName) == '.') {
      throw new SqlNotAllowedException(
          submitted,
          "Name the view without a schema qualifier — a temp view always"
              + " lives in this connection's temp schema, so \""
              + name.name()
              + ".\" names a schema, not a view.");
    }
    if (afterName < original.length() && original.charAt(afterName) == '(') {
      throw new SqlNotAllowedException(
          submitted,
          "A column list after the view name is not accepted; name the"
              + " columns in the SELECT itself, with AS.");
    }
    if (!"as".equals(wordAt(bones, afterName))) {
      throw new SqlNotAllowedException(
          submitted, "Expected AS after the view name: CREATE TEMP VIEW <name> AS" + " SELECT ….");
    }
    int bodyStart = afterWordAt(bones, afterName);
    String bodyBones = bones.substring(bodyStart);
    if (bodyBones.isBlank()) {
      throw new SqlNotAllowedException(
          submitted, "The view has no body. Write CREATE TEMP VIEW <name> AS SELECT ….");
    }
    rejectWritingWords(submitted, bodyBones);
    String bodyLead = firstWord(bodyBones);
    if (!TABULAR_STARTS.contains(bodyLead)) {
      throw new SqlNotAllowedException(
          submitted,
          "A temp view's body must be a SELECT, WITH or VALUES, and \""
              + (bodyLead.isEmpty() ? "this" : bodyLead.toUpperCase(Locale.ROOT))
              + "\" is none of them.");
    }
    return new Statement(
        statement, Shape.DEFINE, name.name(), original.substring(bodyStart).strip());
  }

  /** A parsed identifier and the index just past it. */
  private record ParsedName(String name, int end) {}

  /**
   * The view's name at {@code i} in {@code original}: a bare identifier, or one quoted with {@code
   * "…"}, {@code `…`} or {@code […]} — unquoted in the result, so callers re-quote it themselves
   * and a name SQLite would accept in any spelling round-trips.
   */
  private static ParsedName parseViewName(String submitted, String original, int i) {
    int n = original.length();
    if (i >= n) {
      throw new SqlNotAllowedException(
          submitted, "The view has no name. Write CREATE TEMP VIEW <name> AS SELECT ….");
    }
    char c = original.charAt(i);
    if (c == '"' || c == '`') {
      return parseQuotedName(original, i, c);
    }
    if (c == '[') {
      int close = original.indexOf(']', i + 1);
      // An unclosed bracket cannot reach here: skeleton() blanked it to
      // the end of the text, so AS was never found and the check above
      // already refused. Guarded anyway.
      if (close < 0) {
        throw new SqlNotAllowedException(
            submitted, "The [ opened at character " + (i + 1) + " is never closed.");
      }
      return new ParsedName(original.substring(i + 1, close), close + 1);
    }
    if (c == '\'') {
      throw new SqlNotAllowedException(
          submitted,
          "A view name is an identifier, not a string literal. Quote it"
              + " with double quotes if it needs quoting.");
    }
    if (Character.isLetter(c) || c == '_') {
      int end = i;
      while (end < n && isWordChar(original.charAt(end))) {
        end++;
      }
      return new ParsedName(original.substring(i, end), end);
    }
    throw new SqlNotAllowedException(
        submitted, "Expected a view name after CREATE TEMP VIEW, not \"" + c + "\".");
  }

  private static ParsedName parseQuotedName(String original, int start, char quote) {
    StringBuilder name = new StringBuilder();
    int i = start + 1;
    int n = original.length();
    while (i < n) {
      char c = original.charAt(i);
      if (c == quote) {
        if (i + 1 < n && original.charAt(i + 1) == quote) {
          name.append(quote);
          i += 2;
          continue;
        }
        return new ParsedName(name.toString(), i + 1);
      }
      name.append(c);
      i++;
    }
    // Unreachable in practice: skeleton() refuses unterminated quotes
    // before any of this parsing runs.
    throw new SqlNotAllowedException(
        original, "The quote opened at character " + (start + 1) + " is never closed.");
  }

  /**
   * Advances {@code from} past positions that are whitespace in the skeleton — real whitespace and
   * blanked comments — stopping early when the original text holds a quote or bracket there,
   * because a blanked quote is content (a quoted identifier) and not space.
   */
  private static int skipBlanked(String bones, String original, int from) {
    while (from < bones.length()
        && Character.isWhitespace(bones.charAt(from))
        && !isQuoteStart(original.charAt(from))) {
      from++;
    }
    return from;
  }

  private static boolean isQuoteStart(char c) {
    return c == '"' || c == '\'' || c == '`' || c == '[';
  }

  // ------------------------------------------------------------- word walking

  /** Index of the first letter at or after {@code from}. */
  private static int letterStart(String text, int from) {
    while (from < text.length() && !Character.isLetter(text.charAt(from))) {
      from++;
    }
    return from;
  }

  /** The identifier word starting at or after {@code from}, lower-cased. */
  private static String wordAt(String text, int from) {
    int start = letterStart(text, from);
    int end = start;
    while (end < text.length() && isIdentifierChar(text.charAt(end))) {
      end++;
    }
    return text.substring(start, end).toLowerCase(Locale.ROOT);
  }

  /** Index just past the identifier word starting at or after {@code from}. */
  private static int afterWordAt(String text, int from) {
    int start = letterStart(text, from);
    while (start < text.length() && isIdentifierChar(text.charAt(start))) {
      start++;
    }
    return start;
  }

  private static boolean isIdentifierChar(char c) {
    return Character.isLetterOrDigit(c) || c == '_';
  }

  /**
   * Index just past a leading {@code EXPLAIN} or {@code EXPLAIN QUERY PLAN} at {@code at}.
   *
   * <p>Both spellings, because a rule that only knew the short one would let {@code EXPLAIN QUERY
   * PLAN PRAGMA query_only = OFF} through. {@code QUERY} is consumed only when {@code PLAN} follows
   * it; anything else is left in place and fails the classification, which is the safe direction.
   */
  private static int afterExplainKeyword(String bones, int at) {
    int rest = afterWordAt(bones, at);
    if ("query".equals(wordAt(bones, rest))) {
      int afterQuery = afterWordAt(bones, rest);
      if ("plan".equals(wordAt(bones, afterQuery))) {
        return afterWordAt(bones, afterQuery);
      }
    }
    return rest;
  }

  private static void rejectWritingWords(String submitted, String bones) {
    String lower = bones.toLowerCase(Locale.ROOT);
    for (String word : WRITING_WORDS) {
      int at = wordIndex(lower, word, 0);
      if (at >= 0) {
        throw new SqlNotAllowedException(
            submitted,
            "\""
                + word.toUpperCase(Locale.ROOT)
                + "\" changes the session database,"
                + " and this connection is opened read-only. Remove it. (If that"
                + " is a column name, quote it: \""
                + word
                + "\".)");
      }
    }
    // REPLACE is a scalar function and a statement. Only the statement
    // spelling — REPLACE INTO — is refused.
    int replace = wordIndex(lower, "replace", 0);
    while (replace >= 0) {
      String rest = lower.substring(replace + "replace".length()).stripLeading();
      if (rest.startsWith("into")) {
        throw new SqlNotAllowedException(
            submitted, "\"REPLACE INTO\" writes rows, and this connection is opened read-only.");
      }
      replace = wordIndex(lower, "replace", replace + 1);
    }
  }

  /**
   * Refuses any pragma whose bare name is not on the allowlist — and any pragma whose name is not
   * spelled bare at all.
   *
   * <p>The quoted-name refusal is explicit, not incidental. {@code PRAGMA "query_only" =
   * table_info} is legal SQLite: the quotes name the pragma and the unquoted word is its
   * <em>value</em>. An earlier version of this check parsed the name out of the skeleton, where
   * quoted identifiers are blanked — so the first bare word it found was the value, and a value
   * that happened to be an allowlisted name would have let a settable pragma through. Names are
   * therefore read from the original text, and a quote or bracket where the name should be is
   * refused by rule rather than by accident.
   *
   * @param afterPragma index in {@code bones}/{@code original} just past the {@code PRAGMA} keyword
   */
  private static void rejectNonIntrospectionPragma(
      String submitted, String bones, String original, int afterPragma) {
    int i = skipBlanked(bones, original, afterPragma);
    String word = barePragmaWord(submitted, original, i);
    int next = skipBlanked(bones, original, i + word.length());
    if (!word.isEmpty() && next < original.length() && original.charAt(next) == '.') {
      // PRAGMA main.table_info(x) is legal; the schema qualifier is not
      // the pragma's name.
      i = skipBlanked(bones, original, next + 1);
      word = barePragmaWord(submitted, original, i);
    }
    String name = word.toLowerCase(Locale.ROOT);
    if (!INTROSPECTION_PRAGMAS.contains(name)) {
      throw new SqlNotAllowedException(
          submitted,
          "PRAGMA "
              + (name.isEmpty() ? "(nothing)" : name)
              + " is not one of the introspection pragmas this view runs. Allowed: "
              + String.join(", ", INTROSPECTION_PRAGMAS.stream().sorted().toList())
              + ". Every other pragma either changes state or has a spelling that"
              + " does.");
    }
  }

  /**
   * The bare identifier at {@code i} in the original text, or {@code ""} when there is none —
   * refusing outright when a quote or bracket sits where the name should be.
   */
  private static String barePragmaWord(String submitted, String original, int i) {
    if (i >= original.length()) {
      return "";
    }
    char c = original.charAt(i);
    if (isQuoteStart(c)) {
      throw new SqlNotAllowedException(
          submitted,
          "A quoted pragma name is refused outright: the allowlist admits the"
              + " introspection pragmas by their bare names, and a quoted"
              + " spelling such as PRAGMA \"query_only\" = … would otherwise"
              + " be judged by its value instead of its name. Spell it bare:"
              + " PRAGMA table_info(…), PRAGMA index_list(…), ….");
    }
    if (!Character.isLetter(c) && c != '_') {
      return "";
    }
    int end = i;
    while (end < original.length() && isWordChar(original.charAt(end))) {
      end++;
    }
    return original.substring(i, end);
  }

  /**
   * A same-length copy of {@code sql} with comments, string literals and quoted identifiers
   * replaced by spaces.
   *
   * <p>Same length on purpose: every index into the skeleton is also an index into the original, so
   * the statement text handed back is the user's own and not a reconstruction.
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
    throw new SqlNotAllowedException(
        sql,
        "The "
            + describeQuote(quote)
            + " opened at character "
            + (start + 1)
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
    List<int[]> segments = new ArrayList<>(2);
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
