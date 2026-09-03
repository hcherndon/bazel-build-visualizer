package com.holtherndon.bazelviz.ui.events;

import com.holtherndon.bazelviz.bepcodec.BepPayloadType;
import com.holtherndon.bazelviz.ui.format.EventValueFormat;
import com.holtherndon.bazelviz.ui.table.ColumnSpec;
import java.util.List;
import java.util.OptionalLong;

/**
 * The columns of the chronological event table (plan 17.11).
 *
 * <p>Every extractor is a field read plus trivial formatting, as
 * {@link ColumnSpec} requires — they run on the EDT during paint, for the
 * visible rows only.
 *
 * <h2>What each column refuses to invent</h2>
 *
 * <ul>
 *   <li><b>Type</b> and <b>Children</b> are stored as {@code 0} for a record
 *       that did not decode, because their columns are {@code NOT NULL} and a
 *       row must exist for those bytes regardless. Displaying that 0 would
 *       assert "no payload, no children" about a record nobody could read, so
 *       a failed decode renders both as an em dash.</li>
 *   <li><b>Event time</b> is empty for every BEP event that carries no
 *       timestamp of its own. It renders as an em dash, never as the epoch.</li>
 *   <li><b>Event id</b> renders as an em dash when the event declared no id,
 *       and as the raw hash when it declared one whose identity row is missing
 *       — two different findings, shown differently.</li>
 *   <li><b>Raw bytes</b> is always known: it is the length the journal actually
 *       stored, which is why it is the one column a failed decode still fills
 *       in.</li>
 * </ul>
 */
public final class EventTableColumns {

    /** Index of the column carrying the row's database id, used to resolve selection. */
    public static final int ID_COLUMN = 0;

    private EventTableColumns() {}

    /** The columns, in display order. */
    public static List<ColumnSpec<EventRow>> columns() {
        return List.of(
                new ColumnSpec<>("Row id", EventRow::id),
                new ColumnSpec<>("Sequence", EventRow::sequence),
                new ColumnSpec<>("Type", EventTableColumns::eventType),
                new ColumnSpec<>("Decode", EventTableColumns::decode),
                new ColumnSpec<>("Event id", EventTableColumns::identity),
                new ColumnSpec<>("Children", EventTableColumns::children),
                new ColumnSpec<>("Raw bytes", row -> EventValueFormat.count(row.rawLength())),
                new ColumnSpec<>("Event time (UTC)",
                        row -> EventValueFormat.timestamp(row.eventMicros())),
                new ColumnSpec<>("Received (UTC)",
                        row -> EventValueFormat.timestamp(row.receiveMicros())));
    }

    /**
     * The payload case, or an em dash when the record did not decode and its
     * stored {@code event_type} of 0 therefore means "unread", not "none".
     */
    public static String eventType(EventRow row) {
        if (!row.hasReadableStructure()) {
            return EventValueFormat.UNKNOWN;
        }
        return BepPayloadType.name(row.eventType());
    }

    /** The decode outcome, with unknown fields called out rather than hidden. */
    public static String decode(EventRow row) {
        return switch (row.decodeStatus()) {
            case OK -> "ok";
            case UNKNOWN_FIELDS -> "unknown fields";
            case FAILED -> "failed";
            // NOT_ATTEMPTED is not persistable; seeing one means the row was
            // written by something that broke that contract, and saying so is
            // better than translating it into a plausible neighbour.
            case NOT_ATTEMPTED -> "not attempted (unexpected)";
        };
    }

    /**
     * The canonical id display; the bare hash when the event declared an id
     * whose identity row is absent; an em dash when it declared none.
     */
    public static String identity(EventRow row) {
        if (row.idDisplay().isPresent()) {
            return EventValueFormat.text(row.idDisplay());
        }
        OptionalLong hash = row.eventIdHash();
        if (hash.isEmpty()) {
            return EventValueFormat.UNKNOWN;
        }
        return EventValueFormat.idHash(hash) + " (no identity row)";
    }

    /**
     * The announced-child count, or an em dash when the record did not decode
     * and nothing could have been counted.
     */
    public static String children(EventRow row) {
        return row.hasReadableStructure()
                ? EventValueFormat.count(row.childCount())
                : EventValueFormat.UNKNOWN;
    }
}
