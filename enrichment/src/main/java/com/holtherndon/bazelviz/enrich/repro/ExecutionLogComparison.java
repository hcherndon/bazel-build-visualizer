package com.holtherndon.bazelviz.enrich.repro;

import com.holtherndon.bazelviz.core.filter.FilterExpression;
import com.holtherndon.bazelviz.core.repro.ReproComparison;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import org.sqlite.ProgressHandler;

/**
 * Disposable, private comparison of immutable raw execution logs. Use on a worker, not the EDT.
 * Source IDs, record ordering and shared input-set shapes do not participate in semantic equality.
 */
public final class ExecutionLogComparison implements ReproComparison {
  /**
   * Verified raw bytes, not a statement that every recorded action can be compared completely.
   * Format, header presence and embedded identity are separate evidence: older compact headers
   * carry no invocation ID, while binary logs have no invocation header at all. Identity is never
   * inferred from the expected ID or a file's name.
   */
  public record Verification(
      long records,
      long spawns,
      String sha256,
      boolean invocationMatched,
      boolean compactFormat,
      boolean invocationHeaderPresent,
      Optional<String> embeddedInvocationId,
      long cachedSpawns,
      long remoteSpawns,
      long unknownRunnerSpawns,
      List<String> coverageNotes) {
    public Verification {
      Objects.requireNonNull(embeddedInvocationId);
      coverageNotes = List.copyOf(coverageNotes);
    }
  }

  final ComparisonLimits limits;
  private final Path directory;
  private final Connection connection;
  private final BooleanSupplier cancelled;
  private long sqlSteps;
  private boolean closed;

  private ExecutionLogComparison(Path directory, ComparisonLimits limits, BooleanSupplier cancelled)
      throws SQLException {
    this.directory = directory;
    this.limits = limits;
    this.cancelled = cancelled;
    connection = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("comparison.db"));
    try {
      sql("PRAGMA journal_mode=OFF");
      sql("PRAGMA synchronous=OFF");
      sql("PRAGMA temp_store=FILE");
      sql("PRAGMA cache_size=-1024");
      sql("PRAGMA page_size=4096");
      sql("PRAGMA max_page_count=" + limits.databaseBytes() / 4096);
      ProgressHandler.setHandler(
          connection,
          1000,
          new ProgressHandler() {
            @Override
            protected int progress() {
              sqlSteps += 1000;
              return sqlSteps > limits.sqlSteps()
                      || cancelled.getAsBoolean()
                      || Thread.currentThread().isInterrupted()
                  ? 1
                  : 0;
            }
          });
      schema();
      sql("PRAGMA temp.cache_size=-1024");
      sql("PRAGMA temp.max_page_count=" + limits.databaseBytes() / 4096);
      ComparisonFilter.register(connection);
    } catch (SQLException | RuntimeException e) {
      try {
        connection.close();
      } catch (SQLException cleanup) {
        e.addSuppressed(cleanup);
      }
      throw e;
    }
  }

  public static ReproComparison open(Path a, Path b, Path scratchParent, BooleanSupplier cancelled)
      throws IOException {
    return open(a, b, scratchParent, cancelled, ComparisonLimits.defaults());
  }

  public static ReproComparison open(
      Path a, Path b, Path scratchParent, BooleanSupplier cancelled, ComparisonLimits limits)
      throws IOException {
    return open(a, b, scratchParent, cancelled, limits, Optional.empty(), Optional.empty());
  }

  public static ReproComparison open(
      Path a,
      Path b,
      Path scratchParent,
      BooleanSupplier cancelled,
      Optional<String> expectedSha256A,
      Optional<String> expectedSha256B)
      throws IOException {
    return open(
        a,
        b,
        scratchParent,
        cancelled,
        ComparisonLimits.defaults(),
        expectedSha256A,
        expectedSha256B);
  }

  private static ReproComparison open(
      Path a,
      Path b,
      Path scratchParent,
      BooleanSupplier cancelled,
      ComparisonLimits limits,
      Optional<String> expectedSha256A,
      Optional<String> expectedSha256B)
      throws IOException {
    Objects.requireNonNull(cancelled);
    if (Files.isSameFile(a, b)) {
      throw new IOException("Choose two different execution logs.");
    }
    Files.createDirectories(scratchParent);
    Path owned = Files.createTempDirectory(scratchParent, "repro-");
    try {
      if (Files.getFileStore(owned).supportsFileAttributeView("posix")) {
        Files.setPosixFilePermissions(owned, PosixFilePermissions.fromString("rwx------"));
      }
      ExecutionLogComparison result = new ExecutionLogComparison(owned, limits, cancelled);
      try {
        // Snapshots remain outside all source session stores and protect against concurrent edits.
        Path snapshotA = result.snapshot(a, "a.log");
        Path snapshotB = result.snapshot(b, "b.log");
        result.checkDigest(snapshotA, expectedSha256A);
        result.checkDigest(snapshotB, expectedSha256B);
        new ObservationIndexer(result, 0).read(snapshotA);
        new ObservationIndexer(result, 1).read(snapshotB);
        result.index();
        result.compare();
        return result;
      } catch (SQLException | IOException | RuntimeException failure) {
        try {
          result.close();
        } catch (IOException cleanup) {
          failure.addSuppressed(cleanup);
        }
        throw failure;
      }
    } catch (SQLException e) {
      cleanupAfterFailure(owned, e);
      throw new IOException(
          "Comparison stopped: malformed data or SQLite disk/work limit. No result was published.",
          e);
    } catch (IOException | RuntimeException e) {
      cleanupAfterFailure(owned, e);
      throw e;
    }
  }

  private void checkDigest(Path snapshot, Optional<String> expected) throws IOException {
    if (expected.isEmpty()) {
      return;
    }
    if (!expected.get().matches("[a-fA-F0-9]{64}")) {
      throw new IOException("Saved audit has an invalid execution-log SHA-256 identity.");
    }
    if (!sourceDigest(snapshot).equalsIgnoreCase(expected.get())) {
      throw new IOException(
          "Execution-log evidence changed since the saved audit; no comparison was published.");
    }
  }

  private String sourceDigest(Path snapshot) throws IOException {
    MessageDigest digest = newDigest();
    try (var in = Files.newInputStream(snapshot)) {
      byte[] buffer = new byte[65536];
      int length;
      while ((length = in.read(buffer)) != -1) {
        check();
        digest.update(buffer, 0, length);
      }
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  public static Verification verify(
      Path source, Path scratchParent, String expectedInvocationId, BooleanSupplier cancelled)
      throws IOException {
    Files.createDirectories(scratchParent);
    Path owned = Files.createTempDirectory(scratchParent, "repro-");
    try {
      if (Files.getFileStore(owned).supportsFileAttributeView("posix")) {
        Files.setPosixFilePermissions(owned, PosixFilePermissions.fromString("rwx------"));
      }
      try (ExecutionLogComparison db =
          new ExecutionLogComparison(owned, ComparisonLimits.defaults(), cancelled)) {
        Path snapshot = db.snapshot(source, "a.log");
        ObservationIndexer indexer = new ObservationIndexer(db, 0);
        indexer.read(snapshot);
        boolean matched =
            !indexer.invocationId().isEmpty()
                && indexer.invocationId().equals(expectedInvocationId);
        if (!indexer.invocationId().isEmpty() && !matched) {
          throw new IOException(
              "Execution-log invocation identity does not match the captured build.");
        }
        if (!matched) {
          db.note(
              0,
              "This execution log cannot independently establish the expected invocation"
                  + " identity.");
        }
        return new Verification(
            indexer.records(),
            db.number("SELECT count(*) FROM spawns"),
            db.sourceDigest(snapshot),
            matched,
            indexer.compactFormat(),
            indexer.invocationHeaderPresent(),
            indexer.invocationId().isEmpty()
                ? Optional.empty()
                : Optional.of(indexer.invocationId()),
            db.number(
                "SELECT count(*) FROM spawns WHERE cache_hit=1 OR instr(runner,'cache hit')>0"),
            db.number("SELECT count(*) FROM spawns WHERE runner='remote'"),
            db.number(
                "SELECT count(*) FROM spawns WHERE runner NOT IN"
                    + " ('local','darwin-sandbox','linux-sandbox','processwrapper-sandbox','worker','remote','disk"
                    + " cache hit','remote cache hit')"),
            db.summary().coverageNotes());
      }
    } catch (SQLException e) {
      cleanupAfterFailure(owned, e);
      throw failure(e);
    } catch (IOException | RuntimeException e) {
      cleanupAfterFailure(owned, e);
      throw e;
    }
  }

  private Path snapshot(Path source, String name) throws IOException {
    if (!Files.isRegularFile(source)) {
      throw new IOException(
          "Choose a regular execution-log file, not a directory, pipe or device.");
    }
    long size = Files.size(source);
    if (size > limits.sourceBytes()) {
      throw new IOException(
          "Execution log exceeds the source byte limit (" + limits.sourceBytes() + ").");
    }
    var before = Files.readAttributes(source, BasicFileAttributes.class);
    Path target = directory.resolve(name);
    try (var in = Files.newInputStream(source);
        var out = Files.newOutputStream(target)) {
      byte[] buffer = new byte[65536];
      long copied = 0;
      int n;
      while ((n = in.read(buffer)) != -1) {
        check();
        copied += n;
        if (copied > limits.sourceBytes()) {
          throw new IOException("Execution log grew beyond the source byte limit while copying.");
        }
        out.write(buffer, 0, n);
      }
      var after = Files.readAttributes(source, BasicFileAttributes.class);
      if (copied != before.size()
          || before.size() != after.size()
          || !before.lastModifiedTime().equals(after.lastModifiedTime())
          || !Objects.equals(before.fileKey(), after.fileKey())) {
        throw new IOException(
            "Execution log changed while its comparison snapshot was being copied.");
      }
    }
    return target;
  }

  private void schema() throws SQLException {
    sql("CREATE TABLE entry_ids(side INTEGER,id INTEGER,PRIMARY KEY(side,id)) WITHOUT ROWID");
    sql(
        "CREATE TABLE differences(section TEXT,field TEXT,position INTEGER,before TEXT,after"
            + " TEXT,PRIMARY KEY(section,field,position)) WITHOUT ROWID");
    sql("CREATE TABLE notes(side INTEGER,note TEXT,PRIMARY KEY(side,note)) WITHOUT ROWID");
    sql("CREATE TABLE sources(side INTEGER PRIMARY KEY,incomplete INTEGER NOT NULL DEFAULT 0)");
    sql("INSERT INTO sources VALUES(0,0),(1,0)");
    sql(
        "CREATE TABLE items(side INTEGER,id INTEGER,kind TEXT,path TEXT,PRIMARY KEY(side,id))"
            + " WITHOUT ROWID");
    sql(
        "CREATE TABLE files(side INTEGER,item INTEGER,path TEXT,kind TEXT,value TEXT,known"
            + " INTEGER,produced INTEGER)");
    sql("CREATE INDEX files_item ON files(side,item)");
    sql(
        "CREATE TABLE edges(side INTEGER,parent INTEGER,child INTEGER,PRIMARY"
            + " KEY(side,parent,child)) WITHOUT ROWID");
    sql(
        "CREATE TABLE spawns(side INTEGER,id INTEGER,target TEXT,mnemonic TEXT,runner"
            + " TEXT,cache_hit INTEGER,eligible INTEGER,reason TEXT,inputs INTEGER,tools"
            + " INTEGER,incomplete INTEGER,recipehash TEXT,inputhash TEXT,outputhash TEXT,matchkey"
            + " TEXT,output TEXT,PRIMARY KEY(side,id)) WITHOUT ROWID");
    sql("CREATE TABLE recipe(side INTEGER,spawn INTEGER,section TEXT,field TEXT,value TEXT)");
    sql("CREATE INDEX recipe_spawn ON recipe(side,spawn,section,field,value)");
    sql("CREATE TABLE outputs(side INTEGER,spawn INTEGER,item INTEGER,path TEXT)");
    sql("CREATE INDEX outputs_spawn ON outputs(side,spawn)");
    sql(
        "CREATE TABLE producers(side INTEGER,path TEXT,value TEXT,spawn INTEGER,PRIMARY"
            + " KEY(side,path,value,spawn)) WITHOUT ROWID");
    sql("CREATE TABLE declared(side INTEGER,spawn INTEGER,path TEXT)");
    sql("CREATE INDEX declared_spawn ON declared(side,spawn,path)");
    sql(
        "CREATE TABLE fingerprints(side INTEGER,id INTEGER,hash TEXT,known INTEGER,PRIMARY"
            + " KEY(side,id)) WITHOUT ROWID");
    sql(
        "CREATE TABLE results(id INTEGER PRIMARY KEY,a INTEGER,b INTEGER,target TEXT,mnemonic"
            + " TEXT,output TEXT,finding TEXT,recipe_changed INTEGER,input_changed"
            + " INTEGER,output_changed INTEGER,reason TEXT,incomplete INTEGER)");
    sql("CREATE INDEX results_b ON results(b)");
    sql(
        "CREATE TABLE output_changes(path TEXT,old_value TEXT,new_value TEXT,producer"
            + " INTEGER,PRIMARY KEY(path,old_value,new_value,producer)) WITHOUT ROWID");
    sql(
        "CREATE TABLE manifest(section TEXT,path TEXT,kind TEXT,value TEXT,known"
            + " INTEGER,PRIMARY KEY(section,path,kind,value)) WITHOUT ROWID");
    sql(
        "CREATE TABLE detail_a(section TEXT,field TEXT,position INTEGER,value TEXT,PRIMARY"
            + " KEY(section,field,position)) WITHOUT ROWID");
    sql(
        "CREATE TABLE detail_b(section TEXT,field TEXT,position INTEGER,value TEXT,PRIMARY"
            + " KEY(section,field,position)) WITHOUT ROWID");
  }

  void check() throws IOException {
    if (closed || cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
      throw new IOException("Comparison cancelled or closed.");
    }
    if (sqlSteps > limits.sqlSteps()) {
      throw new IOException(
          "Comparison exceeded its SQLite work limit (" + limits.sqlSteps() + ").");
    }
  }

  boolean cancelled() {
    return closed || cancelled.getAsBoolean() || Thread.currentThread().isInterrupted();
  }

  void put(String query, Object... values) throws SQLException, IOException {
    check();
    try (PreparedStatement p = prepare(query, values)) {
      p.executeUpdate();
    }
  }

  private PreparedStatement prepare(String query, Object... values) throws SQLException {
    chargeStatement();
    PreparedStatement p = connection.prepareStatement(query);
    for (int i = 0; i < values.length; i++) {
      p.setObject(i + 1, values[i]);
    }
    return p;
  }

  private void sql(String query) throws SQLException {
    chargeStatement();
    try (Statement s = connection.createStatement()) {
      s.execute(query);
    }
  }

  private void chargeStatement() throws SQLException {
    // Charge the unreported tail below the progress handler's 1,000-instruction interval too.
    // This deliberately overestimates short statements instead of letting many small writes escape.
    sqlSteps += 1000;
    if (sqlSteps > limits.sqlSteps()
        || cancelled.getAsBoolean()
        || Thread.currentThread().isInterrupted()) {
      throw new SQLException("Comparison cancelled or exceeded its SQLite work budget.");
    }
  }

  void note(int side, String text) throws SQLException, IOException {
    put("INSERT OR IGNORE INTO notes VALUES(?,?)", side, text);
  }

  void incomplete(int side) throws SQLException, IOException {
    put("UPDATE sources SET incomplete=1 WHERE side=?", side);
  }

  private long number(String query, Object... values) throws SQLException {
    try (PreparedStatement p = prepare(query, values);
        ResultSet r = p.executeQuery()) {
      return r.next() ? r.getLong(1) : 0;
    }
  }

  boolean hasItem(int side, long id) throws SQLException {
    return number("SELECT count(*) FROM items WHERE side=? AND id=?", side, id) == 1;
  }

  boolean isArtifact(int side, long id) throws SQLException {
    return number(
            "SELECT count(*) FROM items WHERE side=? AND id=? AND kind IN"
                + " ('FILE','DIRECTORY','SYMLINK')",
            side,
            id)
        == 1;
  }

  boolean isSet(int side, long id) throws SQLException {
    return id == 0
        || number("SELECT count(*) FROM items WHERE side=? AND id=? AND kind='SET'", side, id) == 1;
  }

  boolean hasTreeOutputs(int side, long spawn) throws SQLException {
    return number(
            "SELECT EXISTS(SELECT 1 FROM declared d JOIN outputs o ON o.side=d.side AND"
                + " o.spawn=d.spawn JOIN items i ON i.side=o.side AND i.id=o.item WHERE d.side=?"
                + " AND d.spawn=? AND substr(i.path,1,length(d.path)+1)=d.path||'/')",
            side,
            spawn)
        != 0;
  }

  private void index() throws SQLException, IOException {
    sql("CREATE INDEX declared_path ON declared(side,path,spawn)");
    put(
        "INSERT INTO recipe SELECT DISTINCT side,spawn,'Declarations',path,'' FROM declared WHERE"
            + " path IS NOT NULL");
    put(
        "UPDATE spawns SET incomplete=1,reason='Duplicate environment or platform keys have"
            + " ambiguous ordering.' WHERE EXISTS (SELECT 1 FROM recipe r WHERE r.side=spawns.side"
            + " AND r.spawn=spawns.id AND r.section IN ('Environment','Platform') GROUP BY"
            + " r.section,r.field HAVING count(*)>1)");
    for (int side = 0; side < 2; side++) {
      long last = 0;
      while (true) {
        check();
        try (PreparedStatement p =
                prepare(
                    "SELECT id,inputs,tools FROM spawns WHERE side=? AND id>? ORDER BY id LIMIT 1",
                    side,
                    last);
            ResultSet r = p.executeQuery()) {
          if (!r.next()) {
            break;
          }
          long id = r.getLong(1);
          last = id;
          Fingerprint inputs = setFingerprint(side, r.getLong(2));
          Fingerprint tools = setFingerprint(side, r.getLong(3));
          String recipe =
              hash(
                  "SELECT section,field,value FROM recipe WHERE side=? AND spawn=? AND"
                      + " section<>'Evidence' ORDER BY section,field,value",
                  side,
                  id);
          sql("DELETE FROM manifest");
          boolean outputsKnown = outputManifest(side, id);
          String outputHash =
              hash("SELECT section,path,kind,value FROM manifest ORDER BY section,path,kind,value");
          String key =
              hash(
                  "SELECT DISTINCT path FROM declared WHERE side=? AND spawn=? ORDER BY path",
                  side,
                  id);
          String output;
          try (PreparedStatement q =
                  prepare("SELECT min(path) FROM declared WHERE side=? AND spawn=?", side, id);
              ResultSet paths = q.executeQuery()) {
            output = paths.next() ? paths.getString(1) : null;
          }
          put(
              "UPDATE spawns SET"
                  + " recipehash=?,inputhash=?,outputhash=?,matchkey=?,output=?,incomplete=incomplete"
                  + " OR ? WHERE side=? AND id=?",
              recipe,
              inputs.hash + ":" + tools.hash,
              outputHash,
              key,
              Objects.requireNonNullElse(output, ""),
              !inputs.known
                  || !tools.known
                  || !outputsKnown
                  || output == null
                  || number("SELECT incomplete FROM sources WHERE side=?", side) != 0,
              side,
              id);
        }
      }
    }
    sql("CREATE INDEX spawns_match ON spawns(side,target,mnemonic,matchkey)");
  }

  private record Fingerprint(String hash, boolean known) {}

  private Fingerprint setFingerprint(int side, long id) throws SQLException, IOException {
    try (PreparedStatement p =
            prepare("SELECT hash,known FROM fingerprints WHERE side=? AND id=?", side, id);
        ResultSet r = p.executeQuery()) {
      if (r.next()) {
        return new Fingerprint(r.getString(1), r.getBoolean(2));
      }
    }
    sql("DELETE FROM manifest");
    boolean known = inputManifest(side, id, "Inputs");
    String fingerprint =
        hash(
            "SELECT path,kind,value FROM manifest WHERE section='Inputs' ORDER BY path,kind,value");
    put("INSERT INTO fingerprints VALUES(?,?,?,?)", side, id, fingerprint, known);
    return new Fingerprint(fingerprint, known);
  }

  private static String reachable() {
    return "WITH RECURSIVE reachable(id) AS (SELECT ? UNION SELECT e.child FROM edges e JOIN"
        + " reachable r ON e.parent=r.id WHERE e.side=?) ";
  }

  private boolean inputManifest(int side, long id, String section)
      throws SQLException, IOException {
    if (id == 0) {
      return true;
    }
    if (number("SELECT count(*) FROM items WHERE side=? AND id=? AND kind='SET'", side, id) != 1) {
      return false;
    }
    put(
        reachable()
            + "INSERT INTO manifest SELECT ?,f.path,f.kind,f.value,f.known FROM reachable r JOIN"
            + " files f ON f.item=r.id WHERE f.side=? ON CONFLICT(section,path,kind,value) DO"
            + " UPDATE SET known=min(manifest.known,excluded.known)",
        id,
        side,
        section,
        side);
    boolean known =
        number(
                reachable()
                    + "SELECT count(*) FROM reachable r LEFT JOIN items i ON i.id=r.id AND i.side=?"
                    + " WHERE i.id IS NULL OR i.kind='UNSUPPORTED'",
                id,
                side,
                side)
            == 0;
    return known && manifestKnown(section);
  }

  private boolean outputManifest(int side, long id) throws SQLException, IOException {
    put(
        "INSERT INTO manifest SELECT 'Outputs',f.path,f.kind,f.value,f.known FROM outputs o JOIN"
            + " files f ON f.side=o.side AND f.item=o.item WHERE o.side=? AND o.spawn=? ON"
            + " CONFLICT(section,path,kind,value) DO UPDATE SET"
            + " known=min(manifest.known,excluded.known)",
        side,
        id);
    put(
        "INSERT OR IGNORE INTO manifest SELECT 'Outputs',path,'MISSING','not produced',0 FROM"
            + " outputs WHERE side=? AND spawn=? AND item IS NULL AND path IS NOT NULL",
        side,
        id);
    boolean resolved =
        number(
                "SELECT count(*) FROM outputs o LEFT JOIN items i ON i.side=o.side AND i.id=o.item"
                    + " WHERE o.side=? AND o.spawn=? AND (o.item IS NULL OR i.id IS NULL OR"
                    + " i.kind='UNSUPPORTED')",
                side,
                id)
            == 0;
    return resolved && manifestKnown("Outputs");
  }

  private boolean manifestKnown(String section) throws SQLException {
    return number("SELECT count(*) FROM manifest WHERE section=? AND known=0", section) == 0
        && number(
                "SELECT count(*) FROM (SELECT path FROM manifest WHERE section=? GROUP BY path"
                    + " HAVING count(*)>1)",
                section)
            == 0;
  }

  private String hash(String query, Object... values) throws SQLException, IOException {
    MessageDigest digest = newDigest();
    try (PreparedStatement p = prepare(query, values);
        ResultSet r = p.executeQuery()) {
      int columns = r.getMetaData().getColumnCount();
      while (r.next()) {
        check();
        for (int i = 1; i <= columns; i++) {
          byte[] value =
              Objects.requireNonNullElse(r.getString(i), "[unknown]")
                  .getBytes(StandardCharsets.UTF_8);
          digest.update(ByteBuffer.allocate(4).putInt(value.length).array());
          digest.update(value);
        }
      }
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  private static MessageDigest newDigest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private void compare() throws SQLException, IOException {
    // Only a unique label/mnemonic/output-set group can be paired. Preserve every retry otherwise.
    try (PreparedStatement p = prepare("SELECT * FROM spawns WHERE side=0 ORDER BY id");
        ResultSet a = p.executeQuery()) {
      while (a.next()) {
        check();
        long id = a.getLong("id");
        Object[] group = {a.getString("target"), a.getString("mnemonic"), a.getString("matchkey")};
        long ac =
            number(
                "SELECT count(*) FROM spawns WHERE side=0 AND target=? AND mnemonic=? AND"
                    + " matchkey=?",
                group);
        long bc =
            number(
                "SELECT count(*) FROM spawns WHERE side=1 AND target=? AND mnemonic=? AND"
                    + " matchkey=?",
                group);
        long candidate =
            bc == 1
                ? number(
                    "SELECT id FROM spawns WHERE side=1 AND target=? AND mnemonic=? AND matchkey=?",
                    group)
                : 0;
        boolean fallback = false;
        if (ac == 1 && bc == 0) {
          candidate = sharedCandidate(0, id, a.getString("target"), a.getString("mnemonic"));
          if (candidate != 0
              && sharedCandidate(1, candidate, a.getString("target"), a.getString("mnemonic"))
                  == id) {
            bc = 1;
            fallback = true;
          }
        }
        if (ac != 1 || bc > 1 || a.getString("output").isEmpty()) {
          result(
              a,
              null,
              Finding.INCONCLUSIVE,
              false,
              false,
              false,
              "Ambiguous action observations; retries are not paired by arrival order.",
              true);
        } else if (bc == 0) {
          result(
              a,
              null,
              Finding.REMOVED,
              false,
              false,
              false,
              "No unique counterpart with the same target, mnemonic and output set.",
              true);
        } else {
          try (PreparedStatement q =
                  prepare(
                      "SELECT * FROM spawns WHERE side=1 AND target=? AND mnemonic=? AND" + " id=?",
                      a.getString("target"),
                      a.getString("mnemonic"),
                      candidate);
              ResultSet b = q.executeQuery()) {
            b.next();
            boolean recipe = !a.getString("recipehash").equals(b.getString("recipehash"));
            boolean inputs = !a.getString("inputhash").equals(b.getString("inputhash"));
            boolean outputs = !a.getString("outputhash").equals(b.getString("outputhash"));
            boolean key =
                number(
                        "SELECT count(*) FROM recipe a JOIN recipe b ON b.section=a.section AND"
                            + " b.field=a.field WHERE a.side=0 AND a.spawn=? AND b.side=1 AND"
                            + " b.spawn=? AND a.section='Evidence' AND a.field='Action cache"
                            + " digest' AND a.value<>b.value",
                        id,
                        b.getLong("id"))
                    > 0;
            boolean incomplete =
                a.getBoolean("incomplete")
                    || b.getBoolean("incomplete")
                    || !a.getBoolean("eligible")
                    || !b.getBoolean("eligible");
            Finding finding =
                recipe
                    ? Finding.RECIPE_DRIFT
                    : inputs
                        ? Finding.INPUT_DRIFT
                        : outputs
                            ? (incomplete ? Finding.OUTPUT_CHANGED : Finding.OUTPUT_DIVERGENCE)
                            : key
                                ? Finding.CACHE_IDENTITY_DRIFT
                                : incomplete ? Finding.INCONCLUSIVE : Finding.UNCHANGED;
            String reason =
                recipe
                    ? "Recorded command, environment, platform or policy changed."
                    : inputs
                        ? "Recorded input content or tool membership changed."
                        : outputs
                            ? "Output content changed"
                                + (incomplete
                                    ? "; incomplete evidence prevents equal-input conclusions."
                                    : " with equal recorded recipe and inputs. This does not"
                                        + " identify a hidden cause.")
                            : key
                                ? "Bazel's action cache digest changed despite equal recorded"
                                    + " recipe, inputs and outputs; identity difference is"
                                    + " unexplained."
                                : incomplete
                                    ? "Missing, unsupported, redacted, failed or cached evidence"
                                        + " prevents verification."
                                    : "No differences observed in the recorded recipe, inputs and"
                                        + " outputs; not proof of hermeticity.";
            if (!a.getBoolean("eligible") || !b.getBoolean("eligible")) {
              reason += " One or both observations did not independently execute successfully.";
            }
            if (!a.getString("reason").isEmpty()) {
              reason += " A: " + a.getString("reason");
            }
            if (!b.getString("reason").isEmpty()) {
              reason += " B: " + b.getString("reason");
            }
            if (fallback) {
              reason +=
                  " Paired through a unique shared output because the declared output set changed.";
            }
            if (key && (recipe || inputs || outputs)) {
              reason += " Bazel's recorded action cache digest also changed.";
            }
            result(a, b.getLong("id"), finding, recipe, inputs, outputs, reason, incomplete);
          }
        }
      }
    }
    try (PreparedStatement p =
            prepare(
                "SELECT * FROM spawns s WHERE side=1 AND NOT EXISTS(SELECT 1 FROM results r WHERE"
                    + " r.b=s.id) ORDER BY id");
        ResultSet b = p.executeQuery()) {
      while (b.next()) {
        boolean ambiguous =
            number(
                    "SELECT count(*) FROM spawns WHERE side=0 AND target=? AND mnemonic=? AND"
                        + " matchkey=?",
                    b.getString("target"),
                    b.getString("mnemonic"),
                    b.getString("matchkey"))
                > 0;
        put(
            "INSERT INTO"
                + " results(a,b,target,mnemonic,output,finding,recipe_changed,input_changed,output_changed,reason,incomplete)"
                + " VALUES(NULL,?,?,?,?,?,0,0,0,?,1)",
            b.getLong("id"),
            b.getString("target"),
            b.getString("mnemonic"),
            b.getString("output"),
            ambiguous ? "INCONCLUSIVE" : "ADDED",
            ambiguous
                ? "Ambiguous counterpart; every observation is retained."
                : "Action or output set is present only in B.");
      }
    }
    propagation();
    sql("CREATE INDEX results_finding ON results(finding,id)");
  }

  private long sharedCandidate(int side, long id, String target, String mnemonic)
      throws SQLException {
    try (PreparedStatement p =
            prepare(
                "SELECT DISTINCT s.id FROM spawns s JOIN declared other ON other.side=s.side AND"
                    + " other.spawn=s.id JOIN declared current ON current.path=other.path AND"
                    + " current.side=? AND current.spawn=? WHERE s.side=? AND s.target=? AND"
                    + " s.mnemonic=? ORDER BY s.id LIMIT 2",
                side,
                id,
                1 - side,
                target,
                mnemonic);
        ResultSet r = p.executeQuery()) {
      if (!r.next()) {
        return 0;
      }
      long candidate = r.getLong(1);
      return r.next() ? 0 : candidate;
    }
  }

  private void result(
      ResultSet a,
      Long b,
      Finding finding,
      boolean recipe,
      boolean inputs,
      boolean outputs,
      String reason,
      boolean incomplete)
      throws SQLException, IOException {
    put(
        "INSERT INTO"
            + " results(a,b,target,mnemonic,output,finding,recipe_changed,input_changed,output_changed,reason,incomplete)"
            + " VALUES(?,?,?,?,?,?,?,?,?,?,?)",
        a.getLong("id"),
        b,
        a.getString("target"),
        a.getString("mnemonic"),
        a.getString("output"),
        finding.name(),
        recipe,
        inputs,
        outputs,
        reason,
        incomplete);
  }

  private void propagation() throws SQLException, IOException {
    // Link only exact changed output values, never a pathname-only guess at a producer.
    put(
        "INSERT OR IGNORE INTO producers SELECT o.side,f.path||' ['||f.kind||']',f.value,o.spawn"
            + " FROM outputs o JOIN files f ON f.side=o.side AND f.item=o.item WHERE f.known=1");
    try (PreparedStatement p =
            prepare(
                "SELECT id,a,b FROM results WHERE output_changed=1 AND a IS NOT NULL AND b IS NOT"
                    + " NULL");
        ResultSet r = p.executeQuery()) {
      while (r.next()) {
        fillDetails(0, r.getLong(2), "detail_a");
        fillDetails(1, r.getLong(3), "detail_b");
        put(
            "INSERT OR IGNORE INTO output_changes SELECT a.field,a.value,b.value,? FROM detail_a a"
                + " JOIN detail_b b ON a.section=b.section AND a.field=b.field AND"
                + " a.position=b.position WHERE a.section='Outputs' AND a.value<>b.value AND"
                + " (SELECT count(*) FROM producers p WHERE p.side=0 AND p.path=a.field AND"
                + " p.value=a.value)=1 AND (SELECT count(*) FROM producers p WHERE p.side=1 AND"
                + " p.path=b.field AND p.value=b.value)=1",
            r.getLong(1));
      }
    }
    try (PreparedStatement p =
            prepare(
                "SELECT id,a,b FROM results WHERE input_changed=1 AND recipe_changed=0 AND a IS NOT"
                    + " NULL AND b IS NOT NULL");
        ResultSet r = p.executeQuery()) {
      while (r.next()) {
        fillDetails(0, r.getLong(2), "detail_a");
        fillDetails(1, r.getLong(3), "detail_b");
        long supported =
            number(
                "SELECT count(*) FROM detail_a a JOIN detail_b b ON a.section=b.section AND"
                    + " a.field=b.field AND a.position=b.position WHERE a.section='Inputs' AND"
                    + " a.value<>b.value AND (SELECT count(*) FROM output_changes o WHERE"
                    + " o.path=a.field AND o.old_value=a.value AND o.new_value=b.value AND"
                    + " o.producer<>?)=1",
                r.getLong(1));
        if (supported > 0) {
          put(
              "UPDATE results SET finding='DOWNSTREAM',reason=? WHERE id=?",
              supported
                  + " changed generated inputs exactly match changed producer outputs; other"
                  + " differences may also exist.",
              r.getLong(1));
        }
      }
    }
  }

  private void fillDetails(int side, long id, String table) throws SQLException, IOException {
    sql("DELETE FROM " + table);
    put(
        "INSERT INTO "
            + table
            + " SELECT section,field,row_number() OVER(PARTITION BY section,field ORDER BY"
            + " value),value FROM recipe WHERE side=? AND spawn=?",
        side,
        id);
    sql("DELETE FROM manifest");
    try (PreparedStatement p =
            prepare("SELECT inputs,tools FROM spawns WHERE side=? AND id=?", side, id);
        ResultSet r = p.executeQuery()) {
      if (r.next()) {
        inputManifest(side, r.getLong(1), "Inputs");
        inputManifest(side, r.getLong(2), "Tools");
      }
    }
    outputManifest(side, id);
    sql(
        "INSERT INTO "
            + table
            + " SELECT section,path||' ['||kind||']',row_number() OVER(PARTITION BY"
            + " section,path,kind ORDER BY value),value FROM manifest");
  }

  @Override
  public synchronized Summary summary() throws IOException {
    check();
    try {
      List<String> notes = new ArrayList<>();
      try (PreparedStatement p = prepare("SELECT side,note FROM notes ORDER BY side,note");
          ResultSet r = p.executeQuery()) {
        while (r.next()) {
          notes.add((r.getInt(1) == 0 ? "A: " : "B: ") + r.getString(2));
        }
      }
      if (number("SELECT count(*) FROM spawns") == 0) {
        notes.add(
            "No spawns were recorded. An empty pair is not a successful reproducibility check.");
      }
      notes.add(
          "Only recorded spawn evidence is compared. Matching omits configuration hashes because"
              + " execution logs do not supply them.");
      notes.add(
          "Arguments, environment and platform values remain private; inspectors expose masked"
              + " change locations.");
      return new Summary(
          number("SELECT count(*) FROM spawns WHERE side=0"),
          number("SELECT count(*) FROM spawns WHERE side=1"),
          number("SELECT count(*) FROM results WHERE a IS NOT NULL AND b IS NOT NULL"),
          number("SELECT count(*) FROM results WHERE finding='UNCHANGED'"),
          number("SELECT count(*) FROM results WHERE finding='OUTPUT_DIVERGENCE'"),
          number(
              "SELECT count(*) FROM results WHERE finding IN"
                  + " ('RECIPE_DRIFT','INPUT_DRIFT','CACHE_IDENTITY_DRIFT')"),
          number("SELECT count(*) FROM results WHERE finding='DOWNSTREAM'"),
          number("SELECT count(*) FROM results WHERE incomplete=1"),
          notes);
    } catch (SQLException e) {
      throw failure(e);
    }
  }

  @Override
  public synchronized Page page(FilterExpression filter, long afterId, int limit)
      throws IOException {
    checkPage(limit);
    try {
      var predicate = ComparisonFilter.compile(filter);
      long total =
          number(
              "SELECT count(*) FROM results WHERE " + predicate.sql(),
              predicate.values().toArray());
      List<Object> bindings = new ArrayList<>(predicate.values());
      bindings.add(afterId);
      bindings.add(limit);
      List<Row> rows = new ArrayList<>();
      long characters = 0;
      try (PreparedStatement p =
              prepare(
                  "SELECT * FROM results WHERE "
                      + predicate.sql()
                      + " AND id>? ORDER BY id LIMIT ?",
                  bindings.toArray());
          ResultSet r = p.executeQuery()) {
        while (r.next()) {
          Row row = row(r);
          characters +=
              row.target().length()
                  + row.mnemonic().length()
                  + row.output().length()
                  + row.reason().length();
          checkCharacters(characters);
          rows.add(row);
        }
      }
      return new Page(rows, total);
    } catch (SQLException e) {
      throw failure(e);
    }
  }

  private static Row row(ResultSet r) throws SQLException, IOException {
    return new Row(
        r.getLong("id"),
        safePath(r.getString("target")),
        safePath(r.getString("mnemonic")),
        safePath(r.getString("output")),
        Finding.valueOf(r.getString("finding")),
        r.getBoolean("recipe_changed"),
        r.getBoolean("input_changed"),
        r.getBoolean("output_changed"),
        r.getString("reason"));
  }

  private static String diffQuery() {
    return "SELECT a.section,a.field,a.position,a.value before,b.value after FROM detail_a a LEFT"
        + " JOIN detail_b b ON a.section=b.section AND a.field=b.field AND"
        + " a.position=b.position WHERE a.value IS NOT b.value UNION ALL SELECT"
        + " b.section,b.field,b.position,NULL before,b.value after FROM detail_b b LEFT JOIN"
        + " detail_a a ON a.section=b.section AND a.field=b.field AND a.position=b.position"
        + " WHERE a.section IS NULL";
  }

  @Override
  public synchronized Details details(long id, long offset, int limit) throws IOException {
    checkPage(limit);
    if (offset < 0) {
      throw new IllegalArgumentException("Detail offset must not be negative.");
    }
    try {
      Row row;
      try (PreparedStatement p = prepare("SELECT * FROM results WHERE id=?", id);
          ResultSet r = p.executeQuery()) {
        if (!r.next()) {
          throw new IOException("Comparison row no longer exists.");
        }
        row = row(r);
        sql("DELETE FROM detail_a");
        sql("DELETE FROM detail_b");
        long a = r.getLong("a");
        if (!r.wasNull()) {
          fillDetails(0, a, "detail_a");
        }
        long b = r.getLong("b");
        if (!r.wasNull()) {
          fillDetails(1, b, "detail_b");
        }
      }
      sql("DELETE FROM differences");
      sql("INSERT INTO differences " + diffQuery());
      long total = number("SELECT count(*) FROM differences");
      List<FieldDifference> differences = new ArrayList<>();
      long characters = 0;
      try (PreparedStatement p =
              prepare(
                  "SELECT * FROM differences ORDER BY section,field,position LIMIT ? OFFSET ?",
                  limit,
                  offset);
          ResultSet r = p.executeQuery()) {
        while (r.next()) {
          String section = r.getString("section");
          FieldDifference difference =
              new FieldDifference(
                  section,
                  safePath(r.getString("field")),
                  displayValue(section, r.getString("before")),
                  displayValue(section, r.getString("after")));
          characters +=
              difference.section().length()
                  + difference.field().length()
                  + difference.before().length()
                  + difference.after().length();
          checkCharacters(characters);
          differences.add(difference);
        }
      }
      return new Details(row, differences, total, offset + differences.size() < total);
    } catch (SQLException e) {
      throw failure(e);
    }
  }

  private static String displayValue(String section, String value) throws IOException {
    if (value == null) {
      return "[not recorded]";
    }
    return section.equals("Arguments")
            || section.equals("Environment")
            || section.equals("Platform")
            || section.equals("Evidence")
        ? "[private value; changed]"
        : safePath(value);
  }

  private static String safePath(String value) throws IOException {
    if (value.length() > ComparisonLimits.MAX_CELL_CHARACTERS) {
      throw new IOException(
          "Comparison text exceeds the cell character limit ("
              + ComparisonLimits.MAX_CELL_CHARACTERS
              + "); no partial page was returned.");
    }
    return value.replaceAll("(/Users/|/home/)[^/\\s]+", "$1[user]");
  }

  private static void checkCharacters(long characters) throws IOException {
    if (characters > ComparisonLimits.MAX_PAGE_CHARACTERS) {
      throw new IOException(
          "Comparison page exceeds the character limit ("
              + ComparisonLimits.MAX_PAGE_CHARACTERS
              + "); request fewer rows.");
    }
  }

  private void checkPage(int limit) throws IOException {
    check();
    if (limit < 1 || limit > limits.pageRows()) {
      throw new IllegalArgumentException(
          "Comparison pages support 1 to " + limits.pageRows() + " rows.");
    }
  }

  private static IOException failure(SQLException e) {
    return new IOException(
        "Comparison query stopped: data, cancellation or SQLite disk/work limit. No partial page"
            + " was returned.",
        e);
  }

  List<String> queryPlan(String query) throws SQLException {
    List<String> plan = new ArrayList<>();
    try (PreparedStatement p = prepare("EXPLAIN QUERY PLAN " + query);
        ResultSet r = p.executeQuery()) {
      while (r.next()) {
        plan.add(r.getString(4));
      }
    }
    return List.copyOf(plan);
  }

  @Override
  public synchronized void close() throws IOException {
    if (!closed) {
      closed = true;
      IOException problem = null;
      try {
        connection.close();
      } catch (SQLException e) {
        problem = failure(e);
      }
      try {
        deleteOwned(directory);
      } catch (IOException cleanup) {
        if (problem == null) problem = cleanup;
        else problem.addSuppressed(cleanup);
      }
      if (problem != null) throw problem;
    }
  }

  private static void deleteOwned(Path directory) throws IOException {
    if (!Files.exists(directory)) {
      return;
    }
    // These fixed files are the only things this service creates. Never recursively delete input
    // paths.
    for (String name : List.of("comparison.db", "comparison.db-journal", "a.log", "b.log")) {
      Files.deleteIfExists(directory.resolve(name));
    }
    Files.delete(directory);
  }

  private static void cleanupAfterFailure(Path owned, Throwable failure) {
    try {
      deleteOwned(owned);
    } catch (IOException cleanup) {
      failure.addSuppressed(cleanup);
    }
  }
}
