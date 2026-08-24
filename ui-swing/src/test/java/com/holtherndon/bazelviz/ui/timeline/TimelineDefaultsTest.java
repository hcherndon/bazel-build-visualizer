package com.holtherndon.bazelviz.ui.timeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.GraphicsEnvironment;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What the timeline shows before anyone touches a control.
 *
 * <p>These are choices, not accidents: the previous defaults — Runner and
 * "first activity" — were whatever happened to be first in each enum, and
 * Runner needs an execution log most sessions do not have. These tests exist
 * so a reordering of either enum cannot silently change what a fresh timeline
 * shows.
 */
final class TimelineDefaultsTest {

    @BeforeAll
    static void requireHeadless() {
        assertThat(GraphicsEnvironment.isHeadless()).isTrue();
    }

    @Test
    @DisplayName("a fresh timeline groups by mnemonic and sorts lanes by name")
    void groupingAndSortDefaults() {
        TimelineView view = new TimelineView();

        assertThat(view.grouping()).isEqualTo(LaneGrouping.By.MNEMONIC);
        assertThat(view.sortBy()).isEqualTo(LaneGrouping.SortBy.NAME);
    }

    @Test
    @DisplayName("follow-live starts on")
    void followLiveDefaultsOn() {
        assertThat(new TimelineView().followingForTest()).isTrue();
    }
}
