/**
 * The single definition of how a raw payload becomes database rows.
 *
 * <p>Three producers reach this package: the offline importer reading a BEP file, the live capture
 * pipeline receiving BES envelopes, and journal replay after an interruption. They must agree
 * exactly — a resumed capture that derived a different event type or id hash than an uninterrupted
 * one would make sessions incomparable, and "the final state equals an uninterrupted import" is a
 * Phase 1 exit criterion this project checks. One class, called by all three, is how that agreement
 * is enforced rather than hoped for.
 *
 * <p>It lives in {@code capture-file} rather than in {@code bep-codec} because it produces {@code
 * storage-sqlite} row records from {@code session-format} journal locations, and {@code bep-codec}
 * depends on neither. Moving it up would drag the storage and session layers onto the decoder's
 * compile classpath to save one dependency edge here.
 */
package com.holtherndon.bazelviz.capture.normalize;
