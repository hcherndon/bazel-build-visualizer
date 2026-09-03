package com.holtherndon.bazelviz.ui.events;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.core.event.DecodeStatus;
import com.holtherndon.bazelviz.storage.events.RawLocation;
import com.holtherndon.bazelviz.ui.format.EventValueFormat;
import com.holtherndon.bazelviz.ui.table.ColumnSpec;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unknown-is-not-zero, at the only place the user can see it (plan 11.4,
 * project rule 11).
 *
 * <p>A record whose bytes did not decode has {@code event_type = 0} and
 * {@code child_count = 0} in the database, because those columns are
 * {@code NOT NULL} and a row must exist for the bytes regardless. Rendering
 * those zeroes would tell the user the event had no payload and no children —
 * a specific, false claim about a record nobody could read. These tests pin
 * every such cell to the em dash.
 */
class EventTableColumnsTest {

    private static final RawLocation LOCATION = new RawLocation(0, 512, 40);

    @Test
    @DisplayName("a failed decode shows an em dash for type and children, never 0")
    void failedDecodeNeverRendersZero() {
        EventRow row = new EventRow(
                7, 6, 0, DecodeStatus.FAILED, false,
                OptionalLong.empty(), Optional.empty(), 0, false,
                LOCATION, OptionalLong.empty(), 1_700_000_000_000_000L);

        assertThat(EventTableColumns.eventType(row)).isEqualTo(EventValueFormat.UNKNOWN);
        assertThat(EventTableColumns.children(row)).isEqualTo(EventValueFormat.UNKNOWN);
        assertThat(EventTableColumns.identity(row)).isEqualTo(EventValueFormat.UNKNOWN);
        assertThat(EventTableColumns.decode(row)).isEqualTo("failed");

        assertThat(renderRow(row))
                .as("no cell of an undecodable event may render as a bare zero")
                .doesNotContain("0")
                .contains(EventValueFormat.UNKNOWN);
    }

    @Test
    @DisplayName("an event with no timestamp shows an em dash, not the epoch")
    void missingTimestampIsNotTheEpoch() {
        EventRow row = decodedRow(OptionalLong.empty());

        assertThat(EventValueFormat.timestamp(row.eventMicros()))
                .isEqualTo(EventValueFormat.UNKNOWN)
                .isNotEqualTo("1970-01-01 00:00:00.000000");
    }

    @Test
    @DisplayName("a real timestamp renders in UTC to microsecond precision")
    void realTimestampRenders() {
        assertThat(EventValueFormat.timestamp(1_700_000_000_123_456L))
                .isEqualTo("2023-11-14 22:13:20.123456");
    }

    @Test
    @DisplayName("a decoded event with no id of its own shows an em dash")
    void eventWithoutAnIdIsUnknownNotBlank() {
        EventRow row = new EventRow(
                3, 2, 3, DecodeStatus.OK, false,
                OptionalLong.empty(), Optional.empty(), 0, false,
                LOCATION, OptionalLong.of(1_700_000_000_000_000L), 1_700_000_000_000_000L);

        assertThat(EventTableColumns.identity(row)).isEqualTo(EventValueFormat.UNKNOWN);
        // A decoded event really does have zero children; that 0 is an
        // observation and stays a 0.
        assertThat(EventTableColumns.children(row)).isEqualTo("0");
    }

    @Test
    @DisplayName("an id hash with no identity row is shown as the hash, and said to be missing")
    void identityRowMissingIsItsOwnFinding() {
        EventRow row = new EventRow(
                3, 2, 3, DecodeStatus.OK, false,
                OptionalLong.of(0x1234L), Optional.empty(), 1, false,
                LOCATION, OptionalLong.of(1L), 1L);

        assertThat(EventTableColumns.identity(row))
                .isEqualTo("0x0000000000001234 (no identity row)");
    }

    @Test
    @DisplayName("raw length is always known, even when nothing else about the record is")
    void rawLengthSurvivesAFailedDecode() {
        EventRow row = new EventRow(
                7, 6, 0, DecodeStatus.FAILED, false,
                OptionalLong.empty(), Optional.empty(), 0, false,
                LOCATION, OptionalLong.empty(), 1L);

        assertThat(row.rawLength()).isEqualTo(40);
        assertThat(EventValueFormat.count(row.rawLength())).isEqualTo("40");
    }

    @Test
    @DisplayName("unknown fields are surfaced rather than folded into ok")
    void unknownFieldsAreVisible() {
        EventRow row = new EventRow(
                3, 2, 3, DecodeStatus.UNKNOWN_FIELDS, true,
                OptionalLong.of(9L), Optional.of("//a:b"), 1, false,
                LOCATION, OptionalLong.of(1L), 1L);

        assertThat(EventTableColumns.decode(row)).isEqualTo("unknown fields");
    }

    @Test
    @DisplayName("the columns are the Phase 1 deliverable's columns")
    void columnsAreTheOnesThePlanNames() {
        assertThat(EventTableColumns.columns()).extracting(ColumnSpec::name)
                .containsExactly("Row id", "Sequence", "Type", "Decode", "Event id", "Children",
                        "Raw bytes", "Event time (UTC)", "Received (UTC)");
    }

    private static EventRow decodedRow(OptionalLong eventMicros) {
        return new EventRow(
                3, 2, 3, DecodeStatus.OK, false,
                OptionalLong.of(9L), Optional.of("//a:b"), 1, false,
                LOCATION, eventMicros, 1_700_000_000_000_000L);
    }

    /** Every cell of one row, as the table would render it. */
    private static String renderRow(EventRow row) {
        List<ColumnSpec<EventRow>> columns = EventTableColumns.columns();
        StringBuilder text = new StringBuilder();
        for (ColumnSpec<EventRow> column : columns) {
            if (column.name().equals("Row id")
                    || column.name().equals("Sequence")
                    || column.name().equals("Raw bytes")
                    || column.name().equals("Received (UTC)")) {
                // Genuinely observed values: the row's own identity, its
                // position in the stream, the byte count the journal stored and
                // when it was received. Those may legitimately contain digits.
                continue;
            }
            text.append(column.extractor().apply(row)).append('|');
        }
        return text.toString();
    }
}
