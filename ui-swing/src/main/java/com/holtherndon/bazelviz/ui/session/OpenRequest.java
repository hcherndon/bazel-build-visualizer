package com.holtherndon.bazelviz.ui.session;

import com.holtherndon.bazelviz.format.portable.BvizWriter;
import com.holtherndon.bazelviz.format.session.ManagedSessionLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * What a path handed to the application is.
 *
 * <h2>Why classification is its own type</h2>
 *
 * <p>Three routes arrive at the same question — the Open menu, a command-line
 * argument, and macOS handing over a double-clicked file — and each of them
 * used to be free to guess differently. The guess is small and the consequences
 * are not: opening a {@code .bviz} as a BEP file produces a parser error about
 * a Zip header, which tells a user nothing about what they actually did.
 *
 * <h2>The extension is not the evidence, except where it is</h2>
 *
 * <p>A directory with a manifest in it is a session whatever it is called, and
 * a BEP file is binary or JSON regardless of its name — the importer detects
 * which by reading it. Only the portable archive is identified by extension,
 * because that is what the extension is for: it is the name macOS associates
 * with this application, and a file that claims to be one and is not is
 * rejected by the archive reader with a message about archives.
 */
public record OpenRequest(Kind kind, Path path) {

    public OpenRequest {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(path, "path");
    }

    /** What was opened. */
    public enum Kind {
        /** A managed session directory: open it in place. */
        SESSION_DIRECTORY,
        /** A portable {@code .bviz} archive: validate, extract, then open. */
        PORTABLE_ARCHIVE,
        /** A build event file, binary or JSON: import it into a new session. */
        BEP_FILE,
        /** Something this application has no route for. */
        UNSUPPORTED
    }

    /** Decides what {@code path} is, without opening it. */
    public static OpenRequest classify(Path path) {
        Objects.requireNonNull(path, "path");
        if (Files.isDirectory(path)) {
            return new OpenRequest(
                    ManagedSessionLayout.at(path).isManagedSession()
                            ? Kind.SESSION_DIRECTORY
                            : Kind.UNSUPPORTED,
                    path);
        }
        if (!Files.isRegularFile(path)) {
            return new OpenRequest(Kind.UNSUPPORTED, path);
        }
        String name = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        if (name.endsWith(BvizWriter.EXTENSION)) {
            return new OpenRequest(Kind.PORTABLE_ARCHIVE, path);
        }
        return new OpenRequest(Kind.BEP_FILE, path);
    }

    /** What to tell a user when there is no route. */
    public String describeUnsupported() {
        if (Files.isDirectory(path)) {
            return path + " is a directory, but not a session: it has no "
                    + ManagedSessionLayout.MANIFEST_FILE_NAME + " in it.";
        }
        return path + " is not a file this application opens. It reads build event files,"
                + " session directories, and " + BvizWriter.EXTENSION + " archives.";
    }
}
