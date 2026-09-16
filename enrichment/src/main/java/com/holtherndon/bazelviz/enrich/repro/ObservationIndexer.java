package com.holtherndon.bazelviz.enrich.repro;

import com.google.devtools.build.lib.exec.Protos.Digest;
import com.google.devtools.build.lib.exec.Protos.ExecLogEntry;
import com.google.devtools.build.lib.exec.Protos.File;
import com.google.devtools.build.lib.exec.Protos.Platform;
import com.google.devtools.build.lib.exec.Protos.SpawnExec;
import com.google.protobuf.Message;
import io.airlift.compress.zstd.ZstdInputStream;
import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;

/**
 * Loss-aware, framed import into a private index. At most one bounded protobuf is held at a time.
 */
final class ObservationIndexer {
  private final ExecutionLogComparison db;
  private final int side;
  private long records;
  private long expanded;
  private long synthetic;
  private String algorithm = "";
  private boolean compactFormat;
  private boolean invocation;
  private String invocationId = "";

  long records() {
    return records;
  }

  String invocationId() {
    return invocationId;
  }

  boolean compactFormat() {
    return compactFormat;
  }

  boolean invocationHeaderPresent() {
    return invocation;
  }

  ObservationIndexer(ExecutionLogComparison db, int side) {
    this.db = db;
    this.side = side;
  }

  void read(Path path) throws IOException, SQLException {
    if (Files.size(path) > db.limits.sourceBytes()) {
      throw new IOException(
          "Execution log exceeds the source byte limit (" + db.limits.sourceBytes() + ").");
    }
    try (PushbackInputStream raw =
        new PushbackInputStream(new BufferedInputStream(Files.newInputStream(path)), 4)) {
      byte[] head = raw.readNBytes(4);
      raw.unread(head);
      compactFormat =
          head.length == 4
              && head[0] == 0x28
              && head[1] == (byte) 0xb5
              && head[2] == 0x2f
              && head[3] == (byte) 0xfd;
      if (head.length == 0) {
        db.note(side, "Empty log: cached work and missing capture cannot be distinguished.");
        return;
      }
      if (compactFormat) {
        ZstdFrameGuard.verify(path, db::cancelled);
      }
      // Every first byte, including '{' and whitespace, can be a valid protobuf length varint.
      // Only zstd magic establishes a format. Other files must pass bounded protobuf decoding.
      try (InputStream stream = compactFormat ? new ZstdInputStream(raw) : raw) {
        byte[] bytes;
        while ((bytes = frame(stream)) != null) {
          db.check();
          if (++records > db.limits.records()) {
            throw new IOException(
                "Execution log exceeds the record limit (" + db.limits.records() + ").");
          }
          if (compactFormat) {
            compact(ExecLogEntry.parseFrom(bytes));
          } else {
            binary(SpawnExec.parseFrom(bytes));
          }
        }
      }
      if (compactFormat && !invocation) {
        db.note(side, "Compact invocation header is missing; digest algorithms may be unknown.");
      } else if (compactFormat && invocationId.isEmpty()) {
        db.note(
            side,
            "The compact invocation header has no embedded invocation ID; it cannot independently"
                + " identify the build that produced this log.");
      }
      db.note(
          side,
          "Non-spawn actions and persistent action-cache hits are outside spawn comparison"
              + " coverage.");
      db.note(
          side,
          "Execution logs do not identify configured targets or record undeclared"
              + " filesystem/network access.");
    }
  }

  private byte[] frame(InputStream in) throws IOException {
    int first = in.read();
    if (first == -1) {
      return null;
    }
    long length = first & 127;
    int current = first;
    int shift = 7;
    int prefix = 1;
    while ((current & 128) != 0) {
      if (shift >= 35) {
        throw new IOException("Malformed execution-log record length.");
      }
      current = in.read();
      if (current == -1) {
        throw new EOFException("Truncated execution-log record length.");
      }
      length |= (long) (current & 127) << shift;
      shift += 7;
      prefix++;
    }
    if (length == 0 || length > db.limits.recordBytes()) {
      throw new IOException(
          "Execution-log record is empty or exceeds the record byte limit ("
              + db.limits.recordBytes()
              + ").");
    }
    expanded += length + prefix;
    if (expanded > db.limits.expandedBytes()) {
      throw new IOException(
          "Execution log exceeds the expanded byte limit (" + db.limits.expandedBytes() + ").");
    }
    byte[] bytes = in.readNBytes((int) length);
    if (bytes.length != length) {
      throw new EOFException("Truncated execution-log record.");
    }
    return bytes;
  }

  private void compact(ExecLogEntry e) throws SQLException, IOException {
    long id = Integer.toUnsignedLong(e.getId());
    if (id != 0) {
      db.put("INSERT INTO entry_ids VALUES(?,?)", side, id);
    }
    if (unknown(e)) {
      db.note(side, "Unknown compact fields were encountered; source coverage is incomplete.");
      db.incomplete(side);
    }
    switch (e.getTypeCase()) {
      case INVOCATION -> {
        if (invocation || records != 1) {
          throw new IOException("Compact invocation header is duplicated or out of order.");
        }
        invocation = true;
        invocationId = e.getInvocation().getId();
        algorithm = e.getInvocation().getHashFunctionName();
        if (unknown(e.getInvocation())) {
          db.incomplete(side);
        }
      }
      case FILE -> {
        ExecLogEntry.File f = e.getFile();
        item(id, "FILE", f.getPath());
        file(id, f.getPath(), "FILE", f.hasDigest() ? f.getDigest() : null, "", unknown(f));
      }
      case DIRECTORY -> {
        ExecLogEntry.Directory d = e.getDirectory();
        item(id, "DIRECTORY", d.getPath());
        db.put(
            "INSERT INTO files VALUES(?,?,?,?,?,?,?)",
            side,
            id,
            d.getPath(),
            "DIRECTORY",
            "",
            !unknown(d),
            1);
        for (ExecLogEntry.File f : d.getFilesList()) {
          if (f.getPath().startsWith("/") || List.of(f.getPath().split("/")).contains("..")) {
            throw new IOException("A directory member has an invalid relative path.");
          }
          file(
              id,
              d.getPath() + "/" + f.getPath(),
              "FILE",
              f.hasDigest() ? f.getDigest() : null,
              "",
              unknown(f));
        }
      }
      case UNRESOLVED_SYMLINK -> {
        var s = e.getUnresolvedSymlink();
        item(id, "SYMLINK", s.getPath());
        file(id, s.getPath(), "SYMLINK", null, s.getTargetPath(), unknown(s));
      }
      case INPUT_SET -> {
        item(id, "SET", "");
        var set = e.getInputSet();
        if (unknown(set)) {
          db.put("UPDATE items SET kind='UNSUPPORTED' WHERE side=? AND id=?", side, id);
        }
        for (int child : set.getTransitiveSetIdsList()) {
          if (!db.isSet(side, Integer.toUnsignedLong(child)) || child == 0) {
            db.incomplete(side);
            db.note(side, "An input-set reference has a missing or invalid set kind.");
          }
          edge(id, Integer.toUnsignedLong(child));
        }
        for (int child : set.getInputIdsList()) {
          if (db.isSet(side, Integer.toUnsignedLong(child))) {
            db.incomplete(side);
            db.note(side, "An input artifact reference incorrectly points to an input set.");
          }
          edge(id, Integer.toUnsignedLong(child));
        }
      }
      case RUNFILES_TREE -> {
        item(id, "UNSUPPORTED", e.getRunfilesTree().getPath());
        db.note(
            side,
            "Runfiles overlays are not reconstructed yet; affected actions are inconclusive.");
      }
      case SYMLINK_ENTRY_SET -> item(id, "UNSUPPORTED", "");
      case SYMLINK_ACTION ->
          db.note(
              side,
              "Non-spawn symlink actions were recorded but are not independently executed spawns.");
      case TYPE_NOT_SET -> {
        db.note(side, "An unrecognized compact entry prevents complete coverage.");
        db.incomplete(side);
      }
      case SPAWN -> {
        var s = e.getSpawn();
        long spawn = records;
        spawn(
            spawn,
            s.getTargetLabel(),
            s.getMnemonic(),
            s.getRunner(),
            s.getCacheHit(),
            s.getExitCode(),
            s.getStatus(),
            s.getArgsList(),
            s.hasPlatform() ? s.getPlatform() : null,
            s.getRemotable(),
            s.getCacheable(),
            s.getRemoteCacheable(),
            s.getTimeoutMillis(),
            Integer.toUnsignedLong(s.getInputSetId()),
            Integer.toUnsignedLong(s.getToolSetId()),
            unknown(s));
        actionDigest(spawn, s.hasDigest() ? s.getDigest() : null);
        for (var v : s.getEnvVarsList()) {
          recipe(spawn, "Environment", v.getName(), v.getValue());
          if (unknown(v)) {
            mark(spawn, "Unknown environment fields.");
          }
        }
        for (var output : s.getOutputsList()) {
          switch (output.getTypeCase()) {
            case OUTPUT_ID -> {
              long outputId = Integer.toUnsignedLong(output.getOutputId());
              db.put("INSERT INTO outputs VALUES(?,?,?,NULL)", side, spawn, outputId);
              db.put(
                  "INSERT INTO declared SELECT side,?,path FROM items WHERE side=? AND id=?",
                  spawn,
                  side,
                  outputId);
              if (!db.isArtifact(side, outputId)) {
                mark(spawn, "Missing or invalid output reference.");
              }
            }
            case INVALID_OUTPUT_PATH -> {
              db.put(
                  "INSERT INTO outputs VALUES(?,?,NULL,?)",
                  side,
                  spawn,
                  output.getInvalidOutputPath());
              db.put(
                  "INSERT INTO declared VALUES(?,?,?)", side, spawn, output.getInvalidOutputPath());
            }
            case TYPE_NOT_SET -> mark(spawn, "Unrecognized output reference.");
          }
          if (unknown(output)) {
            mark(spawn, "Unknown output fields.");
          }
        }
      }
    }
  }

  private void binary(SpawnExec s) throws SQLException, IOException {
    long spawn = records;
    long inputs = ++synthetic;
    item(inputs, "SET", "");
    long tools = ++synthetic;
    item(tools, "SET", "");
    for (File f : s.getInputsList()) {
      long id = ++synthetic;
      binaryFile(id, f);
      edge(inputs, id);
      if (f.getIsTool()) {
        edge(tools, id);
      }
    }
    spawn(
        spawn,
        s.getTargetLabel(),
        s.getMnemonic(),
        s.getRunner(),
        s.getCacheHit(),
        s.getExitCode(),
        s.getStatus(),
        s.getCommandArgsList(),
        s.hasPlatform() ? s.getPlatform() : null,
        s.getRemotable(),
        s.getCacheable(),
        s.getRemoteCacheable(),
        s.getTimeoutMillis(),
        inputs,
        tools,
        unknown(s));
    actionDigest(spawn, s.hasDigest() ? s.getDigest() : null);
    for (var v : s.getEnvironmentVariablesList()) {
      recipe(spawn, "Environment", v.getName(), v.getValue());
      if (unknown(v)) {
        mark(spawn, "Unknown environment fields.");
      }
    }
    for (String output : s.getListedOutputsList()) {
      db.put("INSERT INTO declared VALUES(?,?,?)", side, spawn, output);
    }
    for (File f : s.getActualOutputsList()) {
      long id = ++synthetic;
      binaryFile(id, f);
      db.put("INSERT INTO outputs VALUES(?,?,?,NULL)", side, spawn, id);
    }
    // Binary logs flatten directory outputs. Without a directory node, an empty tree is unknown.
    if (db.hasTreeOutputs(side, spawn)) {
      mark(
          spawn,
          "Binary tree output kinds and empty directories cannot be reconstructed completely.");
      db.note(
          side,
          "Binary logs flatten tree outputs; directory kinds and empty directories have incomplete"
              + " coverage.");
    }
    db.put(
        "INSERT INTO outputs SELECT side,spawn,NULL,path FROM declared d WHERE side=? AND spawn=?"
            + " AND NOT EXISTS (SELECT 1 FROM outputs o JOIN items i ON i.side=o.side AND"
            + " i.id=o.item WHERE o.side=d.side AND o.spawn=d.spawn AND (i.path=d.path OR"
            + " substr(i.path,1,length(d.path)+1)=d.path||'/'))",
        side,
        spawn);
  }

  private void binaryFile(long id, File f) throws SQLException, IOException {
    String kind = f.getSymlinkTargetPath().isEmpty() ? "FILE" : "SYMLINK";
    item(id, kind, f.getPath());
    file(
        id,
        f.getPath(),
        kind,
        f.hasDigest() ? f.getDigest() : null,
        f.getSymlinkTargetPath(),
        unknown(f));
  }

  private void spawn(
      long id,
      String label,
      String mnemonic,
      String runner,
      boolean cache,
      int exit,
      String status,
      List<String> args,
      Platform platform,
      boolean remotable,
      boolean cacheable,
      boolean remoteCacheable,
      long timeout,
      long inputs,
      long tools,
      boolean unknown)
      throws SQLException, IOException {
    if (timeout < 0) {
      throw new IOException("Execution-log timeout is negative.");
    }
    boolean eligible =
        !cache
            && !runner.isEmpty()
            && !runner.contains("cache hit")
            && exit == 0
            && status.isEmpty();
    String reason =
        cache || runner.contains("cache hit")
            ? "A cache hit did not independently execute."
            : !eligible ? "Execution failed or its runner is unavailable." : "";
    db.put(
        "INSERT INTO"
            + " spawns(side,id,target,mnemonic,runner,cache_hit,eligible,reason,inputs,tools,incomplete)"
            + " VALUES(?,?,?,?,?,?,?,?,?,?,?)",
        side,
        id,
        label,
        mnemonic,
        runner,
        cache,
        eligible,
        reason,
        inputs,
        tools,
        unknown || label.isEmpty() || args.isEmpty());
    if (!db.isSet(side, inputs) || !db.isSet(side, tools)) {
      mark(id, "A spawn input/tool set is missing, forward-referenced or has an invalid kind.");
    }
    for (int i = 0; i < args.size(); i++) {
      recipe(id, "Arguments", String.format(Locale.ROOT, "%09d", i), args.get(i));
    }
    recipe(id, "Policy", "remotable", Boolean.toString(remotable));
    recipe(id, "Policy", "cacheable", Boolean.toString(cacheable));
    recipe(id, "Policy", "remote_cacheable", Boolean.toString(remoteCacheable));
    recipe(id, "Policy", "timeout_millis", Long.toString(timeout));
    if (platform == null) {
      db.note(
          side,
          "Execution-platform properties were not reported for some spawns; host equivalence is not"
              + " established.");
      recipe(id, "Platform", "[availability]", "unreported");
      mark(id, "Execution platform was not reported.");
    } else {
      recipe(id, "Platform", "[availability]", "reported");
      for (var p : platform.getPropertiesList()) {
        recipe(id, "Platform", p.getName(), p.getValue());
        if (unknown(p)) {
          mark(id, "Unknown platform fields.");
        }
      }
      if (unknown(platform)) {
        mark(id, "Unknown platform fields.");
      }
    }
  }

  private void recipe(long id, String section, String key, String value)
      throws SQLException, IOException {
    db.put("INSERT INTO recipe VALUES(?,?,?,?,?)", side, id, section, key, value);
    if (value.contains("[redacted") || value.contains("<withheld>")) {
      mark(id, "Source contains redacted or withheld values.");
    }
  }

  private void item(long id, String kind, String path) throws SQLException, IOException {
    if (id == 0) {
      throw new IOException("Referenced compact entry has no ID.");
    }
    db.put("INSERT INTO items VALUES(?,?,?,?)", side, id, kind, path);
  }

  private void edge(long parent, long child) throws SQLException, IOException {
    if (!db.hasItem(side, child)) {
      db.note(side, "Missing or forward compact references prevent complete coverage.");
      db.incomplete(side);
    }
    db.put("INSERT OR IGNORE INTO edges VALUES(?,?,?)", side, parent, child);
  }

  private void actionDigest(long spawn, Digest digest) throws SQLException, IOException {
    if (digest == null || digest.getHash().isEmpty()) {
      db.note(
          side,
          "Action cache digests are not reported for every spawn; absent keys are not compared.");
      return;
    }
    String algo = digest.getHashFunctionName().isEmpty() ? algorithm : digest.getHashFunctionName();
    if (digest.getSizeBytes() < 0) {
      throw new IOException("Execution-log action digest size is negative.");
    }
    if (algo.isEmpty() || unknown(digest)) {
      db.note(side, "An action cache digest has unknown format or algorithm.");
      return;
    }
    recipe(
        spawn,
        "Evidence",
        "Action cache digest",
        algo + ":" + digest.getHash() + ":" + digest.getSizeBytes());
  }

  private void file(
      long id, String path, String kind, Digest digest, String target, boolean unknown)
      throws SQLException, IOException {
    if (digest != null && digest.getSizeBytes() < 0) {
      throw new IOException("Execution-log digest size is negative.");
    }
    String algo = digest == null ? "" : digest.getHashFunctionName();
    if (algo.isEmpty()) {
      algo = algorithm;
    }
    boolean known =
        !unknown
            && (kind.equals("SYMLINK")
                ? !target.isEmpty()
                : digest != null
                    && !digest.getHash().isEmpty()
                    && !algo.isEmpty()
                    && !unknown(digest));
    if (!known) {
      db.note(
          side,
          "Some file observations have missing digests, unknown algorithms or unsupported fields;"
              + " affected actions are inconclusive.");
    }
    String value =
        kind.equals("SYMLINK")
            ? target
            : digest == null
                ? "[digest unavailable]"
                : algo + ":" + digest.getHash() + ":" + digest.getSizeBytes();
    db.put("INSERT INTO files VALUES(?,?,?,?,?,?,?)", side, id, path, kind, value, known, 1);
  }

  private void mark(long id, String reason) throws SQLException, IOException {
    db.put("UPDATE spawns SET incomplete=1,reason=? WHERE side=? AND id=?", reason, side, id);
  }

  private static boolean unknown(Message m) {
    return !m.getUnknownFields().asMap().isEmpty();
  }
}
