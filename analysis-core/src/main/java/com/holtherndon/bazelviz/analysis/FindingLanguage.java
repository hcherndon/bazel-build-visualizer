package com.holtherndon.bazelviz.analysis;

import java.util.List;
import java.util.Locale;

/**
 * The wording rules from plan 16.2, enforced rather than remembered.
 *
 * <h2>Why this is code</h2>
 *
 * <p>Plan 24's Phase 8 exit criterion is "findings avoid unsupported causal language", and plan
 * 16.2 lists the exact phrases: use "candidate", "associated with", "may indicate", "investigate";
 * avoid "root cause" unless explicit failure data proves it, "will improve build time", and
 * "definitely caused by". A style rule in a document is followed until somebody writes a finding at
 * the end of a long afternoon. A check in {@link Finding}'s constructor is followed always, and the
 * test that proves the criterion can simply try to construct a finding that breaks it.
 *
 * <h2>What the rules actually protect</h2>
 *
 * <p>Every finding here is a correlation over observational data from a build that ran once. "This
 * action's queue time was 80% of its attempt" is a measurement; "the remote queue is why your build
 * is slow" is a claim about a counterfactual build that was never run. The second sentence is the
 * one users act on, and it is the one the data cannot support — which is why plan 16.1's own rule
 * text says, of queue-dominated actions, "do not assume the cause is Bazel rather than remote
 * infrastructure".
 *
 * <h2>The one exception</h2>
 *
 * <p>"Root cause" is permitted when explicit failure data proves it: a failing action with a
 * structured failure detail and an exit code is not a correlation, it is a record of what happened.
 * {@link #check} takes that as a parameter rather than inferring it, so a finding claiming
 * causation has to be constructed by something that knows it holds the proof.
 */
public final class FindingLanguage {

  private FindingLanguage() {}

  /**
   * Phrases that assert causation or promise an outcome.
   *
   * <p>Deliberately short. A long list would start rejecting sentences that are merely clumsy, and
   * a check that fires on innocent text gets worked around rather than obeyed.
   */
  private static final List<String> CAUSAL =
      List.of(
          "will improve",
          "will reduce",
          "will speed",
          "will make the build",
          "will save",
          "definitely",
          "guaranteed",
          "guarantees",
          "is caused by",
          "was caused by",
          "the cause is",
          "this proves");

  /** Permitted only with explicit failure data behind it. */
  private static final String ROOT_CAUSE = "root cause";

  /**
   * Words that mark a statement as a candidate rather than a conclusion.
   *
   * <p>Required in the sentence explaining why a finding might matter, which is the sentence a
   * reader acts on. Everything in plan 16.2's "use" list is here, plus the ordinary hedges that
   * mean the same thing.
   */
  private static final List<String> HEDGES =
      List.of(
          "may",
          "might",
          "can ",
          "could",
          "candidate",
          "associated",
          "investigate",
          "consider",
          "suggests",
          "worth ",
          "appears");

  /**
   * Throws when {@code text} claims more than observational data supports.
   *
   * @param field which part of the finding this is, so the message says where to look
   * @param provenByFailureData true when the finding rests on a structured failure record rather
   *     than on a correlation
   */
  public static void check(String field, String text, boolean provenByFailureData) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException(field + " must say something");
    }
    String lower = text.toLowerCase(Locale.ROOT);
    for (String phrase : CAUSAL) {
      if (lower.contains(phrase)) {
        throw new IllegalArgumentException(
            field
                + " asserts causation or promises an outcome (\""
                + phrase
                + "\"), which observational data from one build cannot support"
                + " (plan 16.2): "
                + text);
      }
    }
    if (!provenByFailureData && lower.contains(ROOT_CAUSE)) {
      throw new IllegalArgumentException(
          field
              + " says \"root cause\" without explicit failure data to prove it"
              + " (plan 16.2): "
              + text);
    }
  }

  /**
   * Throws when {@code text} states a conclusion where it should offer a candidate.
   *
   * <p>Applied to the "why it may matter" sentence only. Requiring a hedge in a title would produce
   * titles that all begin with the same four words, and requiring one in a caveat is redundant — a
   * caveat is already a hedge.
   */
  public static void requireHedge(String field, String text) {
    String lower = text.toLowerCase(Locale.ROOT);
    for (String hedge : HEDGES) {
      if (lower.contains(hedge)) {
        return;
      }
    }
    throw new IllegalArgumentException(
        field
            + " states a conclusion where the data supports a candidate; use one of "
            + HEDGES
            + " (plan 16.2): "
            + text);
  }
}
