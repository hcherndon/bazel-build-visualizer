package com.holtherndon.bazelviz.storage.entities;

import com.holtherndon.bazelviz.core.domain.ActionOutcome;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * What the actions table is currently showing.
 *
 * <p>A filter narrows what is displayed and the view says so — the row count shown beside a
 * filtered table is the filtered count, and the unfiltered total stays visible next to it. Silently
 * showing a subset as though it were everything is the failure mode this exists to avoid (rule 12).
 *
 * @param mnemonic exact match on the action type
 * @param outcome exact match on succeeded or failed
 * @param labelContains substring of the owning target's label. Actions with no label are excluded
 *     when this is set, because "contains" cannot be true of a label that does not exist — the view
 *     says so rather than implying the unlabelled action did not match on its merits.
 * @param textContains substring of the primary output path
 */
public record ActionFilter(
    Optional<String> mnemonic,
    Optional<ActionOutcome> outcome,
    Optional<String> labelContains,
    Optional<String> textContains,
    OptionalLong fromMicros,
    OptionalLong toMicros) {

  public static final ActionFilter NONE =
      new ActionFilter(
          Optional.empty(),
          Optional.empty(),
          Optional.empty(),
          Optional.empty(),
          OptionalLong.empty(),
          OptionalLong.empty());

  public ActionFilter {
    Objects.requireNonNull(mnemonic, "mnemonic");
    Objects.requireNonNull(outcome, "outcome");
    Objects.requireNonNull(labelContains, "labelContains");
    Objects.requireNonNull(textContains, "textContains");
  }

  public boolean isEmpty() {
    return fromMicros.isEmpty()
        && toMicros.isEmpty()
        && mnemonic.isEmpty()
        && outcome.isEmpty()
        && labelContains.isEmpty()
        && textContains.isEmpty();
  }

  public ActionFilter withMnemonic(Optional<String> value) {
    return new ActionFilter(value, outcome, labelContains, textContains, fromMicros, toMicros);
  }

  public ActionFilter withOutcome(Optional<ActionOutcome> value) {
    return new ActionFilter(mnemonic, value, labelContains, textContains, fromMicros, toMicros);
  }

  public ActionFilter withLabelContains(Optional<String> value) {
    return new ActionFilter(mnemonic, outcome, value, textContains, fromMicros, toMicros);
  }

  public ActionFilter withTextContains(Optional<String> value) {
    return new ActionFilter(mnemonic, outcome, labelContains, value, fromMicros, toMicros);
  }

  /**
   * The filter restricted to actions overlapping a time range.
   *
   * <p>Plan 14.5's "filter selected time range": a range dragged out on the timeline narrows the
   * actions table to what ran inside it.
   *
   * <p>Overlapping, not contained. An action that started before the range and finished inside it
   * was running during the window the user selected, and excluding it would hide exactly the long
   * action a user dragging a range around a slow patch is usually looking for.
   */
  public ActionFilter inRange(long fromMicros, long toMicros) {
    return new ActionFilter(
        mnemonic,
        outcome,
        labelContains,
        textContains,
        OptionalLong.of(Math.min(fromMicros, toMicros)),
        OptionalLong.of(Math.max(fromMicros, toMicros)));
  }

  /** The filter with no time restriction. */
  public ActionFilter withoutRange() {
    return new ActionFilter(
        mnemonic, outcome, labelContains, textContains, OptionalLong.empty(), OptionalLong.empty());
  }

  /** True when a time range is in force. */
  public boolean hasRange() {
    return fromMicros.isPresent() && toMicros.isPresent();
  }
}
