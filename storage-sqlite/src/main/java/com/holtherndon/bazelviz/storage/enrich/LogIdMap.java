package com.holtherndon.bazelviz.storage.enrich;

import java.util.Arrays;

/**
 * Maps an execution log's own entry ids to database row ids, in a flat array.
 *
 * <h2>Why not a HashMap</h2>
 *
 * <p>The compact log names one entry per file, and a five-million-action build
 * names tens of millions of files. A {@code HashMap<Long, Long>} at that size
 * costs roughly 48 bytes an entry in boxed keys, boxed values and nodes — over
 * a gigabyte before the rest of the import starts. A {@code long[]} indexed by
 * the log's id costs eight, and the log's ids are assigned sequentially from
 * one, so the array is dense.
 *
 * <p>Zero means absent, which is safe because SQLite rowids start at one and
 * because the log's own ids do too.
 *
 * <p>Entries are <em>not</em> guaranteed to arrive in increasing id order — the
 * proto says so explicitly — so this grows to fit whatever id it is handed
 * rather than assuming the next one is one larger.
 */
public final class LogIdMap {

    private static final int INITIAL_CAPACITY = 1024;

    /** Refuses an id so large it must be corruption rather than a real entry. */
    private static final long MAX_ID = 1L << 32;

    private long[] rowIds;
    private long highestId;

    public LogIdMap() {
        this.rowIds = new long[INITIAL_CAPACITY];
    }

    /** Records that log entry {@code logId} became database row {@code rowId}. */
    public void put(long logId, long rowId) {
        if (logId <= 0 || logId >= MAX_ID) {
            throw new IllegalArgumentException(
                    "execution log entry id out of range: " + logId);
        }
        if (logId >= rowIds.length) {
            int grown = rowIds.length;
            while (grown <= logId) {
                grown = Math.max(grown * 2, 1024);
            }
            rowIds = Arrays.copyOf(rowIds, grown);
        }
        rowIds[(int) logId] = rowId;
        highestId = Math.max(highestId, logId);
    }

    /**
     * The row id for a log entry, or zero when the log never declared it.
     *
     * <p>Zero is a real answer, not an error: it means the log referenced an id
     * it did not define, which the importer records as a diagnostic rather than
     * papering over.
     */
    public long get(long logId) {
        if (logId <= 0 || logId >= rowIds.length) {
            return 0;
        }
        return rowIds[(int) logId];
    }

    /** True when {@code logId} was declared. */
    public boolean contains(long logId) {
        return get(logId) != 0;
    }

    /** The largest id seen, for reporting how big the log's id space got. */
    public long highestId() {
        return highestId;
    }
}
