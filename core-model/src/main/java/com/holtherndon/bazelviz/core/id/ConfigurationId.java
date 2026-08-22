package com.holtherndon.bazelviz.core.id;

import java.util.Objects;

/**
 * A build configuration, as the event stream identifies it (plan 11.1).
 *
 * <h2>Scope: one stream, not one machine</h2>
 *
 * <p>The BEP's own comment on this identifier is unusually direct: "users of
 * the protocol should not make any assumptions about it having any structure,
 * or equality of the identifier between different streams". So this is a
 * <em>session-scoped</em> identity. Two sessions that both contain a
 * configuration called {@code 1a589d14…} may or may not mean the same thing,
 * and nothing here should suggest otherwise.
 *
 * <p>That is enough for everything Phase 3 does — every target and action in
 * one session refers to configurations in the same session — and comparing
 * configurations <em>across</em> sessions needs a content fingerprint (mnemonic,
 * platform, cpu and the options that made it), which is Phase 10's problem when
 * session comparison arrives. Recording the raw id now and deriving a
 * fingerprint later is possible; inventing a fingerprint now and finding it
 * wrong is not undoable, because it would already be in every stored row.
 *
 * <p>A typed wrapper rather than a bare {@code String} because the codebase
 * carries several opaque identifier strings — configuration, named set, stream
 * — and they are not interchangeable. Passing one where another is expected is
 * a mistake the compiler should catch.
 */
public record ConfigurationId(String value) {

    /**
     * The null configuration. Bazel uses the literal {@code "none"} for targets
     * that are not configurable, such as source files.
     */
    public static final ConfigurationId NONE = new ConfigurationId("none");

    public ConfigurationId {
        Objects.requireNonNull(value, "value");
        if (value.isEmpty()) {
            throw new IllegalArgumentException("a configuration id must not be empty");
        }
    }

    /**
     * The id an event carried, or {@link #NONE} when it carried none.
     *
     * <p>An absent configuration and the null configuration are the same thing
     * in the BEP: a target with no configuration is a source file, and Bazel
     * spells that {@code "none"} when it spells it at all.
     */
    public static ConfigurationId ofEventValue(String value) {
        return value == null || value.isEmpty() ? NONE : new ConfigurationId(value);
    }

    /** True for the null configuration — source files and other unconfigurables. */
    public boolean isNull() {
        return NONE.value.equals(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
