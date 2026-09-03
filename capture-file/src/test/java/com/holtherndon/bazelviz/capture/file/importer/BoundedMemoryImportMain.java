package com.holtherndon.bazelviz.capture.file.importer;

import com.holtherndon.bazelviz.core.id.SessionId;
import com.holtherndon.bazelviz.format.session.SessionManager;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Runs one import inside a JVM whose maximum heap is far smaller than the file being imported, and
 * prints what it managed.
 *
 * <p>This exists as a separate process because the streaming requirement cannot be demonstrated
 * from inside a test JVM that already has a two-gigabyte heap: an implementation that buffered the
 * whole file would pass such a test comfortably. The only way to show that nothing is retained in
 * proportion to the file is to deny the process the memory to retain it. If the pipeline ever
 * starts holding the source — a list of records, a byte array of the file, a map keyed by event —
 * this process dies with {@code OutOfMemoryError} and {@link BepImporterBoundedMemoryTest} fails.
 *
 * <p>Arguments: source file, sessions root. Output: one line of {@code key=value} pairs on stdout,
 * which the test parses.
 */
public final class BoundedMemoryImportMain {

  private BoundedMemoryImportMain() {}

  public static void main(String[] args) throws Exception {
    if (args.length != 2) {
      System.err.println("usage: BoundedMemoryImportMain <source> <sessions-root>");
      System.exit(2);
      return;
    }
    Path source = Path.of(args[0]);
    Path sessionsRoot = Path.of(args[1]);
    Files.createDirectories(sessionsRoot);

    long maxHeap = Runtime.getRuntime().maxMemory();
    long fileBytes = Files.size(source);

    SessionManager sessions = new SessionManager(sessionsRoot, "0.1.0-bounded-memory");
    // Defaults everywhere that matters: this must prove the shipped
    // configuration streams, not a specially shrunken one.
    BepImporter importer = new BepImporter(sessions, ImportOptions.defaults());
    ImportResult result =
        importer.importFile(source, SessionId.random(), ImportProgressListener.NONE, () -> false);

    System.out.println(
        "maxHeapBytes="
            + maxHeap
            + " fileBytes="
            + fileBytes
            + " outcome="
            + result.outcome()
            + " events="
            + result.eventsInDatabase()
            + " recordsJournaled="
            + result.recordsJournaled()
            + " peakBufferBytes="
            + result.peakBufferBytes()
            + " sessionRoot="
            + result.sessionRoot());
  }
}
