package com.holtherndon.bazelviz.capture.file.importer;

/**
 * Receives throttled progress samples from {@link BepImporter}.
 *
 * <p>Deliberately free of Swing types. The importer runs on a worker thread and
 * knows nothing about the UI; a Swing caller implements this and hands off with
 * {@code SwingUtilities.invokeLater}. Putting an EDT dependency here would make
 * the import pipeline untestable headlessly and would invite exactly the
 * blocking-the-EDT bug the project rules forbid.
 *
 * <p>Callbacks arrive on the importing thread and are throttled by
 * {@link ImportOptions#progressIntervalMillis()} and
 * {@link ImportOptions#progressEveryRecords()}, so a listener that repaints on
 * every call still cannot be flooded by a fast source. A listener that throws
 * aborts the import, so implementations must not propagate rendering failures.
 */
@FunctionalInterface
public interface ImportProgressListener {

    /** A listener that discards every sample. */
    ImportProgressListener NONE = progress -> {};

    void onProgress(ImportProgress progress);
}
