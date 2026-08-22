package com.holtherndon.bazelviz.core.enrich;

import java.util.Optional;

/**
 * The three execution-log formats Bazel can write, and what each one can tell
 * us.
 *
 * <p>They are mutually exclusive from Bazel 7 on — passing two is a
 * command-line error that fails the build before analysis (X2) — so the
 * instrumentation planner picks exactly one and there is no fallback file.
 */
public enum ExecLogFormat {

    /**
     * The compact format: zstd-compressed {@code ExecLogEntry} records that
     * reference each other by id.
     *
     * <p>Preferred wherever it exists. Measured 789 bytes against the binary
     * format's 2,754 and the JSON format's 7,055 for the same build (S4), and
     * it is the only format carrying an invocation header, which is what lets
     * a log be verified as belonging to its session (V2).
     *
     * <p>Does not exist on Bazel 6.5.0 (X1).
     */
    COMPACT("execution_log_compact_file", true, true),

    /**
     * Length-delimited {@code SpawnExec} messages, uncompressed.
     *
     * <p>The only option on 6.5.0. Carries no invocation header, so a binary
     * log cannot be checked against the session it claims to describe (V3).
     */
    BINARY("execution_log_binary_file", false, false),

    /**
     * Concatenated JSON objects.
     *
     * <p>Offered for interoperability, not for analysis: 8.9x the size of the
     * compact format for identical content, and no invocation header.
     */
    JSON("execution_log_json_file", false, false);

    private final String flagName;
    private final boolean compressed;
    private final boolean carriesInvocationId;

    ExecLogFormat(String flagName, boolean compressed, boolean carriesInvocationId) {
        this.flagName = flagName;
        this.compressed = compressed;
        this.carriesInvocationId = carriesInvocationId;
    }

    /** The Bazel option that writes this format, without leading dashes. */
    public String flagName() {
        return flagName;
    }

    /** True when the file is a zstd frame rather than raw protobuf. */
    public boolean isCompressed() {
        return compressed;
    }

    /**
     * True when the format begins with an invocation header carrying the build
     * id, so the file can be proven to belong to this session.
     */
    public boolean carriesInvocationId() {
        return carriesInvocationId;
    }

    /**
     * Why a log in this format cannot be verified against its session, or empty
     * when it can.
     */
    public Optional<String> unverifiableReason() {
        return carriesInvocationId
                ? Optional.empty()
                : Optional.of("the " + name().toLowerCase(java.util.Locale.ROOT)
                        + " execution log carries no invocation id, so it cannot be"
                        + " checked against this session's build");
    }
}
