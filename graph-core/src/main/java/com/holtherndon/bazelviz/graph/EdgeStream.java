package com.holtherndon.bazelviz.graph;

/**
 * A replayable source of directed edges. {@link CsrBuilder} iterates the
 * stream exactly twice (count pass, fill pass), so {@link #forEach} must
 * deliver the identical edge multiset on every invocation.
 */
@FunctionalInterface
public interface EdgeStream {

    void forEach(EdgeVisitor visitor);

    @FunctionalInterface
    interface EdgeVisitor {
        void edge(int from, int to);
    }
}
