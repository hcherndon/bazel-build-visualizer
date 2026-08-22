/**
 * The raw segmented journal: the recovery and forward-compatibility source of
 * truth for a session (ADR-004, plan 9.3 and 21.1).
 *
 * <p>Every payload the application captures is appended here verbatim before
 * anything is derived from it, so a session can always be rebuilt from raw
 * bytes — including by a later version of this application that understands
 * more of the schema than this one does.
 *
 * <ul>
 *   <li>{@link com.holtherndon.bazelviz.format.journal.JournalWriter} appends
 *       frames, rotates segments at frame boundaries, and groups flushes.</li>
 *   <li>{@link com.holtherndon.bazelviz.format.journal.JournalReader} reads
 *       them back, verifying each checksum and stopping at the first frame it
 *       cannot trust with an exact offset and reason.</li>
 *   <li>{@link com.holtherndon.bazelviz.format.journal.JournalRecovery} finds
 *       the last intact frame after a crash and removes only the invalid
 *       trailing bytes.</li>
 *   <li>{@link com.holtherndon.bazelviz.format.journal.ImportCheckpointStore}
 *       records where to resume, atomically.</li>
 * </ul>
 *
 * <p>The byte layout itself lives in
 * {@link com.holtherndon.bazelviz.core.journal.JournalFormat} so that writer,
 * reader and recovery cannot drift apart.
 */
package com.holtherndon.bazelviz.format.journal;
