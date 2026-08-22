package com.holtherndon.bazelviz.core.id;

import java.util.Objects;

/**
 * A logical action, identified by its primary output path (plan 11.1, 11.2).
 *
 * <h2>Why the path, and not the label</h2>
 *
 * <p>Measured across Bazel 6.5.0, 7.6.1, 8.4.1 and 9.2.0:
 * {@code id.actionCompleted.primaryOutput} is unique across an entire event
 * stream in every run, while {@code (label, configuration)} collides heavily —
 * one target legitimately emits many actions, and a single test target was
 * observed producing six (FileWrite, SourceSymlinkManifest,
 * RepoMappingManifest, SymlinkTree, RunfilesTree, TestRunner). A label-keyed
 * action table merges all of those into one row.
 *
 * <p>The path works as an identity because it is already
 * configuration-qualified: its second segment is the configuration mnemonic, so
 * the same label built for the target and for the exec platform produces two
 * different primary outputs. The flip side is that it is useless for comparing
 * <em>across</em> builds — a {@code dbg} action and an {@code opt} action for
 * the same label have different paths — which is a Phase 10 problem and is
 * noted here so nobody reaches for it as a cross-session key.
 *
 * <h2>Read it from the id, never from the payload</h2>
 *
 * <p>There are two fields called "primary output" and they behave differently.
 * The proto's comment "only provided for successful actions" is attached to the
 * <em>payload</em> field {@code ActionExecuted.primary_output}; the
 * <em>identifier</em> field {@code ActionCompletedId.primary_output} is present
 * on every action event, including failures. A normalizer that keyed on the
 * payload would lose the identity of every failed action — precisely the rows a
 * build-failure view exists to show. Measured: failed actions carry the id
 * field and omit the payload one, and on Bazel 9.2 a <em>successful</em>
 * {@code RunfilesTree} action omits the payload one too, so its presence is not
 * even a reliable proxy for success.
 *
 * @param primaryOutput the exec-root-relative path from
 *     {@code ActionCompletedId.primary_output}
 */
public record ActionId(String primaryOutput) {

    public ActionId {
        Objects.requireNonNull(primaryOutput, "primaryOutput");
        if (primaryOutput.isEmpty()) {
            throw new IllegalArgumentException("an action's primary output path must not be empty");
        }
    }

    /** The action a {@code ActionCompletedId} names. */
    public static ActionId ofEventId(String primaryOutputFromId) {
        return new ActionId(primaryOutputFromId);
    }

    public String storageKey() {
        return primaryOutput;
    }

    /** The output's file name, for a column too narrow for the path. */
    public String outputFileName() {
        int slash = primaryOutput.lastIndexOf('/');
        return slash < 0 ? primaryOutput : primaryOutput.substring(slash + 1);
    }

    @Override
    public String toString() {
        return primaryOutput;
    }
}
