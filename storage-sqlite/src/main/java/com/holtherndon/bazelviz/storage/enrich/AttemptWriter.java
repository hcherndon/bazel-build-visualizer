package com.holtherndon.bazelviz.storage.enrich;

import com.holtherndon.bazelviz.core.enrich.AttemptCorrelation;
import com.holtherndon.bazelviz.core.enrich.EnrichmentCommand;
import com.holtherndon.bazelviz.core.enrich.EnrichmentCommand.EnvVar;
import com.holtherndon.bazelviz.core.enrich.EnrichmentCommand.OutputRef;
import com.holtherndon.bazelviz.core.enrich.EnrichmentCommand.SpawnTiming;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Turns execution-log commands into rows, and decides what each spawn is attached to.
 *
 * <h2>What this writer must not do</h2>
 *
 * <p>It writes no column any earlier phase wrote. ADR-009 and Phase 4's contract §4: where the
 * execution log and the BEP measure the same thing, both survive under names that say whose they
 * are. An attempt's {@code start_micros} sits beside the action's, and neither replaces the other.
 *
 * <p>It never guesses a correlation. Where more than one action matches, the attempt records {@link
 * AttemptCorrelation#AMBIGUOUS} and stays unattached, because plan 24 makes ambiguity a thing the
 * user must be able to see.
 */
public final class AttemptWriter implements AutoCloseable {

  private static final String INSERT_ARTIFACT =
      "INSERT INTO artifacts (path, digest, size_bytes, is_directory) VALUES (?, ?, ?, ?)"
          + " ON CONFLICT (path) DO UPDATE SET"
          + " digest = coalesce(artifacts.digest, excluded.digest),"
          + " size_bytes = coalesce(artifacts.size_bytes, excluded.size_bytes),"
          + " is_directory = max(artifacts.is_directory, excluded.is_directory)";
  private static final String SELECT_ARTIFACT = "SELECT id FROM artifacts WHERE path = ?";

  private static final String INSERT_LABEL =
      "INSERT INTO labels (value) VALUES (?) ON CONFLICT (value) DO NOTHING";
  private static final String SELECT_LABEL = "SELECT id FROM labels WHERE value = ?";
  private static final String INSERT_MNEMONIC =
      "INSERT INTO mnemonics (value) VALUES (?) ON CONFLICT (value) DO NOTHING";
  private static final String SELECT_MNEMONIC = "SELECT id FROM mnemonics WHERE value = ?";

  private static final String INSERT_INPUT_SET =
      "INSERT INTO input_sets (task_id, log_id) VALUES (?, ?)"
          + " ON CONFLICT (task_id, log_id) DO NOTHING";
  private static final String SELECT_INPUT_SET =
      "SELECT id FROM input_sets WHERE task_id = ? AND log_id = ?";
  private static final String INSERT_INPUT_SET_CHILD =
      "INSERT INTO input_set_children (parent_id, child_id) VALUES (?, ?)"
          + " ON CONFLICT (parent_id, child_id) DO NOTHING";
  private static final String INSERT_INPUT_SET_FILE =
      "INSERT INTO input_set_files (input_set_id, artifact_id) VALUES (?, ?)"
          + " ON CONFLICT (input_set_id, artifact_id) DO NOTHING";

  private static final String INSERT_ATTEMPT =
      "INSERT INTO action_attempts (task_id, log_entry_index, action_id, correlation,"
          + " correlation_note, label_id, mnemonic_id, runner, cache_hit, exit_code,"
          + " status, start_micros, start_unknown_reason, total_micros,"
          + " execution_wall_micros, parse_micros, network_micros, fetch_micros,"
          + " queue_micros, setup_micros, upload_micros, process_outputs_micros,"
          + " retry_micros, input_bytes, input_files, memory_estimate_bytes,"
          + " measured_memory_peak_bytes, timeout_millis, remotable, cacheable,"
          + " remote_cacheable, digest_hash, digest_size_bytes, digest_function,"
          + " input_set_id, tool_set_id)"
          + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,"
          + " ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

  private static final String INSERT_ATTEMPT_OUTPUT =
      "INSERT INTO attempt_outputs (attempt_id, artifact_id, kind, produced)"
          + " VALUES (?, ?, ?, ?)";
  private static final String INSERT_ENV =
      "INSERT INTO attempt_env_vars (attempt_id, name, value, redacted)"
          + " VALUES (?, ?, ?, ?) ON CONFLICT (attempt_id, name) DO NOTHING";

  /**
   * Actions whose primary output is one of this spawn's produced outputs.
   *
   * <p>Built per call because the number of outputs varies; the {@code IN} list is parameterised,
   * never interpolated.
   */
  private static final String MATCH_ACTIONS_PREFIX =
      "SELECT DISTINCT a.id FROM actions a"
          + " JOIN artifacts art ON art.path = a.primary_output"
          + " WHERE art.id IN (";

  private static final String MATCH_TEST_LABEL =
      "SELECT count(*) FROM tests t"
          + " JOIN configured_targets ct ON ct.id = t.configured_target_id"
          + " JOIN targets tg ON tg.id = ct.target_id"
          + " JOIN labels l ON l.id = tg.label_id WHERE l.value = ?";

  private final Connection connection;
  private final long taskId;
  private final LogIdMap artifactIds = new LogIdMap();
  private final LogIdMap inputSetIds = new LogIdMap();

  private final PreparedStatement insertArtifact;
  private final PreparedStatement selectArtifact;
  private final PreparedStatement insertLabel;
  private final PreparedStatement selectLabel;
  private final PreparedStatement insertMnemonic;
  private final PreparedStatement selectMnemonic;
  private final PreparedStatement insertInputSet;
  private final PreparedStatement selectInputSet;
  private final PreparedStatement insertInputSetChild;
  private final PreparedStatement insertInputSetFile;
  private final PreparedStatement insertAttempt;
  private final PreparedStatement insertOutput;
  private final PreparedStatement insertEnv;
  private final PreparedStatement matchTestLabel;

  private long attemptsWritten;
  private long undeclaredReferences;
  private Optional<String> invocationId = Optional.empty();

  public AttemptWriter(Connection connection, long taskId) throws SQLException {
    this.connection = connection;
    this.taskId = taskId;
    this.insertArtifact = connection.prepareStatement(INSERT_ARTIFACT);
    this.selectArtifact = connection.prepareStatement(SELECT_ARTIFACT);
    this.insertLabel = connection.prepareStatement(INSERT_LABEL);
    this.selectLabel = connection.prepareStatement(SELECT_LABEL);
    this.insertMnemonic = connection.prepareStatement(INSERT_MNEMONIC);
    this.selectMnemonic = connection.prepareStatement(SELECT_MNEMONIC);
    this.insertInputSet = connection.prepareStatement(INSERT_INPUT_SET);
    this.selectInputSet = connection.prepareStatement(SELECT_INPUT_SET);
    this.insertInputSetChild = connection.prepareStatement(INSERT_INPUT_SET_CHILD);
    this.insertInputSetFile = connection.prepareStatement(INSERT_INPUT_SET_FILE);
    this.insertAttempt =
        connection.prepareStatement(INSERT_ATTEMPT, Statement.RETURN_GENERATED_KEYS);
    this.insertOutput = connection.prepareStatement(INSERT_ATTEMPT_OUTPUT);
    this.insertEnv = connection.prepareStatement(INSERT_ENV);
    this.matchTestLabel = connection.prepareStatement(MATCH_TEST_LABEL);
  }

  /** Applies one command. */
  public void apply(EnrichmentCommand command) throws SQLException {
    switch (command) {
      case EnrichmentCommand.InvocationHeaderSeen header ->
          invocationId = Optional.of(header.buildId());
      case EnrichmentCommand.PathDeclared path -> writePath(path);
      case EnrichmentCommand.InputSetDeclared set -> writeInputSet(set);
      case EnrichmentCommand.SpawnObserved spawn -> writeSpawn(spawn);
      default -> {
        // Profile commands go to ProfileWriter. Naming the default
        // rather than throwing keeps a mixed stream harmless.
      }
    }
  }

  private void writePath(EnrichmentCommand.PathDeclared path) throws SQLException {
    boolean directory = path.kind() == OutputRef.Kind.DIRECTORY;
    insertArtifact.setString(1, path.path());
    setNullableString(insertArtifact, 2, path.digest().map(EnrichmentCommand.Digest::hash));
    setNullableLong(
        insertArtifact,
        3,
        path.digest()
            .map(EnrichmentCommand.Digest::sizeBytes)
            .map(OptionalLong::of)
            .orElse(OptionalLong.empty()));
    insertArtifact.setInt(4, directory ? 1 : 0);
    insertArtifact.executeUpdate();

    selectArtifact.setString(1, path.path());
    try (ResultSet rows = selectArtifact.executeQuery()) {
      if (rows.next()) {
        artifactIds.put(path.logId(), rows.getLong(1));
      }
    }
  }

  private void writeInputSet(EnrichmentCommand.InputSetDeclared set) throws SQLException {
    insertInputSet.setLong(1, taskId);
    insertInputSet.setLong(2, set.logId());
    insertInputSet.executeUpdate();

    long rowId;
    selectInputSet.setLong(1, taskId);
    selectInputSet.setLong(2, set.logId());
    try (ResultSet rows = selectInputSet.executeQuery()) {
      if (!rows.next()) {
        return;
      }
      rowId = rows.getLong(1);
    }
    inputSetIds.put(set.logId(), rowId);

    for (long child : set.childSetIds()) {
      long childRow = inputSetIds.get(child);
      if (childRow == 0) {
        // A set referencing one the log never defined. Counted rather
        // than dropped, for the same reason the BEP layer counts
        // undefined file sets: every total reached through it is a
        // lower bound.
        undeclaredReferences++;
        continue;
      }
      insertInputSetChild.setLong(1, rowId);
      insertInputSetChild.setLong(2, childRow);
      insertInputSetChild.addBatch();
    }
    insertInputSetChild.executeBatch();

    for (long file : set.fileLogIds()) {
      long artifactId = artifactIds.get(file);
      if (artifactId == 0) {
        undeclaredReferences++;
        continue;
      }
      insertInputSetFile.setLong(1, rowId);
      insertInputSetFile.setLong(2, artifactId);
      insertInputSetFile.addBatch();
    }
    insertInputSetFile.executeBatch();
  }

  private void writeSpawn(EnrichmentCommand.SpawnObserved spawn) throws SQLException {
    intern(insertLabel, spawn.targetLabel());
    intern(insertMnemonic, Optional.of(spawn.mnemonic()));

    Correlation correlation = correlate(spawn, producedArtifactIds(spawn));

    SpawnTiming timing = spawn.timing();
    int i = 1;
    insertAttempt.setLong(i++, taskId);
    insertAttempt.setLong(i++, spawn.entryIndex());
    setNullableLong(insertAttempt, i++, correlation.actionId());
    insertAttempt.setString(i++, correlation.kind().name());
    setNullableString(insertAttempt, i++, correlation.note());
    setNullableLong(insertAttempt, i++, lookup(selectLabel, spawn.targetLabel()));
    setNullableLong(insertAttempt, i++, lookup(selectMnemonic, Optional.of(spawn.mnemonic())));
    setNullableString(insertAttempt, i++, spawn.runner());
    insertAttempt.setInt(i++, spawn.cacheHit() ? 1 : 0);
    if (spawn.exitCode().isPresent()) {
      insertAttempt.setInt(i++, spawn.exitCode().getAsInt());
    } else {
      insertAttempt.setNull(i++, Types.INTEGER);
    }
    setNullableString(insertAttempt, i++, spawn.status());
    setNullableLong(insertAttempt, i++, timing.startMicros());
    setNullableString(insertAttempt, i++, spawn.startUnknownReason());
    setNullableLong(insertAttempt, i++, timing.totalMicros());
    setNullableLong(insertAttempt, i++, timing.executionWallMicros());
    setNullableLong(insertAttempt, i++, timing.parseMicros());
    setNullableLong(insertAttempt, i++, timing.networkMicros());
    setNullableLong(insertAttempt, i++, timing.fetchMicros());
    setNullableLong(insertAttempt, i++, timing.queueMicros());
    setNullableLong(insertAttempt, i++, timing.setupMicros());
    setNullableLong(insertAttempt, i++, timing.uploadMicros());
    setNullableLong(insertAttempt, i++, timing.processOutputsMicros());
    setNullableLong(insertAttempt, i++, timing.retryMicros());
    setNullableLong(insertAttempt, i++, timing.inputBytes());
    setNullableLong(insertAttempt, i++, timing.inputFiles());
    setNullableLong(insertAttempt, i++, timing.memoryEstimateBytes());
    setNullableLong(insertAttempt, i++, timing.measuredMemoryPeakBytes());
    setNullableLong(insertAttempt, i++, spawn.timeoutMillis());
    insertAttempt.setInt(i++, spawn.remotable() ? 1 : 0);
    insertAttempt.setInt(i++, spawn.cacheable() ? 1 : 0);
    insertAttempt.setInt(i++, spawn.remoteCacheable() ? 1 : 0);
    setNullableString(insertAttempt, i++, spawn.digest().map(EnrichmentCommand.Digest::hash));
    setNullableLong(
        insertAttempt,
        i++,
        spawn.digest().map(d -> OptionalLong.of(d.sizeBytes())).orElse(OptionalLong.empty()));
    setNullableString(
        insertAttempt, i++, spawn.digest().flatMap(EnrichmentCommand.Digest::hashFunctionName));
    setNullableLong(insertAttempt, i++, resolveSet(spawn.inputSetLogId()));
    setNullableLong(insertAttempt, i, resolveSet(spawn.toolSetLogId()));
    insertAttempt.executeUpdate();

    long attemptId;
    try (ResultSet keys = insertAttempt.getGeneratedKeys()) {
      if (!keys.next()) {
        return;
      }
      attemptId = keys.getLong(1);
    }
    attemptsWritten++;

    writeOutputs(attemptId, spawn);
    writeEnvironment(attemptId, spawn.environment());
  }

  private void writeOutputs(long attemptId, EnrichmentCommand.SpawnObserved spawn)
      throws SQLException {
    for (OutputRef output : spawn.outputs()) {
      long artifactId;
      if (output.wasProduced()) {
        artifactId = artifactIds.get(output.logId().getAsLong());
        if (artifactId == 0) {
          undeclaredReferences++;
          continue;
        }
      } else {
        // An invalid_output_path is a path the log never declared as an
        // entry, so it gets an artifact row of its own. On 7.6.1 a
        // failing test's whole output list is these (K2).
        artifactId = ensureArtifact(output.unproducedPath().orElseThrow());
      }
      insertOutput.setLong(1, attemptId);
      insertOutput.setLong(2, artifactId);
      insertOutput.setString(3, output.kind().name());
      insertOutput.setInt(4, output.wasProduced() ? 1 : 0);
      insertOutput.addBatch();
    }
    insertOutput.executeBatch();
  }

  private void writeEnvironment(long attemptId, List<EnvVar> environment) throws SQLException {
    for (EnvVar variable : environment) {
      insertEnv.setLong(1, attemptId);
      insertEnv.setString(2, variable.name());
      setNullableString(insertEnv, 3, variable.value());
      insertEnv.setInt(4, variable.redacted() ? 1 : 0);
      insertEnv.addBatch();
    }
    insertEnv.executeBatch();
  }

  // ------------------------------------------------------------ correlation

  /** The outcome of trying to attach one spawn to one action. */
  private record Correlation(
      AttemptCorrelation kind, OptionalLong actionId, Optional<String> note) {

    static Correlation of(AttemptCorrelation kind, String note) {
      return new Correlation(kind, OptionalLong.empty(), Optional.of(note));
    }

    static Correlation attached(long actionId) {
      return new Correlation(
          AttemptCorrelation.MATCHED_BY_OUTPUT, OptionalLong.of(actionId), Optional.empty());
    }
  }

  /**
   * Decides what this spawn belongs to.
   *
   * <p>The order matters. Tests are handled first because they never match an action by output on
   * any version — on 7.6.1 the spawn that ran the test has no resolvable output at all, and on
   * 8.4.1+ its only one is a {@code test.outputs} directory BEP never names (K2). Falling through
   * to the output match would classify every test spawn as unmatched and bury the real unmatched
   * spawns among them.
   */
  private Correlation correlate(EnrichmentCommand.SpawnObserved spawn, List<Long> producedArtifacts)
      throws SQLException {
    if (spawn.mnemonic().equals("TestRunner") && spawn.targetLabel().isPresent()) {
      return correlateTest(spawn.targetLabel().get());
    }

    if (producedArtifacts.isEmpty()) {
      return Correlation.of(
          AttemptCorrelation.NO_ACTION_EXPECTED,
          "this spawn produced no output that the build event stream names,"
              + " so there is nothing to attach it to");
    }

    List<Long> matches = actionsProducing(producedArtifacts);
    if (matches.size() == 1) {
      return Correlation.attached(matches.getFirst());
    }
    if (matches.size() > 1) {
      // Two actions claiming one of this spawn's outputs as their primary
      // output. Picking either would attach real measurements to a
      // possibly wrong action.
      return Correlation.of(
          AttemptCorrelation.AMBIGUOUS,
          matches.size()
              + " actions name one of this spawn's outputs as their"
              + " primary output, so which one ran here is undecided");
    }
    return Correlation.of(
        AttemptCorrelation.UNMATCHED,
        "no action in the build event stream names any of this spawn's outputs"
            + " as its primary output; the build may have been captured without"
            + " --build_event_publish_all_actions");
  }

  /**
   * A test spawn, attached to its test by label.
   *
   * <p>Both of a test's two spawns land here and both keep their own row: the second is XML
   * generation, which exits 0 even when the test failed (K3). Nothing here tries to pick which one
   * is "the" test — the tests view takes its verdict from {@code testSummary} and needs no help,
   * and choosing between them on the shape of their outputs would be a guess presented as a fact.
   */
  private Correlation correlateTest(String label) throws SQLException {
    matchTestLabel.setString(1, label);
    try (ResultSet rows = matchTestLabel.executeQuery()) {
      boolean known = rows.next() && rows.getLong(1) > 0;
      return known
          ? Correlation.of(
              AttemptCorrelation.MATCHED_BY_TEST_LABEL,
              "a test execution, attached to its test rather than to an action:"
                  + " test spawns name no output the build event stream"
                  + " reports as an action's primary output")
          : Correlation.of(
              AttemptCorrelation.UNMATCHED,
              "a test execution for "
                  + label
                  + ", which this session's build"
                  + " event stream does not mention");
    }
  }

  /**
   * The artifact rows this spawn actually produced.
   *
   * <p>Ids, not paths. The id is already in hand from {@link LogIdMap}, so this costs no query at
   * all — and the alternative, reading each path back to compare it in Java, would be one round
   * trip per output per spawn on a table whose whole point is that it has millions of rows.
   */
  private List<Long> producedArtifactIds(EnrichmentCommand.SpawnObserved spawn) {
    List<Long> ids = new ArrayList<>(spawn.outputs().size());
    for (OutputRef output : spawn.outputs()) {
      if (!output.wasProduced()) {
        continue;
      }
      long artifactId = artifactIds.get(output.logId().getAsLong());
      if (artifactId != 0) {
        ids.add(artifactId);
      }
    }
    return ids;
  }

  /**
   * Actions whose primary output is one of these artifacts.
   *
   * <p>One statement, joining on {@code artifacts.path = actions.primary_output} — both columns are
   * UNIQUE and therefore indexed. The {@code IN} list is parameterised; nothing is interpolated
   * into SQL.
   */
  private List<Long> actionsProducing(List<Long> artifactRowIds) throws SQLException {
    StringBuilder sql = new StringBuilder(MATCH_ACTIONS_PREFIX);
    for (int i = 0; i < artifactRowIds.size(); i++) {
      sql.append(i == 0 ? "?" : ", ?");
    }
    sql.append(')');
    List<Long> ids = new ArrayList<>();
    try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
      for (int i = 0; i < artifactRowIds.size(); i++) {
        statement.setLong(i + 1, artifactRowIds.get(i));
      }
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          ids.add(rows.getLong(1));
        }
      }
    }
    return ids;
  }

  // ---------------------------------------------------------------- helpers

  private long ensureArtifact(String path) throws SQLException {
    insertArtifact.setString(1, path);
    insertArtifact.setNull(2, Types.VARCHAR);
    insertArtifact.setNull(3, Types.INTEGER);
    insertArtifact.setInt(4, 0);
    insertArtifact.executeUpdate();
    selectArtifact.setString(1, path);
    try (ResultSet rows = selectArtifact.executeQuery()) {
      return rows.next() ? rows.getLong(1) : 0;
    }
  }

  private OptionalLong resolveSet(OptionalLong logId) {
    if (logId.isEmpty()) {
      return OptionalLong.empty();
    }
    long row = inputSetIds.get(logId.getAsLong());
    return row == 0 ? OptionalLong.empty() : OptionalLong.of(row);
  }

  private void intern(PreparedStatement statement, Optional<String> value) throws SQLException {
    if (value.isEmpty() || value.get().isEmpty()) {
      return;
    }
    statement.setString(1, value.get());
    statement.executeUpdate();
  }

  private OptionalLong lookup(PreparedStatement statement, Optional<String> value)
      throws SQLException {
    if (value.isEmpty() || value.get().isEmpty()) {
      return OptionalLong.empty();
    }
    statement.setString(1, value.get());
    try (ResultSet rows = statement.executeQuery()) {
      return rows.next() ? OptionalLong.of(rows.getLong(1)) : OptionalLong.empty();
    }
  }

  private static void setNullableLong(PreparedStatement statement, int index, OptionalLong value)
      throws SQLException {
    if (value.isPresent()) {
      statement.setLong(index, value.getAsLong());
    } else {
      statement.setNull(index, Types.INTEGER);
    }
  }

  private static void setNullableString(
      PreparedStatement statement, int index, Optional<String> value) throws SQLException {
    if (value.isPresent()) {
      statement.setString(index, value.get());
    } else {
      statement.setNull(index, Types.VARCHAR);
    }
  }

  /** How many attempts were written. */
  public long attemptsWritten() {
    return attemptsWritten;
  }

  /**
   * How many times the log referenced an id it never declared.
   *
   * <p>Non-zero means every input total reached through those references is a lower bound, which
   * the coverage panel says rather than presenting a smaller number as the answer.
   */
  public long undeclaredReferences() {
    return undeclaredReferences;
  }

  /** The invocation id from the log's header, when the format has one. */
  public Optional<String> invocationId() {
    return invocationId;
  }

  @Override
  public void close() throws SQLException {
    SQLException first = null;
    for (PreparedStatement statement :
        List.of(
            insertArtifact,
            selectArtifact,
            insertLabel,
            selectLabel,
            insertMnemonic,
            selectMnemonic,
            insertInputSet,
            selectInputSet,
            insertInputSetChild,
            insertInputSetFile,
            insertAttempt,
            insertOutput,
            insertEnv,
            matchTestLabel)) {
      try {
        statement.close();
      } catch (SQLException failure) {
        if (first == null) {
          first = failure;
        } else {
          first.addSuppressed(failure);
        }
      }
    }
    if (first != null) {
      throw first;
    }
  }
}
