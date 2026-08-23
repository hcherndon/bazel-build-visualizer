package com.holtherndon.bazelviz.ui.timeline;

import java.util.Optional;
import java.util.OptionalLong;

/**
 * Where the user is looking, and whether they chose to be there.
 *
 * <h2>Why this is a type and not two fields on the view</h2>
 *
 * <p>Plan 24's fifth Phase 6 exit criterion is that live updates must not reset
 * the viewport or the selection. The hard part is not remembering the numbers —
 * it is knowing whether the user moved. A timeline that follows the build is
 * doing the right thing until the moment someone scrolls back to look at
 * something, and from then on following is destroying their work.
 *
 * <p>So "the user has navigated" is recorded here, as a fact, the moment it
 * happens. {@link #withWall} is the only path a live update takes, and it
 * refuses to move a viewport that a person put where it is.
 *
 * @param transform the current pan and zoom
 * @param following whether new data should pull the view along
 * @param selectedNode the selected graph node, kept across updates
 * @param rangeFromMicros a dragged-out time range, kept across updates
 */
public record TimelineViewport(
        TimelineTransform transform,
        boolean following,
        OptionalLong selectedNode,
        OptionalLong rangeFromMicros,
        OptionalLong rangeToMicros) {

    /** A viewport fitted to a whole wall, following whatever arrives next. */
    public static TimelineViewport fitting(long startMicros, long endMicros, int widthPixels) {
        return new TimelineViewport(
                TimelineTransform.fit(startMicros, endMicros, widthPixels),
                true, OptionalLong.empty(), OptionalLong.empty(), OptionalLong.empty());
    }

    /**
     * The viewport after the user panned or zoomed.
     *
     * <p>Stops following, always. Someone who has moved is looking at
     * something, and the build appending to the right is not a reason to take
     * them away from it — plan 14.4: "do not auto-scroll if the user has
     * navigated into history".
     */
    public TimelineViewport navigatedTo(TimelineTransform moved) {
        return new TimelineViewport(
                moved, false, selectedNode, rangeFromMicros, rangeToMicros);
    }

    /** The viewport with following turned on or off by the user's toggle. */
    public TimelineViewport following(boolean follow) {
        return new TimelineViewport(
                transform, follow, selectedNode, rangeFromMicros, rangeToMicros);
    }

    /** The viewport with a different selection, keeping everything else. */
    public TimelineViewport selecting(OptionalLong node) {
        return new TimelineViewport(transform, following, node, rangeFromMicros, rangeToMicros);
    }

    /** The viewport with a dragged-out range, keeping everything else. */
    public TimelineViewport withRange(long fromMicros, long toMicros) {
        return new TimelineViewport(
                transform, following, selectedNode,
                OptionalLong.of(Math.min(fromMicros, toMicros)),
                OptionalLong.of(Math.max(fromMicros, toMicros)));
    }

    /** The viewport with no range. */
    public TimelineViewport withoutRange() {
        return new TimelineViewport(
                transform, following, selectedNode, OptionalLong.empty(), OptionalLong.empty());
    }

    /**
     * The viewport after new data extended the wall.
     *
     * <p>The whole point of this class. A following viewport is refitted so the
     * user watching a build sees it grow; one the user has moved is returned
     * untouched, selection and range included.
     *
     * <p>Note what is <em>not</em> here: no case that clears the selection, and
     * none that resets the transform. A live update has no business doing
     * either, and the way to guarantee that is to give it no way to.
     */
    public TimelineViewport withWall(long startMicros, long endMicros, int widthPixels) {
        if (!following) {
            return this;
        }
        return new TimelineViewport(
                TimelineTransform.fit(startMicros, endMicros, widthPixels),
                true, selectedNode, rangeFromMicros, rangeToMicros);
    }

    /** True when the user has dragged out a time range. */
    public boolean hasRange() {
        return rangeFromMicros.isPresent() && rangeToMicros.isPresent();
    }

    /** The range as a filter description, when there is one. */
    public Optional<String> describeRange() {
        if (!hasRange()) {
            return Optional.empty();
        }
        long width = rangeToMicros.getAsLong() - rangeFromMicros.getAsLong();
        return Optional.of(String.format(
                java.util.Locale.ROOT, "%.3f s selected", width / 1_000_000.0));
    }

    /** What the follow toggle says. */
    public String followLabel() {
        return following ? "Following live" : "Follow live";
    }
}
