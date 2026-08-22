package com.holtherndon.bazelviz.core.entity;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * One artifact, as a build event's {@code File} message describes it.
 *
 * <h2>There is no single File shape</h2>
 *
 * <p>Measured across Bazel 6.5.0, 7.6.1, 8.4.1 and 9.2.0, the same message type
 * carries different fields depending on where it appears:
 *
 * <table border="1">
 *   <caption>Which fields are populated, by position</caption>
 *   <tr><th>Position</th><th>name</th><th>uri</th><th>pathPrefix</th><th>digest</th><th>length</th></tr>
 *   <tr><td>{@code namedSetOfFiles.files}</td><td>yes</td><td>yes</td><td>generated only</td><td>yes</td><td>yes</td></tr>
 *   <tr><td>{@code completed.directoryOutput}</td><td>the directory</td><td>no</td><td>yes</td><td>tree digest</td><td>no</td></tr>
 *   <tr><td>{@code action.primaryOutput}</td><td>no</td><td>yes</td><td>no</td><td>no</td><td>no</td></tr>
 *   <tr><td>{@code testResult.testActionOutput}</td><td>yes</td><td>yes</td><td>no</td><td>no</td><td>no</td></tr>
 *   <tr><td>{@code testSummary.passed}/{@code failed}</td><td>no</td><td>yes</td><td>no</td><td>no</td><td>no</td></tr>
 * </table>
 *
 * <p>So {@link #of} returns empty rather than throwing when there is no name: a
 * File carrying only a uri is not an artifact this project can identify, and
 * the two positions where that happens — an action's primary output and a test
 * summary's logs — have their own handling. A reader that required
 * {@code name}, or {@code uri}, or {@code length} would drop rows.
 *
 * <p>Test action outputs deliberately do <em>not</em> come through here. Their
 * names are {@code test.log} and {@code test.xml} with no prefix, so every test
 * in the build would produce the same two paths; they are stored as logs
 * against their attempt instead.
 *
 * <h2>Identity is the path, not the name and not the uri</h2>
 *
 * <p>{@code name} alone collides: the same name appears under two prefixes when
 * a target is built for both the target and the exec configuration. {@code uri}
 * embeds an absolute output-base path that differs by machine, and whose
 * execroot segment changed between Bazel versions. The exec-root-relative path
 * — the prefix segments joined to the name — is what survives both.
 *
 * @param path {@code pathPrefix} segments joined with {@code name} by "/"
 * @param name the {@code name} field, never empty
 * @param pathPrefix the joined prefix, empty when the message carried none
 * @param digest content digest, or a <em>tree</em> digest when {@link #directory}
 *     — the two are not comparable
 * @param sizeBytes the {@code length} field; empty when the position does not
 *     report one, which is not the same as a zero-byte file
 * @param uri display only
 * @param directory true when this came from {@code directoryOutput}
 * @param source inferred from the absence of a path prefix, which is how Bazel
 *     distinguishes a source file from a generated one — it never says so
 *     directly
 */
public record FileRef(
        String path,
        String name,
        Optional<String> pathPrefix,
        Optional<String> digest,
        OptionalLong sizeBytes,
        Optional<String> uri,
        boolean directory,
        boolean source) {

    public FileRef {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(pathPrefix, "pathPrefix");
        Objects.requireNonNull(digest, "digest");
        Objects.requireNonNull(sizeBytes, "sizeBytes");
        Objects.requireNonNull(uri, "uri");
        if (path.isEmpty()) {
            throw new IllegalArgumentException("an artifact path must not be empty");
        }
    }

    /**
     * Reads the fields of a {@code File} that is expected to identify an
     * artifact. Takes them one by one rather than as a message so this rule —
     * which is about what Bazel means, not about protobuf — stays testable and
     * stays out of the proto layer.
     *
     * @param name the {@code name} field; empty means the message cannot
     *     identify an artifact and the result is empty
     * @param pathPrefix the {@code path_prefix} segments; empty for a source file
     * @param digest the {@code digest} field, empty when absent
     * @param length the {@code length} field
     * @param uri the {@code uri} field, empty when absent
     * @param directory true when the message came from {@code directoryOutput},
     *     whose digest is a tree digest and which carries neither uri nor length
     */
    public static Optional<FileRef> of(
            String name,
            List<String> pathPrefix,
            String digest,
            long length,
            String uri,
            boolean directory) {
        if (name.isEmpty()) {
            return Optional.empty();
        }
        // Absent and empty are the same thing on the wire: proto3 omits a
        // string at its default, so "" is how the stream says "no prefix", and
        // no prefix is how it says "source file".
        String prefix = String.join("/", pathPrefix);
        String path = prefix.isEmpty() ? name : prefix + "/" + name;
        return Optional.of(new FileRef(
                path,
                name,
                optionalText(prefix),
                optionalText(digest),
                // A directoryOutput never carries a length, and reading its
                // absence as zero would report an empty directory. A named
                // set's File always does, so a zero there is a genuinely empty
                // file and is stored as one.
                directory ? OptionalLong.empty() : OptionalLong.of(length),
                optionalText(uri),
                directory,
                prefix.isEmpty()));
    }

    private static Optional<String> optionalText(String value) {
        return value.isEmpty() ? Optional.empty() : Optional.of(value);
    }

    /** The file name alone, for a column too narrow for the path. */
    public String fileName() {
        int slash = name.lastIndexOf('/');
        return slash < 0 ? name : name.substring(slash + 1);
    }
}
