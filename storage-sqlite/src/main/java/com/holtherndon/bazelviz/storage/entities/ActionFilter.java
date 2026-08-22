package com.holtherndon.bazelviz.storage.entities;

import com.holtherndon.bazelviz.core.domain.ActionOutcome;
import java.util.Objects;
import java.util.Optional;

/**
 * What the actions table is currently showing.
 *
 * <p>A filter narrows what is displayed and the view says so — the row count
 * shown beside a filtered table is the filtered count, and the unfiltered total
 * stays visible next to it. Silently showing a subset as though it were
 * everything is the failure mode this exists to avoid (rule 12).
 *
 * @param mnemonic exact match on the action type
 * @param outcome exact match on succeeded or failed
 * @param labelContains substring of the owning target's label. Actions with no
 *     label are excluded when this is set, because "contains" cannot be true of
 *     a label that does not exist — the view says so rather than implying the
 *     unlabelled action did not match on its merits.
 * @param textContains substring of the primary output path
 */
public record ActionFilter(
        Optional<String> mnemonic,
        Optional<ActionOutcome> outcome,
        Optional<String> labelContains,
        Optional<String> textContains) {

    public static final ActionFilter NONE =
            new ActionFilter(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());

    public ActionFilter {
        Objects.requireNonNull(mnemonic, "mnemonic");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(labelContains, "labelContains");
        Objects.requireNonNull(textContains, "textContains");
    }

    public boolean isEmpty() {
        return mnemonic.isEmpty() && outcome.isEmpty()
                && labelContains.isEmpty() && textContains.isEmpty();
    }

    public ActionFilter withMnemonic(Optional<String> value) {
        return new ActionFilter(value, outcome, labelContains, textContains);
    }

    public ActionFilter withOutcome(Optional<ActionOutcome> value) {
        return new ActionFilter(mnemonic, value, labelContains, textContains);
    }

    public ActionFilter withLabelContains(Optional<String> value) {
        return new ActionFilter(mnemonic, outcome, value, textContains);
    }

    public ActionFilter withTextContains(Optional<String> value) {
        return new ActionFilter(mnemonic, outcome, labelContains, value);
    }
}
