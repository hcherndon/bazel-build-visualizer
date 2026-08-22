package com.holtherndon.bazelviz.ui.events;

import com.holtherndon.bazelviz.storage.events.EventDetail;
import com.holtherndon.bazelviz.storage.events.EventSummary;
import com.holtherndon.bazelviz.ui.session.SessionDataException;
import com.holtherndon.bazelviz.ui.session.SessionReader;
import com.holtherndon.bazelviz.ui.table.Page;
import com.holtherndon.bazelviz.ui.table.RowSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The chronological event table's {@link RowSource}: index-addressed on the
 * outside, keyset-paged on the inside.
 *
 * <h2>How a row index becomes a page</h2>
 *
 * <ol>
 *   <li>{@link EventRowIndex} turns the page's first row index into a keyset
 *       anchor — arithmetic when the event ids are contiguous (which
 *       {@link EventRowIndex#open} verifies), a bounded sparse anchor array
 *       when they are not. Neither path uses {@code OFFSET}.</li>
 *   <li>One {@link SessionReader#pageAfter} call fetches the page's rows from
 *       that anchor.</li>
 *   <li>The predicted first id is checked against what came back. A mismatch
 *       demotes the index and the fetch is retried once, rather than the table
 *       showing rows from the wrong place.</li>
 *   <li>Each row is completed with its raw location and id display.</li>
 * </ol>
 *
 * <h2>The per-row lookup, and why it is here</h2>
 *
 * <p>Step 4 costs one primary-key lookup per row. {@code EventSummary} — what
 * the keyset page returns — carries neither {@code raw_length} nor the
 * canonical id display, and both are columns the Phase 1 event table is
 * required to show. {@code storage-sqlite} is frozen for this phase, so the
 * honest options were to fetch them per row or to leave two required columns
 * out. This fetches them: a page of {@value #DEFAULT_PAGE_SIZE} rows costs one
 * range scan plus that many indexed point lookups, all on the fetch executor
 * and never on the EDT. When the storage module next opens, the fix is a
 * single query returning the summary columns joined to
 * {@code raw_length} and {@code bep_event_ids.display}, and this loop collapses
 * into it.
 *
 * <p>What is <em>not</em> fetched per row is the payload itself. The table
 * shows how many bytes a record occupies; the bytes themselves are read only
 * for the selected event, by {@link EventInspectorModel} (plan 17.11).
 *
 * <h2>Threading</h2>
 *
 * <p>{@link #fetchPage} runs on {@code PagedTableModel}'s single-threaded fetch
 * executor, which is also the only thread allowed to touch the
 * {@link SessionReader} and the {@link EventRowIndex}. {@link #rowCount()} is
 * called by the model's constructor on the EDT, which is why it returns a value
 * captured during {@link #open} rather than issuing a query.
 */
public final class EventRowSource implements RowSource<EventRow> {

    /**
     * Rows per page. Small enough that a page's per-row lookups stay well
     * inside the 100 ms cached-page objective (docs/performance.md), large
     * enough that a screenful never spans more than two pages.
     */
    public static final int DEFAULT_PAGE_SIZE = 200;

    private final SessionReader reader;
    private final int pageSize;
    private final long rowCount;
    private final EventRowIndex index;

    private EventRowSource(
            SessionReader reader, int pageSize, long rowCount, EventRowIndex index) {
        this.reader = reader;
        this.pageSize = pageSize;
        this.rowCount = rowCount;
        this.index = index;
    }

    /**
     * Probes the session and builds a source over it.
     *
     * <p>Blocking: run this on the fetch executor, never on the EDT. The
     * resulting object answers {@link #rowCount()} without I/O so that
     * constructing the table model on the EDT is safe.
     */
    public static EventRowSource open(SessionReader reader, int pageSize) {
        Objects.requireNonNull(reader, "reader");
        EventRowIndex index = EventRowIndex.open(reader, pageSize);
        return new EventRowSource(reader, pageSize, index.rowCount(), index);
    }

    /** Opens with {@link #DEFAULT_PAGE_SIZE}. */
    public static EventRowSource open(SessionReader reader) {
        return open(reader, DEFAULT_PAGE_SIZE);
    }

    @Override
    public long rowCount() {
        return rowCount;
    }

    /** The page size this source's row index was built for. */
    public int pageSize() {
        return pageSize;
    }

    /** How row indices are currently resolved; for the view's status line. */
    public EventRowIndex.Mode rowIndexMode() {
        return index.mode();
    }

    @Override
    public Page<EventRow> fetchPage(long pageIndex, int requestedPageSize) {
        if (requestedPageSize != pageSize) {
            // The row index's anchors are laid out for one page size. Serving a
            // different one would silently misalign every page boundary.
            throw new IllegalArgumentException("this source was opened for pages of " + pageSize
                    + " rows and cannot serve pages of " + requestedPageSize);
        }
        long firstRow = pageIndex * pageSize;
        if (firstRow >= rowCount) {
            return new Page<>(pageIndex, List.of());
        }
        int wanted = (int) Math.min(pageSize, rowCount - firstRow);
        return new Page<>(pageIndex, fetchRows(firstRow, wanted, true));
    }

    private List<EventRow> fetchRows(long firstRow, int wanted, boolean mayRetry) {
        EventRowIndex.Anchor anchor = index.anchorForRow(firstRow);
        List<EventSummary> summaries = reader.pageAfter(anchor.exclusiveId(), wanted);
        if (summaries.size() != wanted) {
            throw new SessionDataException("row " + firstRow + " of " + rowCount
                    + " asked for " + wanted + " events and the store returned "
                    + summaries.size() + "; the session changed while it was being viewed");
        }
        if (anchor.expectedFirstId().isPresent()) {
            long expected = anchor.expectedFirstId().getAsLong();
            long actual = summaries.getFirst().id();
            if (expected != actual) {
                index.reportPredictionMismatch(firstRow, expected, actual);
                if (mayRetry) {
                    return fetchRows(firstRow, wanted, false);
                }
                throw new SessionDataException("row " + firstRow + " resolved to event id "
                        + actual + " where " + expected + " was expected, and the rebuilt row"
                        + " index did not agree either");
            }
        }
        // Built straight from the page: the summary now carries the raw
        // location and the id display, so a page costs one query rather than
        // one query plus a point lookup per row.
        List<EventRow> rows = new ArrayList<>(wanted);
        for (EventSummary summary : summaries) {
            rows.add(EventRow.of(summary));
        }
        return rows;
    }
}
