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
     * <p>Each of these four is independently sufficient on every version from
     * 6.5 to 9.2, verified by building in a directory containing only one of
     * them at a time. That includes a {@code WORKSPACE}-only directory on Bazel
     * 9.2, which still builds despite {@code WORKSPACE} being deprecated — so
     * no version-conditional marker logic is needed, and adding some would make
     * this tool refuse to open repositories Bazel itself is happy with.
     *
     * <p>{@code WORKSPACE.bzlmod} is deliberately absent. It looks like a
     * marker and is not one: Bazel does not treat it as a workspace root, and a
     * repository mid-migration has one in a directory that is already marked by
     * something else. Including it would find a "root" one level too deep in
     * exactly the repositories most likely to be complicated.
     */
    public static final java.util.List<String> MARKERS = java.util.List.of(
            "MODULE.bazel", "REPO.bazel", "WORKSPACE.bazel", "WORKSPACE");

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
