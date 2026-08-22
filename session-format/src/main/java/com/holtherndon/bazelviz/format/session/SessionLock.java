package com.holtherndon.bazelviz.format.session;

import com.holtherndon.bazelviz.format.session.json.JsonException;
import com.holtherndon.bazelviz.format.session.json.JsonReader;
import com.holtherndon.bazelviz.format.session.json.JsonValue;
import com.holtherndon.bazelviz.format.session.json.JsonValue.JsonNumber;
import com.holtherndon.bazelviz.format.session.json.JsonValue.JsonObject;
import com.holtherndon.bazelviz.format.session.json.JsonValue.JsonString;
import com.holtherndon.bazelviz.format.session.json.JsonWriter;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * The {@code locks/session.lock} marker that says a session is in use.
 *
 * <p>A session directory is a single-writer artifact: two processes appending to
 * the same journal or writing the same manifest will corrupt it. This lock
 * exists to prevent that. It is <em>not</em> an OS file lock — those behave
 * badly on the network and container filesystems a session directory can easily
 * live on — but a small JSON record naming the process that holds it.
 *
 * <p>Which means the hard problem is the one this class is really about: a lock
 * left behind by a process that was killed must not brick the session forever.
 *
 * <h2>Staleness rule</h2>
 *
 * Given a lock record, {@link #inspect} decides as follows, and never on elapsed
 * time alone — a wall-clock timeout would either break a live eight-hour capture
 * or leave a crashed one locked for hours:
 *
 * <ol>
 *   <li>The lock file is absent → {@link Status#FREE}.</li>
 *   <li>It is unparseable or missing a required member → {@link Status#STALE}.
 *       A record that cannot be read cannot be honoured; the alternative is an
 *       unopenable session.</li>
 *   <li>Its host differs from this host → {@link Status#HELD_ELSEWHERE}. This
 *       machine has no way to ask about a process on another one, and a
 *       shared-filesystem session really may be open there. Not stale; breaking
 *       it requires an explicit human decision via {@link #breakLock}.</li>
 *   <li>Same host, and the pid and process start time match this JVM →
 *       {@link Status#HELD_BY_THIS_PROCESS}.</li>
 *   <li>Same host, and {@link ProcessHandle#of} finds no live process for the
 *       pid → {@link Status#STALE}.</li>
 *   <li>Same host, the pid is alive, and both the record and the live process
 *       report a start time that differ by more than {@value #START_TIME_TOLERANCE_MICROS}
 *       microseconds → {@link Status#STALE}. The pid was recycled onto an
 *       unrelated process; without this check a session stays locked until the
 *       machine reboots.</li>
 *   <li>Otherwise → {@link Status#HELD_LIVE}.</li>
 * </ol>
 *
 * <p>The start-time tolerance is a second, not zero, because the recorded and
 * observed values can come from different reads of a clock with coarse
 * granularity. It is far shorter than any realistic pid-recycling interval, so
 * it does not weaken the reuse check in practice.
 *
 * <p>When either side reports no start time at all — some platforms decline —
 * the lock is treated as {@link Status#HELD_LIVE}. Refusing to open a session
 * that might genuinely be in use is the safe direction to be wrong in; the
 * missing value is recorded as absent, never as zero.
 */
public final class SessionLock implements AutoCloseable {

    /** Tolerance when comparing recorded and observed process start times. */
    public static final long START_TIME_TOLERANCE_MICROS = 1_000_000L;

    /** Version of the lock record format. */
    public static final int LOCK_FORMAT_VERSION = 1;

    private static final String KEY_FORMAT_VERSION = "formatVersion";
    private static final String KEY_HOST = "host";
    private static final String KEY_PID = "pid";
    private static final String KEY_PROCESS_START_MICROS = "processStartMicros";
    private static final String KEY_ACQUIRED_MICROS = "acquiredMicros";
    private static final String KEY_OWNER = "owner";

    /** What a lock file currently means. */
    public enum Status {
        /** No lock file. The session can be opened. */
        FREE,
        /** Held by a live process on this machine. Do not open. */
        HELD_LIVE,
        /** Held by this very JVM. */
        HELD_BY_THIS_PROCESS,
        /** The owning process is gone, or the file is unreadable. Safe to break. */
        STALE,
        /** Held by a process on another host; liveness cannot be determined here. */
        HELD_ELSEWHERE
    }

    /** The contents of a lock file. */
    public record LockRecord(
            int formatVersion,
            String host,
            long pid,
            OptionalLong processStartMicros,
            long acquiredMicros,
            Optional<String> owner) {

        public LockRecord {
            java.util.Objects.requireNonNull(host, "host");
            processStartMicros = java.util.Objects.requireNonNull(processStartMicros, "processStartMicros");
            owner = java.util.Objects.requireNonNull(owner, "owner");
        }
    }

    /** The result of examining a lock file, with a message fit for a user. */
    public record LockState(Status status, Optional<LockRecord> record, String detail) {

        public boolean isBreakable() {
            return status == Status.FREE || status == Status.STALE;
        }
    }

    private final Path lockFile;
    private final LockRecord held;
    private volatile boolean released;

    private SessionLock(Path lockFile, LockRecord held) {
        this.lockFile = lockFile;
        this.held = held;
    }

    /** The record this process wrote. */
    public LockRecord record() {
        return held;
    }

    public Path lockFile() {
        return lockFile;
    }

    public boolean isReleased() {
        return released;
    }

    // -------------------------------------------------------------- inspect

    /** Examines the lock for a session without modifying anything. */
    public static LockState inspect(ManagedSessionLayout layout) throws IOException {
        return inspect(layout.lockFile(), currentHost());
    }

    static LockState inspect(Path lockFile, String thisHost) throws IOException {
        JsonValue document;
        try (Reader reader = Files.newBufferedReader(lockFile, StandardCharsets.UTF_8)) {
            document = JsonReader.parse(reader);
        } catch (NoSuchFileException e) {
            return new LockState(Status.FREE, Optional.empty(), "no lock file at " + lockFile);
        } catch (JsonException e) {
            return new LockState(Status.STALE, Optional.empty(),
                    "lock file " + lockFile + " is not readable JSON (" + e.getMessage()
                            + "); treating it as abandoned");
        }
        LockRecord record;
        try {
            record = decode(document);
        } catch (JsonException e) {
            return new LockState(Status.STALE, Optional.empty(),
                    "lock file " + lockFile + " is missing required fields (" + e.getMessage()
                            + "); treating it as abandoned");
        }
        return evaluate(record, thisHost);
    }

    private static LockState evaluate(LockRecord record, String thisHost) {
        if (!record.host().equals(thisHost)) {
            return new LockState(Status.HELD_ELSEWHERE, Optional.of(record),
                    "held by pid " + record.pid() + " on host '" + record.host()
                            + "'; this host cannot tell whether that process is alive");
        }
        ProcessHandle self = ProcessHandle.current();
        OptionalLong selfStart = startMicros(self);
        if (record.pid() == self.pid() && startsMatch(record.processStartMicros(), selfStart)) {
            return new LockState(Status.HELD_BY_THIS_PROCESS, Optional.of(record),
                    "held by this process (pid " + record.pid() + ")");
        }
        Optional<ProcessHandle> owner = ProcessHandle.of(record.pid());
        if (owner.isEmpty() || !owner.get().isAlive()) {
            return new LockState(Status.STALE, Optional.of(record),
                    "owning process " + record.pid() + " is no longer running");
        }
        OptionalLong observedStart = startMicros(owner.get());
        if (record.processStartMicros().isPresent()
                && observedStart.isPresent()
                && !startsMatch(record.processStartMicros(), observedStart)) {
            return new LockState(Status.STALE, Optional.of(record),
                    "pid " + record.pid() + " is in use by a process started at a different time; "
                            + "the pid was recycled and the original owner is gone");
        }
        return new LockState(Status.HELD_LIVE, Optional.of(record),
                "held by live process " + record.pid() + " on this host");
    }

    private static boolean startsMatch(OptionalLong recorded, OptionalLong observed) {
        if (recorded.isEmpty() || observed.isEmpty()) {
            // Unknown on either side: cannot disprove ownership, so do not.
            return true;
        }
        return Math.abs(recorded.getAsLong() - observed.getAsLong()) <= START_TIME_TOLERANCE_MICROS;
    }

    private static OptionalLong startMicros(ProcessHandle handle) {
        return handle.info().startInstant().map(SessionLock::toMicros)
                .map(OptionalLong::of)
                .orElseGet(OptionalLong::empty);
    }

    // -------------------------------------------------------------- acquire

    /**
     * Takes the lock, creating {@code locks/} if needed.
     *
     * @param owner a short description of what holds it, shown to the user
     * @param breakStale when true a {@link Status#STALE} lock is removed and
     *     replaced; when false any existing lock fails the call
     * @throws SessionLockedException when the session is held by someone else
     */
    public static SessionLock acquire(ManagedSessionLayout layout, String owner, boolean breakStale)
            throws IOException {
        return acquire(layout, owner, breakStale, toMicros(Clock.systemUTC().instant()), currentHost());
    }

    static SessionLock acquire(
            ManagedSessionLayout layout, String owner, boolean breakStale, long acquiredMicros, String host)
            throws IOException {
        Path lockFile = layout.lockFile();
        Files.createDirectories(lockFile.getParent());

        LockState state = inspect(lockFile, host);
        switch (state.status()) {
            case FREE -> {
                // Nothing to clear.
            }
            case STALE -> {
                if (!breakStale) {
                    throw new SessionLockedException(lockFile, state);
                }
                Files.deleteIfExists(lockFile);
            }
            case HELD_LIVE, HELD_BY_THIS_PROCESS, HELD_ELSEWHERE -> throw new SessionLockedException(lockFile, state);
        }

        ProcessHandle self = ProcessHandle.current();
        LockRecord record = new LockRecord(
                LOCK_FORMAT_VERSION,
                host,
                self.pid(),
                startMicros(self),
                acquiredMicros,
                Optional.ofNullable(owner));

        byte[] content = JsonWriter.writePretty(encode(record)).getBytes(StandardCharsets.UTF_8);
        try {
            // CREATE_NEW so two processes racing here cannot both believe they won.
            Files.write(lockFile, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (FileAlreadyExistsException e) {
            throw new SessionLockedException(lockFile, inspect(lockFile, host));
        }
        return new SessionLock(lockFile, record);
    }

    /**
     * Removes a lock unconditionally. Only for a user who has been shown
     * {@link LockState#detail()} and decided the owner is really gone; routine
     * cleanup goes through {@code breakStale} on {@link #acquire}.
     */
    public static boolean breakLock(ManagedSessionLayout layout) throws IOException {
        return Files.deleteIfExists(layout.lockFile());
    }

    /**
     * Releases the lock, but only if the file still holds <em>this</em> record.
     * Someone else's lock is left alone: having already lost ownership once (to a
     * stale-lock break, say), deleting the new owner's file would be worse than
     * leaking ours.
     */
    @Override
    public void close() throws IOException {
        if (released) {
            return;
        }
        released = true;
        try (Reader reader = Files.newBufferedReader(lockFile, StandardCharsets.UTF_8)) {
            LockRecord current = decode(JsonReader.parse(reader));
            if (current.pid() != held.pid()
                    || current.acquiredMicros() != held.acquiredMicros()
                    || !current.host().equals(held.host())) {
                return;
            }
        } catch (NoSuchFileException e) {
            return;
        } catch (JsonException e) {
            // Corrupt now, but it is ours to clean up.
        }
        Files.deleteIfExists(lockFile);
    }

    // --------------------------------------------------------------- codec

    private static JsonObject encode(LockRecord record) {
        Map<String, JsonValue> members = new LinkedHashMap<>();
        members.put(KEY_FORMAT_VERSION, JsonNumber.of(record.formatVersion()));
        members.put(KEY_HOST, new JsonString(record.host()));
        members.put(KEY_PID, JsonNumber.of(record.pid()));
        record.processStartMicros()
                .ifPresent(micros -> members.put(KEY_PROCESS_START_MICROS, JsonNumber.of(micros)));
        members.put(KEY_ACQUIRED_MICROS, JsonNumber.of(record.acquiredMicros()));
        record.owner().ifPresent(owner -> members.put(KEY_OWNER, new JsonString(owner)));
        return new JsonObject(members);
    }

    private static LockRecord decode(JsonValue document) {
        if (!(document instanceof JsonObject object)) {
            throw new JsonException("a lock record must be a JSON object");
        }
        return new LockRecord(
                intMember(object, KEY_FORMAT_VERSION),
                stringMember(object, KEY_HOST),
                longMember(object, KEY_PID),
                optionalLongMember(object, KEY_PROCESS_START_MICROS),
                longMember(object, KEY_ACQUIRED_MICROS),
                object.member(KEY_OWNER)
                        .map(value -> value instanceof JsonString string ? string.value() : null)
                        .filter(java.util.Objects::nonNull));
    }

    private static String stringMember(JsonObject object, String key) {
        JsonValue value = object.member(key).orElseThrow(() -> new JsonException("missing '" + key + "'"));
        if (value instanceof JsonString string) {
            return string.value();
        }
        throw new JsonException("'" + key + "' must be a string");
    }

    private static long longMember(JsonObject object, String key) {
        JsonValue value = object.member(key).orElseThrow(() -> new JsonException("missing '" + key + "'"));
        if (value instanceof JsonNumber number) {
            return number.asLong();
        }
        throw new JsonException("'" + key + "' must be a number");
    }

    private static int intMember(JsonObject object, String key) {
        return Math.toIntExact(longMember(object, key));
    }

    private static OptionalLong optionalLongMember(JsonObject object, String key) {
        Optional<JsonValue> member = object.member(key);
        if (member.isEmpty()) {
            return OptionalLong.empty();
        }
        if (member.get() instanceof JsonNumber number) {
            return OptionalLong.of(number.asLong());
        }
        throw new JsonException("'" + key + "' must be a number");
    }

    // --------------------------------------------------------------- helpers

    /** Best-effort host identity, used only to decide whether pids are meaningful. */
    public static String currentHost() {
        String fromEnv = firstNonBlank(System.getenv("HOSTNAME"), System.getenv("COMPUTERNAME"));
        if (fromEnv != null) {
            return fromEnv;
        }
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException | UncheckedIOException e) {
            // A host we cannot name is one whose pids we should not trust across
            // machines; a stable sentinel keeps that comparison conservative.
            return "unknown-host";
        }
    }

    private static String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return null;
    }

    static long toMicros(Instant instant) {
        return Math.addExact(Math.multiplyExact(instant.getEpochSecond(), 1_000_000L), instant.getNano() / 1_000);
    }
}
