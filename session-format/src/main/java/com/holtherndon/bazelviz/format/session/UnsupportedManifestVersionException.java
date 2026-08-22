package com.holtherndon.bazelviz.format.session;

/**
 * The manifest declares a format version this build does not know how to read.
 *
 * <p>Only thrown for versions from the <em>future</em>. Older versions are
 * migrated forward (see {@link ManifestMigrations}); a newer one cannot be, and
 * guessing at it would risk writing back a manifest that destroys whatever the
 * newer build recorded. Refusing with a version number the user can act on is
 * the honest outcome.
 */
public final class UnsupportedManifestVersionException extends SessionFormatException {

    private static final long serialVersionUID = 1L;

    private final int foundVersion;
    private final int supportedVersion;

    public UnsupportedManifestVersionException(int foundVersion, int supportedVersion, String location) {
        super("manifest at %s declares format version %d, but this build reads at most version %d; "
                        .formatted(location, foundVersion, supportedVersion)
                + "it was written by a newer version of Bazel Build Visualizer — upgrade to open it");
        this.foundVersion = foundVersion;
        this.supportedVersion = supportedVersion;
    }

    /** The version found on disk. */
    public int foundVersion() {
        return foundVersion;
    }

    /** The newest version this build understands. */
    public int supportedVersion() {
        return supportedVersion;
    }
}
