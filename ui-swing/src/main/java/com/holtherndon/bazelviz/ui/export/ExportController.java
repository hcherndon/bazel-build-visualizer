package com.holtherndon.bazelviz.ui.export;

import com.holtherndon.bazelviz.capture.file.export.BepStreamExport;
import com.holtherndon.bazelviz.core.redact.RedactionPolicy;
import com.holtherndon.bazelviz.core.redact.RedactionReport;
import com.holtherndon.bazelviz.core.redact.Redactor;
import com.holtherndon.bazelviz.format.portable.BvizWriter;
import com.holtherndon.bazelviz.format.session.ManagedSessionLayout;
import com.holtherndon.bazelviz.storage.export.TableExport;
import com.holtherndon.bazelviz.storage.redact.SessionRedaction;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

/**
 * Runs the exports, off the event thread, and shows what redaction did before
 * anything leaves the machine.
 *
 * <h2>The confirmation is not a courtesy</h2>
 *
 * <p>docs/privacy.md: an export "shows the user exactly what was redacted
 * before anything is written". Pattern matching finds what it was told to look
 * for, so the honest offer is not "everything sensitive was removed" but "here
 * is what was removed, and here is how much of the session it touched". That
 * requires redaction to run <em>first</em>, into a temporary file, with the
 * archive built only after somebody has read the report and said yes.
 *
 * <h2>The temporary redacted database is the dangerous artifact</h2>
 *
 * <p>Between the copy and the redaction it holds the unredacted session, so it
 * is written into the session's own directory — inside the same permissions the
 * session already has — and deleted whether the export succeeds, fails or is
 * declined.
 *
 * <h2>Path prefixes come from the session</h2>
 *
 * <p>A redactor that only masks {@code /Users/<name>} leaves the rest of an
 * absolute path intact, which is most of it. The workspace and output-base
 * paths are in the session's own tables, so they are read and mapped to
 * {@code [workspace]} and {@code [output-base]} — which keeps the informative
 * part of every path and drops the identifying part.
 */
public final class ExportController {

    private final ExecutorService worker;
    private final Consumer<Runnable> onEventThread;

    public ExportController(ExecutorService worker, Consumer<Runnable> onEventThread) {
        this.worker = Objects.requireNonNull(worker, "worker");
        this.onEventThread = Objects.requireNonNull(onEventThread, "onEventThread");
    }

    /**
     * The two choices plan 22.2 asks to be offered, both off by default.
     *
     * @param omitEnvironmentValues plan 22.2's "optional omission of
     *     environment values while retaining names" — stronger than the pattern
     *     rules, and the right answer for a build whose environment holds
     *     something the patterns will not recognise
     * @param pseudonymiseLabels hides internal project structure at the cost of
     *     making the export nearly unreadable, so it is never a default
     */
    public record RedactionOptions(boolean omitEnvironmentValues, boolean pseudonymiseLabels) {

        public static RedactionOptions defaults() {
            return new RedactionOptions(false, false);
        }

        RedactionPolicy applyTo(RedactionPolicy policy) {
            RedactionPolicy result = policy;
            if (omitEnvironmentValues) {
                result = result.omittingEnvironmentValues();
            }
            if (pseudonymiseLabels) {
                result = result.redactingLabels();
            }
            return result;
        }
    }

    /** Shown the report, decides whether the export proceeds. Called on the UI thread. */
    @FunctionalInterface
    public interface Confirmer {
        boolean confirm(RedactionReport report);
    }

    /**
     * Writes a portable archive.
     *
     * @param redacted when true the session is redacted into a temporary copy,
     *     the report is confirmed, and the archive carries no raw capture
     */
    public void exportArchive(
            Path sessionRoot,
            Path target,
            boolean redacted,
            RedactionOptions redactionOptions,
            String appVersion,
            Confirmer confirmer,
            Consumer<BvizWriter.Result> onDone,
            Consumer<Throwable> onError) {
        worker.execute(() -> {
            Path temporary = null;
            try {
                BvizWriter.Options options;
                if (redacted) {
                    ManagedSessionLayout layout = ManagedSessionLayout.at(sessionRoot);
                    temporary = sessionRoot.resolve("redacted-export.sqlite");
                    SessionRedaction.Result result = SessionRedaction.copyRedacted(
                            layout.databaseFile(), temporary,
                            redactionOptions.applyTo(policyFor(layout.databaseFile())));
                    Path pending = temporary;
                    if (!confirmOnEventThread(confirmer, result.report())) {
                        Files.deleteIfExists(pending);
                        return;
                    }
                    options = BvizWriter.Options.redacted(
                            "redacted export", Map.of("session.sqlite", temporary));
                } else {
                    options = BvizWriter.Options.complete("complete export");
                }
                // Plan 10.4: estimate the space before exporting. The source
                // size is an exact upper bound on the archive, so a target
                // filesystem that cannot hold it certainly cannot hold the
                // export -- and finding that out after twenty minutes of
                // writing is the failure this avoids.
                BvizWriter.SpaceEstimate estimate =
                        BvizWriter.estimate(sessionRoot, target, options);
                if (!estimate.fits()) {
                    throw new java.io.IOException(
                            "not enough room at " + target.getParent() + ": "
                                    + estimate.describe());
                }
                BvizWriter.Result result = BvizWriter.write(
                        sessionRoot, target, options, appVersion, nowMicros());
                onEventThread.accept(() -> onDone.accept(result));
            } catch (Exception failure) {
                onEventThread.accept(() -> onError.accept(failure));
            } finally {
                deleteQuietly(temporary);
            }
        });
    }

    /** Writes the captured stream back out as a binary BEP file. */
    public void exportBep(
            Path sessionRoot,
            Path target,
            Consumer<BepStreamExport.Result> onDone,
            Consumer<Throwable> onError) {
        worker.execute(() -> {
            try {
                BepStreamExport.Result result = BepStreamExport.write(
                        ManagedSessionLayout.at(sessionRoot).rawDirectory(), target);
                onEventThread.accept(() -> onDone.accept(result));
            } catch (Exception failure) {
                onEventThread.accept(() -> onError.accept(failure));
            }
        });
    }

    /** Writes one table as CSV or JSON, redacted unless the caller says otherwise. */
    public void exportTable(
            Path sessionRoot,
            TableExport.Table table,
            TableExport.Format format,
            Path target,
            boolean redact,
            Consumer<TableExport.Result> onDone,
            Consumer<Throwable> onError) {
        worker.execute(() -> {
            Path database = ManagedSessionLayout.at(sessionRoot).databaseFile();
            try (Connection connection = DriverManager.getConnection(
                    "jdbc:sqlite:" + database.toAbsolutePath())) {
                Optional<Redactor> redactor = redact
                        ? Optional.of(new Redactor(policyFor(database)))
                        : Optional.empty();
                TableExport.Result result =
                        TableExport.write(connection, table, format, target, redactor);
                onEventThread.accept(() -> onDone.accept(result));
            } catch (Exception failure) {
                onEventThread.accept(() -> onError.accept(failure));
            }
        });
    }

    /**
     * The export policy with this session's own paths mapped.
     *
     * <p>Read from the session rather than guessed: the workspace and output
     * base are the two prefixes that appear on nearly every path in a build,
     * and mapping them is the difference between an export whose paths are
     * readable and one where every one of them is {@code /Users/[user]/…}.
     */
    static RedactionPolicy policyFor(Path database) {
        RedactionPolicy policy = RedactionPolicy.forExport();
        if (!Files.isRegularFile(database)) {
            return policy;
        }
        List<String> prefixes = new java.util.ArrayList<>();
        try (Connection connection = DriverManager.getConnection(
                "jdbc:sqlite:" + database.toAbsolutePath())) {
            prefixes.addAll(readPrefix(connection,
                    "SELECT workspace_directory FROM build_invocation WHERE singleton = 1"));
            prefixes.addAll(readPrefix(connection,
                    "SELECT working_directory FROM build_invocation WHERE singleton = 1"));
            prefixes.addAll(readPrefix(connection,
                    "SELECT output_base FROM profile_metadata WHERE id = 1"));
        } catch (Exception unreadable) {
            // A session whose database will not open still exports; it simply
            // maps fewer prefixes, and the account-name masking still applies.
            return policy;
        }
        // Longest first, so a workspace under a home directory maps as the
        // workspace. The redactor sorts too; doing it here keeps the names
        // stable when two prefixes are the same string.
        prefixes.sort(Comparator.comparingInt(String::length).reversed());
        int index = 0;
        for (String prefix : prefixes) {
            String placeholder = index == prefixes.size() - 1 && prefixes.size() > 1
                    ? "[output-base]"
                    : (index == 0 ? "[workspace]" : "[path" + index + "]");
            policy = policy.withPathPrefix(prefix, placeholder);
            index++;
        }
        return policy;
    }

    private static List<String> readPrefix(Connection connection, String sql) {
        try (PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet rows = statement.executeQuery()) {
            if (rows.next()) {
                String value = rows.getString(1);
                if (value != null && !value.isBlank()) {
                    return List.of(value);
                }
            }
        } catch (Exception missing) {
            // The table may not exist in an older session; that is not an error.
            return List.of();
        }
        return List.of();
    }

    private boolean confirmOnEventThread(Confirmer confirmer, RedactionReport report) {
        boolean[] answer = {false};
        java.util.concurrent.CountDownLatch decided = new java.util.concurrent.CountDownLatch(1);
        onEventThread.accept(() -> {
            try {
                answer[0] = confirmer.confirm(report);
            } finally {
                decided.countDown();
            }
        });
        try {
            decided.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
        return answer[0];
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (java.io.IOException ignored) {
            // A temporary file that will not delete is not a reason to fail an
            // export that has already succeeded; it is inside the session
            // directory, which the user owns.
        }
    }

    private static long nowMicros() {
        return System.currentTimeMillis() * 1_000L;
    }
}
