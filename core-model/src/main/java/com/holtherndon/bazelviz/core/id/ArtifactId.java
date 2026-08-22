package com.holtherndon.bazelviz.core.id;

import java.util.Objects;

/**
 * A file the build produced or consumed, identified by its path (plan 11.1,
 * 11.3).
 *
 * <h2>Path, not digest</h2>
 *
 * <p>A digest would be a better identity in principle — it is what actually
 * distinguishes two files — and it is unusable as one here, because the BEP
 * does not always carry it. A local build frequently reports files with no
 * digest at all, and an identity that is absent for half the artifacts is not
 * an identity.
 *
 * <p>So the path identifies the artifact and the digest is recorded as an
 * attribute of it. The consequence is stated rather than hidden: two builds
 * that wrote different bytes to the same path within one session are one
 * artifact here, and the digests attached to it will differ. That is visible in
 * the inspector rather than silently reconciled.
 *
 * <p>The path is the one the event carried, joined from Bazel's
 * {@code path_prefix} components and {@code name}. It is not resolved against
 * the filesystem and no file is opened — plan 11.3 says not to read artifact
 * contents, and this type is the reason nothing needs to.
 */
public record ArtifactId(String path) {

    public ArtifactId {
        Objects.requireNonNull(path, "path");
        if (path.isEmpty()) {
            throw new IllegalArgumentException("an artifact path must not be empty");
        }
    }

    /**
     * The artifact a BEP {@code File} names.
     *
     * @param pathPrefix the file's {@code path_prefix} components, in order
     * @param name the file's {@code name}
     */
    public static ArtifactId ofFile(java.util.List<String> pathPrefix, String name) {
        Objects.requireNonNull(pathPrefix, "pathPrefix");
        Objects.requireNonNull(name, "name");
        if (pathPrefix.isEmpty()) {
            return new ArtifactId(name);
        }
        return new ArtifactId(String.join("/", pathPrefix) + "/" + name);
    }

    /** The last path component, for a table that cannot show the whole path. */
    public String fileName() {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    @Override
    public String toString() {
        return path;
    }
}
