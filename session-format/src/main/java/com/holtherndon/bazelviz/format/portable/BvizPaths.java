package com.holtherndon.bazelviz.format.portable;

import java.util.List;
import java.util.Locale;

/**
 * What an archive entry is allowed to be called.
 *
 * <h2>Zip-slip, and the defence that is not a check</h2>
 *
 * <p>Plan 22.4: "prevent zip-slip". The named attack is an entry called
 * {@code ../../../../etc/cron.d/root}, which a naive extractor resolves
 * against the destination and writes outside it. The usual defence is to
 * canonicalise the resolved path and check it still starts with the
 * destination, and that check is here — but it is the second line, not the
 * first.
 *
 * <p>The first line is an allow-list. A session archive contains a known set of
 * files in a known shape, so anything that is not one of them is refused before
 * any path arithmetic happens. That closes the whole family at once — the
 * absolute path, the Windows drive letter, the UNC path, the symlink entry, the
 * name with a NUL in it, the entry called {@code ../manifest.json} — rather
 * than closing the members somebody remembered.
 *
 * <p>It also implements plan 22.4's "never load native code from a session
 * archive" in the only way that really works: a {@code .dylib} in an archive is
 * not rejected by refusing to load it, it is rejected by never being written to
 * disk in the first place.
 */
public final class BvizPaths {

    private BvizPaths() {}

    /** The directories a session archive may contain files in. */
    private static final List<String> ALLOWED_DIRECTORIES =
            List.of("raw/", "indexes/", "checkpoints/");

    /** The files a session archive may contain at its root. */
    private static final List<String> ALLOWED_ROOT_FILES =
            List.of("manifest.json", "session.sqlite", BvizIndex.FILE_NAME,
                    "instrumentation-plan.json");

    /**
     * Checks one entry name, throwing when it may not be extracted.
     *
     * @param name the name exactly as the archive gives it
     */
    public static void requireSafe(String name) throws BvizFormatException {
        requireNoTraversal(name);
        if (!isAllowed(name)) {
            throw new BvizFormatException(
                    "entry is not part of a session: " + describe(name)
                            + ". A .bviz archive holds a manifest, a database, and files under "
                            + String.join(", ", ALLOWED_DIRECTORIES)
                            + " — anything else is refused rather than written to disk.");
        }
    }

    /**
     * The traversal checks alone, for names that write nothing.
     *
     * <p>A directory entry is never extracted — parents are created from the
     * file entries that need them — so it is not held to the allow-list. It is
     * still held to this, because a name is a name whatever the entry does.
     */
    public static void requireNoTraversal(String name) throws BvizFormatException {
        if (name == null || name.isBlank()) {
            throw new BvizFormatException("the archive contains an entry with no name");
        }
        if (name.indexOf('\0') >= 0) {
            throw new BvizFormatException("entry name contains a NUL byte: " + describe(name));
        }
        if (name.indexOf('\\') >= 0) {
            throw new BvizFormatException(
                    "entry name contains a backslash, which some extractors treat as a separator: "
                            + describe(name));
        }
        if (name.startsWith("/") || name.contains(":")) {
            throw new BvizFormatException(
                    "entry name is absolute or drive-qualified: " + describe(name));
        }
        for (String segment : name.split("/", -1)) {
            if (segment.equals("..")) {
                throw new BvizFormatException(
                        "entry name escapes the archive root: " + describe(name));
            }
            if (segment.isEmpty() && !name.endsWith("/")) {
                throw new BvizFormatException(
                        "entry name has an empty path segment: " + describe(name));
            }
            if (segment.equals(".")) {
                throw new BvizFormatException(
                        "entry name has a \".\" segment: " + describe(name));
            }
            requirePlainName(segment, name);
        }
    }

    /**
     * Every segment is letters, digits, dot, dash and underscore.
     *
     * <p>Every file a session contains is named that way, so the restriction
     * costs nothing and closes a family of problems that path arithmetic does
     * not: a control character that rewrites a terminal when the name is
     * printed, a right-to-left override that makes {@code gpj.exe} look like
     * {@code exe.jpg}, a Unicode form that normalises to a different name on
     * macOS than the one that was checked.
     */
    private static void requirePlainName(String segment, String whole)
            throws BvizFormatException {
        if (segment.isEmpty()) {
            return;
        }
        for (int i = 0; i < segment.length(); i++) {
            char character = segment.charAt(i);
            boolean plain = (character >= 'a' && character <= 'z')
                    || (character >= 'A' && character <= 'Z')
                    || (character >= '0' && character <= '9')
                    || character == '.' || character == '-' || character == '_';
            if (!plain) {
                throw new BvizFormatException(
                        "entry name contains a character no session file uses: "
                                + describe(whole));
            }
        }
    }

    private static boolean isAllowed(String name) {
        if (ALLOWED_ROOT_FILES.contains(name)) {
            return true;
        }
        for (String directory : ALLOWED_DIRECTORIES) {
            if (name.startsWith(directory) && name.length() > directory.length()) {
                return true;
            }
        }
        return false;
    }

    /** True when a session file at this relative path belongs in an archive. */
    public static boolean isExportable(String relativePath) {
        return isAllowed(relativePath.replace('\\', '/'));
    }

    /** True when this entry is one of the raw source bytes. */
    public static boolean isRawSource(String relativePath) {
        return relativePath.startsWith("raw/");
    }

    /**
     * A name safe to put in an error message.
     *
     * <p>An archive is untrusted input and its entry names are attacker-chosen
     * text. Printing one verbatim into a log or a dialog is how a terminal
     * escape sequence or a right-to-left override gets rendered; every
     * character outside a conservative set becomes an escape.
     */
    static String describe(String name) {
        StringBuilder safe = new StringBuilder(name.length() + 8).append('"');
        for (int i = 0; i < name.length() && i < 120; i++) {
            char character = name.charAt(i);
            if (character >= 0x20 && character < 0x7f && character != '"' && character != '\\') {
                safe.append(character);
            } else {
                safe.append(String.format(Locale.ROOT, "\\u%04x", (int) character));
            }
        }
        if (name.length() > 120) {
            safe.append("…");
        }
        return safe.append('"').toString();
    }
}
