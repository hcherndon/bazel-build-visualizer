/**
 * Translation from build events into normalization commands (plan, {@code bep-codec}) — the layer
 * that decides what Bazel's stream <em>means</em>.
 *
 * <p>Everything here is pure: {@link com.holtherndon.bazelviz.bepcodec.entity.EntityTranslator}
 * takes a decoded {@code BuildEvent} and returns {@link
 * com.holtherndon.bazelviz.core.entity.EntityCommand} records, with no database, no files and no
 * clock. That is deliberate. Almost every hard-won fact about how Bazel actually behaves lives in
 * this package, and keeping it free of I/O means each one can be pinned by a test that builds an
 * event by hand — so a wrong reading of Bazel and a wrong SQL statement never look alike in a
 * failure.
 *
 * <p>The measurements these classes encode are recorded in {@code docs/bep-content.md}, against
 * Bazel 6.5.0, 7.6.1, 8.4.1 and 9.2.0. Where a class comment cites a finding id, that is the
 * evidence.
 */
package com.holtherndon.bazelviz.bepcodec.entity;
