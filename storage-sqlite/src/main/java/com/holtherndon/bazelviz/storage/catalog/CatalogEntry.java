package com.holtherndon.bazelviz.storage.catalog;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * One session, as the library sees it (plan 10.6).
 *
 * <h2>Everything here is small, on purpose</h2>
 *
 * <p>Plan 10.6: "the catalog must not contain the full build data". The
 * catalog exists so the start screen can list forty sessions in a few
 * milliseconds without opening forty databases, and the moment it starts
 * carrying action rows it is a second copy of the thing it indexes — one that
 * can disagree with the session, and one that has to be migrated whenever the
 * session schema changes.
 *
 * <h2>The directory is the artifact; this is a cache of it</h2>
 *
 * <p>docs/session-format.md: catalog and manifest must agree, with the manifest
 * winning on conflict. So every field here is derived from a manifest that can
 * be read again, and {@link SessionCatalog#rescan} rebuilds the whole table
 * from the directories. That is what makes plan 24's "sessions survive
 * application restart and relocation" true: a moved sessions root invalidates
 * every path in the catalog and none of the sessions.
 *
 * @param directory where the session lives now, which changes when a user moves
 *     their sessions root
 * @param summary a short line of metrics for the card — plan 10.6's
 *     "thumbnail/summary metrics", as words rather than an image
 * @param missing the directory was not there the last time anybody looked; the
 *     row is kept so a user can see what happened rather than finding a session
 *     silently gone
 */
public record CatalogEntry(
        String sessionUuid,
        String displayName,
        Path directory,
        Optional<String> workspace,
        Optional<String> commandSummary,
        Optional<String> bazelVersion,
        String state,
        OptionalLong startedMicros,
        OptionalLong finishedMicros,
        OptionalLong actionCount,
        OptionalLong eventCount,
        OptionalLong totalBytes,
        long warningCount,
        OptionalLong lastOpenedMicros,
        boolean pinned,
        boolean missing,
        Optional<String> summary) {

    public CatalogEntry {
        Objects.requireNonNull(sessionUuid, "sessionUuid");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(directory, "directory");
        Objects.requireNonNull(workspace, "workspace");
        Objects.requireNonNull(commandSummary, "commandSummary");
        Objects.requireNonNull(bazelVersion, "bazelVersion");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(startedMicros, "startedMicros");
        Objects.requireNonNull(finishedMicros, "finishedMicros");
        Objects.requireNonNull(actionCount, "actionCount");
        Objects.requireNonNull(eventCount, "eventCount");
        Objects.requireNonNull(totalBytes, "totalBytes");
        Objects.requireNonNull(lastOpenedMicros, "lastOpenedMicros");
        Objects.requireNonNull(summary, "summary");
    }

    /** A minimal entry for a session nothing has been read from yet. */
    public static CatalogEntry of(String sessionUuid, String displayName, Path directory,
            String state) {
        return new CatalogEntry(
                sessionUuid, displayName, directory,
                Optional.empty(), Optional.empty(), Optional.empty(), state,
                OptionalLong.empty(), OptionalLong.empty(), OptionalLong.empty(),
                OptionalLong.empty(), OptionalLong.empty(), 0, OptionalLong.empty(),
                false, false, Optional.empty());
    }

    /** How long the build took, when both ends are known. */
    public OptionalLong elapsedMicros() {
        return startedMicros.isPresent() && finishedMicros.isPresent()
                ? OptionalLong.of(finishedMicros.getAsLong() - startedMicros.getAsLong())
                : OptionalLong.empty();
    }

    /** The same entry marked as opened now. */
    public CatalogEntry openedAt(long micros) {
        return new CatalogEntry(sessionUuid, displayName, directory, workspace, commandSummary,
                bazelVersion, state, startedMicros, finishedMicros, actionCount, eventCount,
                totalBytes, warningCount, OptionalLong.of(micros), pinned, missing, summary);
    }

    /** The same entry, pinned or not. */
    public CatalogEntry withPinned(boolean value) {
        return new CatalogEntry(sessionUuid, displayName, directory, workspace, commandSummary,
                bazelVersion, state, startedMicros, finishedMicros, actionCount, eventCount,
                totalBytes, warningCount, lastOpenedMicros, value, missing, summary);
    }

    /** The same entry, present or gone. */
    public CatalogEntry withMissing(boolean value) {
        return new CatalogEntry(sessionUuid, displayName, directory, workspace, commandSummary,
                bazelVersion, state, startedMicros, finishedMicros, actionCount, eventCount,
                totalBytes, warningCount, lastOpenedMicros, pinned, value, summary);
    }
}
