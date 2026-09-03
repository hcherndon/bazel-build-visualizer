package com.holtherndon.bazelviz.core.source;

/**
 * Where a piece of information came from (plan section 11.5).
 *
 * <p>Every derived value carries its provenance so the inspector can show it,
 * and so conflicting measurements from different sources are kept apart rather
 * than collapsed into one unexplained number (ADR-009).
 */
public enum DataSource {
    /** A BEP {@code BuildEvent} payload. */
    BEP,

    /** The BES envelope around a BEP payload — sequence numbers, stream identity. */
    BES_ENVELOPE,

    /** Bazel's execution log. */
    EXECUTION_LOG,

    /** Bazel's JSON trace profile. */
    PROFILE,

    /** Bazel's pprof CPU profile of Starlark execution. */
    STARLARK_CPU_PROFILE,

    /** {@code bazel aquery} output — the declared action graph. */
    AQUERY,

    /** {@code bazel cquery} output — the configured-target graph. */
    CQUERY,

    /** {@code bazel query} output — the unconfigured target graph. */
    QUERY,

    /** Stat calls against files on disk. */
    FILESYSTEM,

    /** Computed by this application from one or more of the above. */
    DERIVED,

    /** Entered by the user. */
    USER_ANNOTATION;

    /** True when this source is evidence rather than interpretation. */
    public boolean isObserved() {
        return this != DERIVED && this != USER_ANNOTATION;
    }
}
