package com.holtherndon.bazelviz.enrich.fs;

import com.holtherndon.bazelviz.core.enrich.EnrichmentTask;
import com.holtherndon.bazelviz.storage.enrich.EnrichmentTaskStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Fills in output sizes by looking at the files on disk.
 *
 * <h2>Why this is opt-in and why it is last</h2>
 *
 * <p>Plan 4.1 lists it as optional, notes it "can add I/O", and — the part that
 * shapes this class — notes it "cannot cover remote-only artifacts". A build
 * run with remote execution may never materialise its outputs locally, so a
 * missing file here is a normal result and not an error. The counts this
 * returns exist so the coverage panel can say which of the two happened.
 *
 * <h2>It fills holes and never overwrites</h2>
 *
 * <p>An artifact whose size Bazel reported keeps that size. ADR-009 and Phase
 * 4's contract §4: a stat is a different measurement from a digest recorded at
 * execution time, taken later, of a file that may since have been rebuilt. It
 * is worth having when there is nothing else and is not worth preferring.
 *
 * <h2>It does not leave the output tree</h2>
 *
 * <p>Paths in a session came from a build event or an execution log — both of
 * them text this application did not write, and an imported session is
 * untrusted outright (plan 22.4). A path containing {@code ..}, or an absolute
 * path, or a symlink pointing outside the root, would make this stat a file the
 * user never built. Each candidate is resolved and checked against the root
 * before anything touches it.
 */
public final class FilesystemStatEnricher {

    private static final String SELECT_MISSING =
            "SELECT id, path FROM artifacts WHERE size_bytes IS NULL AND is_directory = 0"
                    + " ORDER BY id";
    private static final String UPDATE_SIZE =
            "UPDATE artifacts SET size_bytes = ? WHERE id = ? AND size_bytes IS NULL";

    private final Connection connection;
    private final java.util.function.LongSupplier clock;

    public FilesystemStatEnricher(Connection connection) {
        this(connection, () -> System.currentTimeMillis() * 1_000L);
    }

    FilesystemStatEnricher(Connection connection, java.util.function.LongSupplier clock) {
        this.connection = connection;
        this.clock = clock;
    }

    /**
     * Stats every artifact with no recorded size, under {@code executionRoot}.
     *
     * @param executionRoot the directory the build's relative paths are
     *     relative to — normally the workspace root, which the session records
     */
    public Result enrich(Path executionRoot) throws SQLException {
        EnrichmentTaskStore tasks = new EnrichmentTaskStore(connection);
        long taskId = tasks.begin(EnrichmentTask.Kind.FILESYSTEM_STAT,
                Optional.of(executionRoot.toString()), clock.getAsLong());

        Path root = executionRoot.toAbsolutePath().normalize();
        List<Candidate> candidates = candidates();

        long filled = 0;
        long absent = 0;
        long refused = 0;
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try (PreparedStatement update = connection.prepareStatement(UPDATE_SIZE)) {
            for (Candidate candidate : candidates) {
                Optional<Path> resolved = resolveWithin(root, candidate.path());
                if (resolved.isEmpty()) {
                    refused++;
                    continue;
                }
                OptionalLong size = sizeOf(resolved.get());
                if (size.isEmpty()) {
                    absent++;
                    continue;
                }
                update.setLong(1, size.getAsLong());
                update.setLong(2, candidate.id());
                update.addBatch();
                filled++;
            }
            update.executeBatch();
            connection.commit();
        } catch (SQLException failure) {
            try {
                connection.rollback();
            } catch (SQLException ignored) {
                // the original failure is the one worth reporting
            }
            throw failure;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }

        Result result = new Result(candidates.size(), filled, absent, refused);
        tasks.finish(taskId, EnrichmentTask.State.SUCCEEDED, Optional.of(result.summary()),
                Optional.empty(), false, List.of(), OptionalLong.of(filled),
                OptionalLong.empty(), clock.getAsLong());
        return result;
    }

    private List<Candidate> candidates() throws SQLException {
        List<Candidate> candidates = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(SELECT_MISSING);
                ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                candidates.add(new Candidate(rows.getLong("id"), rows.getString("path")));
            }
        }
        return candidates;
    }

    /**
     * The absolute path, when it really is inside {@code root}.
     *
     * <p>Checked after resolving symlinks, because a link inside the output
     * tree pointing at {@code /etc/passwd} resolves to a path that is not under
     * the root even though the link itself is. Bazel's output tree is full of
     * symlinks, so this is the ordinary case and not a hypothetical one.
     */
    static Optional<Path> resolveWithin(Path root, String relative) {
        Path candidate = Path.of(relative);
        if (candidate.isAbsolute()) {
            return Optional.empty();
        }
        Path resolved = root.resolve(candidate).normalize();
        if (!resolved.startsWith(root)) {
            return Optional.empty();
        }
        try {
            Path real = resolved.toRealPath();
            return real.startsWith(root.toRealPath()) ? Optional.of(real) : Optional.empty();
        } catch (IOException doesNotExist) {
            // Nothing there. Not a refusal — the caller counts it as absent,
            // which for a remote-only artifact is the expected answer.
            return Optional.of(resolved);
        }
    }

    private static OptionalLong sizeOf(Path file) {
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isRegularFile(file)) {
                return OptionalLong.empty();
            }
            return OptionalLong.of(Files.size(file));
        } catch (IOException unreadable) {
            return OptionalLong.empty();
        }
    }

    private record Candidate(long id, String path) {}

    /**
     * What the stat pass found.
     *
     * @param considered artifacts that had no size
     * @param filled sizes taken from disk
     * @param absent files that were not there — the normal answer for an
     *     artifact that only ever existed on a remote executor
     * @param refused paths that pointed outside the output tree and were not
     *     touched
     */
    public record Result(long considered, long filled, long absent, long refused) {

        String summary() {
            return filled + " of " + considered + " sizes read from disk";
        }

        /** True when nothing could be measured, which remote builds do. */
        public boolean nothingWasLocal() {
            return considered > 0 && filled == 0;
        }
    }
}
