package com.holtherndon.bazelviz.format.session;

import com.holtherndon.bazelviz.core.id.SessionId;
import com.holtherndon.bazelviz.core.journal.JournalFormat;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The on-disk shape of one managed session directory (plan section 10.2).
 *
 * <pre>
 * session-&lt;uuid&gt;/
 * ├── manifest.json
 * ├── session.sqlite
 * ├── raw/            bes-000001.journal, stdout.log, execution-log.bin, ...
 * ├── indexes/        action-forward.csr, timeline-lod.dat, ...
 * ├── exports/
 * ├── checkpoints/    import.ckpt
 * └── locks/          session.lock
 * </pre>
 *
 * <p>This class is the single place in the codebase that knows those names.
 * No other module constructs paths inside a session directory, so relocating or
 * renaming anything below the session root is a change to one file.
 *
 * <p><strong>Resolving is not creating.</strong> Constructing a layout touches
 * nothing; {@link #createDirectories} is the only method that writes, and it
 * creates exactly the subdirectories it is handed. The plan is explicit that
 * only relevant directories exist — an import session has no {@code exports/}
 * until something is exported, and an empty directory is a claim that work
 * happened there.
 */
public final class ManagedSessionLayout {

    /** Directory-name prefix for a managed session, per plan 10.2. */
    public static final String DIRECTORY_PREFIX = "session-";

    public static final String MANIFEST_FILE_NAME = "manifest.json";
    public static final String DATABASE_FILE_NAME = "session.sqlite";

    /** Resumable import position, written atomically (phase-1 contract section 2). */
    public static final String IMPORT_CHECKPOINT_FILE_NAME = "import.ckpt";

    /** The in-use marker inspected by {@link SessionLock}. */
    public static final String LOCK_FILE_NAME = "session.lock";

    /**
     * The instrumentation plan as shown to the user before launch (ADR-007).
     *
     * <p>Kept as its own file rather than folded into the manifest because it is
     * the evidence for a claim the manifest only summarizes: the manifest says
     * which flags were injected, and this says what each one was for, what it
     * cost, which conflicts the user resolved and how. A session that cannot
     * answer "why was my build run with these extra flags" is not transparent,
     * whatever the manifest lists.
     */
    public static final String INSTRUMENTATION_PLAN_FILE_NAME = "instrumentation-plan.json";

    /** Console output of the launched build, verbatim (plan 10.2, ADR-004). */
    public static final String STDOUT_LOG_FILE_NAME = "stdout.log";

    public static final String STDERR_LOG_FILE_NAME = "stderr.log";

    /** The subdirectories a session may contain. */
    public enum SessionDirectory {
        /** Bytes exactly as received: journal segments, stdout, profiles (ADR-004). */
        RAW("raw"),
        /** Derived, rebuildable index files. */
        INDEXES("indexes"),
        /** Output of export operations. */
        EXPORTS("exports"),
        /** Resume points for interrupted import and normalization. */
        CHECKPOINTS("checkpoints"),
        /** In-use markers. */
        LOCKS("locks");

        private final String directoryName;

        SessionDirectory(String directoryName) {
            this.directoryName = directoryName;
        }

        public String directoryName() {
            return directoryName;
        }
    }

    /**
     * What a freshly created session needs before anything is captured: somewhere
     * to put raw bytes, somewhere to record a resume point, and somewhere to
     * declare it is in use. {@code indexes/} and {@code exports/} are created
     * when there is something to put in them.
     */
    public static final Set<SessionDirectory> CAPTURE_DIRECTORIES =
            Set.of(SessionDirectory.RAW, SessionDirectory.CHECKPOINTS, SessionDirectory.LOCKS);

    private final Path root;

    private ManagedSessionLayout(Path root) {
        this.root = Objects.requireNonNull(root, "root").normalize();
    }

    /** A layout over an existing or future session root. Performs no I/O. */
    public static ManagedSessionLayout at(Path sessionRoot) {
        return new ManagedSessionLayout(sessionRoot);
    }

    /** The layout for {@code id} beneath a sessions root. Performs no I/O. */
    public static ManagedSessionLayout forSession(Path sessionsRoot, SessionId id) {
        return new ManagedSessionLayout(sessionsRoot.resolve(directoryName(id)));
    }

    /** The conventional directory name for a session: {@code session-<uuid>}. */
    public static String directoryName(SessionId id) {
        return DIRECTORY_PREFIX + id.value();
    }

    /**
     * The session id encoded in a directory name, or empty when the name does not
     * follow the convention. Used to scan a sessions root cheaply; the manifest
     * remains authoritative for identity, and a mismatch is reported rather than
     * reconciled.
     */
    public static Optional<SessionId> sessionIdFromDirectoryName(Path sessionRoot) {
        Path fileName = sessionRoot.getFileName();
        if (fileName == null) {
            return Optional.empty();
        }
        String name = fileName.toString();
        if (!name.regionMatches(true, 0, DIRECTORY_PREFIX, 0, DIRECTORY_PREFIX.length())) {
            return Optional.empty();
        }
        String uuid = name.substring(DIRECTORY_PREFIX.length());
        try {
            return Optional.of(new SessionId(UUID.fromString(uuid.toLowerCase(Locale.ROOT))));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    // ------------------------------------------------------------- accessors

    public Path root() {
        return root;
    }

    public Path manifestFile() {
        return root.resolve(MANIFEST_FILE_NAME);
    }

    /** The per-session SQLite database (ADR-005). This class does not open it. */
    public Path databaseFile() {
        return root.resolve(DATABASE_FILE_NAME);
    }

    public Path directory(SessionDirectory which) {
        return root.resolve(which.directoryName());
    }

    public Path rawDirectory() {
        return directory(SessionDirectory.RAW);
    }

    public Path indexesDirectory() {
        return directory(SessionDirectory.INDEXES);
    }

    public Path exportsDirectory() {
        return directory(SessionDirectory.EXPORTS);
    }

    public Path checkpointsDirectory() {
        return directory(SessionDirectory.CHECKPOINTS);
    }

    public Path locksDirectory() {
        return directory(SessionDirectory.LOCKS);
    }

    /** Journal segment {@code index}, named by the frozen journal contract. */
    public Path journalSegment(int segmentIndex) {
        if (segmentIndex < 0) {
            throw new IllegalArgumentException("segment index must not be negative: " + segmentIndex);
        }
        return rawDirectory().resolve(JournalFormat.segmentFileName(segmentIndex));
    }

    /** Where the instrumentation plan is written, at the session root. */
    public Path instrumentationPlanFile() {
        return root.resolve(INSTRUMENTATION_PLAN_FILE_NAME);
    }

    /** Captured standard output of the launched build. */
    public Path stdoutLog() {
        return rawDirectory().resolve(STDOUT_LOG_FILE_NAME);
    }

    /** Captured standard error of the launched build. */
    public Path stderrLog() {
        return rawDirectory().resolve(STDERR_LOG_FILE_NAME);
    }

    public Path importCheckpointFile() {
        return checkpointsDirectory().resolve(IMPORT_CHECKPOINT_FILE_NAME);
    }

    public Path lockFile() {
        return locksDirectory().resolve(LOCK_FILE_NAME);
    }

    // ---------------------------------------------------------------- queries

    /**
     * Whether this directory is a managed session, by the rule the format
     * detector uses (phase-1 contract section 6): it contains a manifest.
     */
    public boolean isManagedSession() {
        return Files.isRegularFile(manifestFile());
    }

    /** Which subdirectories currently exist. Never creates anything. */
    public Set<SessionDirectory> existingDirectories() {
        EnumSet<SessionDirectory> present = EnumSet.noneOf(SessionDirectory.class);
        for (SessionDirectory candidate : SessionDirectory.values()) {
            if (Files.isDirectory(directory(candidate))) {
                present.add(candidate);
            }
        }
        return present;
    }

    // --------------------------------------------------------------- creation

    /** Creates the session root and nothing else. Idempotent. */
    public Path createRoot() throws IOException {
        return Files.createDirectories(root);
    }

    /**
     * Creates the session root plus exactly the named subdirectories, and no
     * others. Idempotent: an already-present directory is left alone.
     *
     * @return the session root
     */
    public Path createDirectories(Set<SessionDirectory> which) throws IOException {
        Files.createDirectories(root);
        for (SessionDirectory directory : which) {
            Files.createDirectories(directory(directory));
        }
        return root;
    }

    /** @see #createDirectories(Set) */
    public Path createDirectories(SessionDirectory... which) throws IOException {
        return createDirectories(which.length == 0 ? Set.of() : EnumSet.of(which[0], which));
    }

    /**
     * Creates a subdirectory on demand, for the moment a session first needs one
     * it did not need at creation — the first export, the first built index.
     */
    public Path createDirectory(SessionDirectory which) throws IOException {
        return Files.createDirectories(directory(which));
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ManagedSessionLayout layout && root.equals(layout.root);
    }

    @Override
    public int hashCode() {
        return root.hashCode();
    }

    @Override
    public String toString() {
        return "ManagedSessionLayout[" + root + "]";
    }
}
