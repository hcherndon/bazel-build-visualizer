package com.holtherndon.bazelviz.core.id;

import java.util.Objects;

/**
 * A named set of files, as one event stream identifies it (plan 11.1).
 *
 * <h2>Stream-scoped, and that is not a limitation to route around</h2>
 *
 * <p>The BEP says these ids are "valid only for the particular instance of the
 * event stream", and the type says so too. A depset id from one invocation
 * means nothing in another, and a session that captured two invocations has two
 * unrelated id spaces that happen to use the same short strings.
 *
 * <p>Storage therefore keys depsets on {@code (stream, id)} rather than on the
 * id alone. Getting that wrong would merge two builds' file sets into one
 * graph — silently, since the ids are small integers and collisions are not
 * merely possible but near-certain.
 *
 * <h2>Not flattened</h2>
 *
 * <p>Plan 10.7 says not to eagerly flatten every depset into duplicated
 * action-input rows, and this identifier is what makes that possible: a depset
 * refers to its children by id, so the DAG is stored as a DAG. A build whose
 * depsets share heavily — which is every large build, that being the point of
 * depsets — would otherwise expand to a row count multiplied by the sharing
 * factor.
 */
public record DepsetId(String value) {

    public DepsetId {
        Objects.requireNonNull(value, "value");
        if (value.isEmpty()) {
            throw new IllegalArgumentException("a named-set id must not be empty");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
