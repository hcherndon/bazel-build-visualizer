/**
 * The normalization command vocabulary: what one build event says happened, in terms the storage
 * layer can write and the proto layer can produce.
 *
 * <p>These types are deliberately free of protobuf. {@code bep-codec} produces them from a {@code
 * BuildEvent}, {@code storage-sqlite} consumes them into schema v2, and neither has to know about
 * the other. Phases 4 and 5 will produce the same commands from an execution log and from {@code
 * aquery}, which is only possible because nothing here names the BEP.
 *
 * <p>Where a field is {@code Optional} or {@code OptionalLong}, its emptiness is a fact about the
 * build rather than about the parser: an action with no label really had none, and a duration that
 * is empty was never measured (plan 11.4). Where a field is a plain {@code boolean} or {@code
 * long}, the wire's proto3 default <em>is</em> the value.
 *
 * <p>The measurements behind these shapes are in {@code docs/bep-content.md}.
 */
package com.holtherndon.bazelviz.core.entity;
