package com.holtherndon.bazelviz.ui.session;

import com.holtherndon.bazelviz.core.session.SessionState;
import com.holtherndon.bazelviz.core.source.Completeness;
import com.holtherndon.bazelviz.format.session.SessionManifest;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * What the window shows about the open session, taken from its manifest.
 *
 * <p>Every count here is an {@link OptionalLong} that stays empty when the
 * manifest does not carry it. A session interrupted before finalization has no
 * event count in its manifest, and showing "0 events" for a session that holds
 * eight hundred thousand of them would be exactly the unknown-as-zero mistake
 * plan 11.4 forbids — the status bar renders an em dash instead, and the real
 * count comes from {@link SessionReader#eventCount()} once a reader is open.
 *
 * @param root the managed session directory
 * @param sessionId the session's stable identity, as text
 * @param state the state the manifest records
 * @param manifestEventCount event count as finalized into the manifest, empty
 *     when the import never reached finalization
 * @param sources one entry per capture source, with its completeness
 * @param warnings manifest warnings, shown verbatim
 */
public record SessionInfo(
        Path root,
        String sessionId,
        SessionState state,
        OptionalLong manifestEventCount,
        List<SourceInfo> sources,
        List<String> warnings) {

    public SessionInfo {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(manifestEventCount, "manifestEventCount");
        sources = List.copyOf(sources);
        warnings = List.copyOf(warnings);
    }

    /** One capture source and how much of it was read. */
    public record SourceInfo(
            String kind,
            Optional<String> path,
            Optional<String> sha256,
            OptionalLong byteSize,
            Completeness completeness) {

        public SourceInfo {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(sha256, "sha256");
            Objects.requireNonNull(byteSize, "byteSize");
            Objects.requireNonNull(completeness, "completeness");
        }
    }

    /** Reads the displayable parts of a manifest. */
    public static SessionInfo of(Path root, SessionManifest manifest) {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(manifest, "manifest");
        List<SourceInfo> sources = manifest.sources().stream()
                .map(entry -> new SourceInfo(
                        entry.kind(),
                        entry.path(),
                        entry.sha256(),
                        entry.byteSize(),
                        entry.completeness()))
                .toList();
        return new SessionInfo(
                root,
                manifest.sessionId().toString(),
                manifest.state(),
                manifest.eventCount(),
                sources,
                manifest.warnings());
    }

    /**
     * True when the session's own manifest says its data is partial. The view
     * says so out loud rather than presenting a truncated capture as if it were
     * whole.
     */
    public boolean isPartial() {
        return state == SessionState.INCOMPLETE
                || state == SessionState.CORRUPT_PARTIAL
                || state == SessionState.CANCELLED
                || sources.stream().anyMatch(source -> !source.completeness().isComplete());
    }
}
