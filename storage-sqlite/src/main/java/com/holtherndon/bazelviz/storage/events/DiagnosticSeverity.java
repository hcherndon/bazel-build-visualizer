package com.holtherndon.bazelviz.storage.events;

/** Severity of an {@code import_diagnostics} row. */
public enum DiagnosticSeverity {
    /** Something worth recording that did not reduce the data. */
    INFO,

    /** Data is reduced or uncertain, but the import continues. */
    WARNING,

    /** Data was lost or the import could not continue. */
    ERROR;

    public static DiagnosticSeverity parse(String value) {
        return valueOf(value);
    }
}
