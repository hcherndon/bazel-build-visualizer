package com.holtherndon.bazelviz.runner.workspace;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Where a build will run and which workspace it belongs to (plan 8.2).
 *
 * <p>Both are recorded because they are genuinely different facts and the plan
 * requires each: Bazel resolves a relative target pattern like {@code :all}
 * against the <em>process working directory</em>, while the workspace root is
 * what identifies the project and anchors every path in the event stream. A
 * build launched from {@code repo/services/api} against the workspace at
 * {@code repo} is the normal case, not an error, and a session that recorded
 * only one of the two would mislocate every relative target.
 *
 * @param workingDirectory the directory the process is started in, absolute
 * @param workspaceRoot the detected workspace root, absent when no marker was
 *     found — an honest "not a workspace" rather than a directory guessed by
 *     walking to the filesystem root
 * @param marker the file that identified the root, for showing the user why
 *     this directory was chosen
 * @param detection how the root was established
 */
public record WorkspaceInfo(
        Path workingDirectory,
        Optional<Path> workspaceRoot,
        Optional<String> marker,
        Detection detection) {

    /** How the workspace root was established (plan 8.2 detection order). */
    public enum Detection {
        /** The user named it outright; no search happened. */
        USER_SELECTED,
        /** Found by walking ancestors for a recognized marker file. */
        MARKER_SEARCH,
        /** Bazel itself reported it, via {@code info workspace}. */
        BAZEL_INFO,
        /** No root was found. */
        NOT_FOUND
    }

    /**
     * Files that mark a Bazel workspace root, most authoritative first.
     *
     * <p>{@code MODULE.bazel} leads because Bazel 7 and later prefer it and
     * Bazel 9 has dropped {@code WORKSPACE} entirely; the older names stay
     * because Bazel 6 is a supported target and its repositories will not have
     * a module file. {@code WORKSPACE.bzlmod} is included since a repository
     * mid-migration has one alongside a legacy {@code WORKSPACE}.
     */
    public static final java.util.List<String> MARKERS = java.util.List.of(
            "MODULE.bazel", "REPO.bazel", "WORKSPACE.bzlmod", "WORKSPACE.bazel", "WORKSPACE");

    public WorkspaceInfo {
        Objects.requireNonNull(workingDirectory, "workingDirectory");
        workspaceRoot = Objects.requireNonNull(workspaceRoot, "workspaceRoot");
        marker = Objects.requireNonNull(marker, "marker");
        Objects.requireNonNull(detection, "detection");
    }

    /** True when the build runs below the workspace root rather than at it. */
    public boolean isSubdirectoryLaunch() {
        return workspaceRoot.map(root -> !root.equals(workingDirectory)).orElse(false);
    }

    /** The path from the workspace root to the working directory, if inside one. */
    public Optional<Path> relativePackagePath() {
        return workspaceRoot
                .filter(root -> workingDirectory.startsWith(root))
                .map(root -> root.relativize(workingDirectory));
    }
}
