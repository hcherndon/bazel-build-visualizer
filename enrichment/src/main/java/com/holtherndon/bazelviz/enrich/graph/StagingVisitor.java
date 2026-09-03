package com.holtherndon.bazelviz.enrich.graph;

import com.google.devtools.build.lib.analysis.AnalysisProtosV2.Action;
import com.google.devtools.build.lib.analysis.AnalysisProtosV2.Artifact;
import com.google.devtools.build.lib.analysis.AnalysisProtosV2.Configuration;
import com.google.devtools.build.lib.analysis.AnalysisProtosV2.DepSetOfFiles;
import com.google.devtools.build.lib.analysis.AnalysisProtosV2.PathFragment;
import com.google.devtools.build.lib.analysis.AnalysisProtosV2.RuleClass;
import com.google.devtools.build.lib.analysis.AnalysisProtosV2.Target;
import com.holtherndon.bazelviz.storage.graph.GraphStaging;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes every entity to the staging tables exactly as the file carried it.
 *
 * <p>Resolves nothing, on purpose: the file names entities before declaring them on three of four
 * supported versions (finding Q4), so a resolution here would be made against an incomplete picture
 * and would quietly produce a graph slightly smaller than the truth.
 *
 * <p>Everything is batched. The one thing held in memory is the list of configuration checksums,
 * which is bounded by the number of configurations — two in the probe, and a few hundred in the
 * worst realistic case — rather than by the size of the graph.
 */
final class StagingVisitor implements ActionGraphVisitor {

  /**
   * Rows per JDBC batch.
   *
   * <p>The driver holds a batched statement's parameters until execution, so an unbounded batch is
   * an unbounded allocation. Same reasoning and same size as the profile writer's.
   */
  private static final int BATCH = 5_000;

  private final PreparedStatement fragment;
  private final PreparedStatement artifact;
  private final PreparedStatement target;
  private final PreparedStatement ruleClass;
  private final PreparedStatement config;
  private final PreparedStatement depset;
  private final PreparedStatement depsetChild;
  private final PreparedStatement depsetArtifact;
  private final PreparedStatement action;
  private final PreparedStatement actionInput;
  private final PreparedStatement actionOutput;

  private final List<PreparedStatement> all = new ArrayList<>();
  private final List<String> configurationChecksums = new ArrayList<>();
  private int pending;
  private long actionOrdinal;

  StagingVisitor(GraphStaging staging) throws SQLException {
    fragment =
        track(
            staging.insert(
                "INSERT OR REPLACE INTO stage_fragment (id, label, parent) VALUES (?, ?, ?)"));
    artifact =
        track(
            staging.insert(
                "INSERT OR REPLACE INTO stage_artifact (id, fragment, is_tree) VALUES (?, ?, ?)"));
    target =
        track(
            staging.insert(
                "INSERT OR REPLACE INTO stage_target (id, label, rule_class_id) VALUES (?, ?, ?)"));
    ruleClass =
        track(staging.insert("INSERT OR REPLACE INTO stage_rule_class (id, name) VALUES (?, ?)"));
    config =
        track(
            staging.insert(
                "INSERT OR REPLACE INTO stage_config (id, checksum, mnemonic, platform)"
                    + " VALUES (?, ?, ?, ?)"));
    depset = track(staging.insert("INSERT OR REPLACE INTO stage_depset (id) VALUES (?)"));
    depsetChild =
        track(
            staging.insert(
                "INSERT OR IGNORE INTO stage_depset_child (parent, child) VALUES (?, ?)"));
    depsetArtifact =
        track(
            staging.insert(
                "INSERT OR IGNORE INTO stage_depset_artifact (depset, artifact) VALUES (?, ?)"));
    action =
        track(
            staging.insert(
                "INSERT OR REPLACE INTO stage_action (ordinal, target_id, mnemonic, config_id,"
                    + " primary_output, execution_platform, action_key, discovers_inputs,"
                    + " is_executable) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"));
    actionInput =
        track(
            staging.insert(
                "INSERT OR IGNORE INTO stage_action_input (ordinal, depset) VALUES (?, ?)"));
    actionOutput =
        track(
            staging.insert(
                "INSERT OR IGNORE INTO stage_action_output (ordinal, artifact) VALUES (?, ?)"));
  }

  private PreparedStatement track(PreparedStatement statement) {
    all.add(statement);
    return statement;
  }

  @Override
  public void artifact(Artifact value) {
    run(
        () -> {
          artifact.setLong(1, value.getId());
          artifact.setLong(2, value.getPathFragmentId());
          artifact.setInt(3, value.getIsTreeArtifact() ? 1 : 0);
          artifact.addBatch();
        });
  }

  @Override
  public void action(Action value) {
    long ordinal = actionOrdinal++;
    run(
        () -> {
          action.setLong(1, ordinal);
          action.setLong(2, value.getTargetId());
          action.setString(3, value.getMnemonic());
          action.setLong(4, value.getConfigurationId());
          action.setLong(5, value.getPrimaryOutputId());
          action.setString(6, value.getExecutionPlatform());
          action.setString(7, value.getActionKey());
          action.setInt(8, value.getDiscoversInputs() ? 1 : 0);
          // True, or unknown. Never false.
          //
          // Bazel 6.5.0 emits no is_executable at all, verified against the
          // same two FileWrite actions 7.6.1 marks executable (Q9). But the
          // field is a proto3 bool without explicit presence, so a parsed
          // message cannot tell "this version never says" from "this action
          // is not executable" -- the encoding erased the difference before
          // the parser saw it, and hasField() on such a field throws rather
          // than answering.
          //
          // So only the positive is recorded. `1` means Bazel said executable;
          // NULL means it did not say so, which on 6.5.0 is every action and
          // elsewhere is most of them. A stored 0 would claim a distinction
          // this data does not carry.
          if (value.getIsExecutable()) {
            action.setInt(9, 1);
          } else {
            action.setNull(9, Types.INTEGER);
          }
          action.addBatch();

          for (int depsetId : value.getInputDepSetIdsList()) {
            actionInput.setLong(1, ordinal);
            actionInput.setLong(2, depsetId);
            actionInput.addBatch();
          }
          for (int outputId : value.getOutputIdsList()) {
            actionOutput.setLong(1, ordinal);
            actionOutput.setLong(2, outputId);
            actionOutput.addBatch();
          }
        });
  }

  @Override
  public void target(Target value) {
    run(
        () -> {
          target.setLong(1, value.getId());
          target.setString(2, value.getLabel());
          target.setLong(3, value.getRuleClassId());
          target.addBatch();
        });
  }

  @Override
  public void depSet(DepSetOfFiles value) {
    run(
        () -> {
          depset.setLong(1, value.getId());
          depset.addBatch();
          for (int child : value.getTransitiveDepSetIdsList()) {
            depsetChild.setLong(1, value.getId());
            depsetChild.setLong(2, child);
            depsetChild.addBatch();
          }
          for (int artifactId : value.getDirectArtifactIdsList()) {
            depsetArtifact.setLong(1, value.getId());
            depsetArtifact.setLong(2, artifactId);
            depsetArtifact.addBatch();
          }
        });
  }

  @Override
  public void configuration(Configuration value) {
    // The checksum is the only id that crosses out of this file: aquery's
    // own configuration ids are small integers valid nowhere else, and the
    // checksum equals the BEP's configuration id (Q6).
    if (!value.getChecksum().isEmpty()) {
      configurationChecksums.add(value.getChecksum());
    }
    run(
        () -> {
          config.setLong(1, value.getId());
          config.setString(2, value.getChecksum());
          config.setString(3, value.getMnemonic());
          config.setString(4, value.getPlatformName());
          config.addBatch();
        });
  }

  @Override
  public void ruleClass(RuleClass value) {
    run(
        () -> {
          ruleClass.setLong(1, value.getId());
          ruleClass.setString(2, value.getName());
          ruleClass.addBatch();
        });
  }

  @Override
  public void pathFragment(PathFragment value) {
    run(
        () -> {
          fragment.setLong(1, value.getId());
          fragment.setString(2, value.getLabel());
          // Absent means root. proto3 omits a zero, and no fragment has id 0.
          fragment.setLong(3, value.getParentId());
          fragment.addBatch();
        });
  }

  /** Every configuration checksum the query reported, in order. */
  List<String> configurationChecksums() {
    return List.copyOf(configurationChecksums);
  }

  /** Executes whatever is left in the batches. */
  void flush() throws SQLException {
    for (PreparedStatement statement : all) {
      statement.executeBatch();
    }
    pending = 0;
  }

  private void run(SqlAction work) {
    try {
      work.run();
      if (++pending >= BATCH) {
        flush();
      }
    } catch (SQLException failure) {
      throw new StagingException(failure);
    }
  }

  @FunctionalInterface
  private interface SqlAction {
    void run() throws SQLException;
  }

  /** Carries a {@link SQLException} out of a visitor method. */
  static final class StagingException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    StagingException(SQLException cause) {
      super(cause.getMessage(), cause);
    }
  }
}
