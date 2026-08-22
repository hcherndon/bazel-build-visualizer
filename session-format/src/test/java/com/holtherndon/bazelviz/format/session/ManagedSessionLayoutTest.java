package com.holtherndon.bazelviz.format.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.holtherndon.bazelviz.core.id.SessionId;
import com.holtherndon.bazelviz.format.session.ManagedSessionLayout.SessionDirectory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManagedSessionLayoutTest {

    private static final SessionId ID =
            new SessionId(UUID.fromString("11111111-2222-3333-4444-555555555555"));

    @TempDir
    Path root;

    @Test
    void resolvingALayoutTouchesNothing() {
        ManagedSessionLayout layout = ManagedSessionLayout.forSession(root, ID);

        assertThat(layout.root()).isEqualTo(root.resolve("session-" + ID.value()));
        assertThat(Files.exists(layout.root())).isFalse();
        assertThat(layout.existingDirectories()).isEmpty();
        assertThat(layout.isManagedSession()).isFalse();
    }

    @Test
    void accessorsMatchThePlannedLayout() {
        ManagedSessionLayout layout = ManagedSessionLayout.forSession(root, ID);
        Path sessionRoot = layout.root();

        assertThat(layout.manifestFile()).isEqualTo(sessionRoot.resolve("manifest.json"));
        assertThat(layout.databaseFile()).isEqualTo(sessionRoot.resolve("session.sqlite"));
        assertThat(layout.rawDirectory()).isEqualTo(sessionRoot.resolve("raw"));
        assertThat(layout.indexesDirectory()).isEqualTo(sessionRoot.resolve("indexes"));
        assertThat(layout.exportsDirectory()).isEqualTo(sessionRoot.resolve("exports"));
        assertThat(layout.checkpointsDirectory()).isEqualTo(sessionRoot.resolve("checkpoints"));
        assertThat(layout.locksDirectory()).isEqualTo(sessionRoot.resolve("locks"));
        assertThat(layout.lockFile()).isEqualTo(sessionRoot.resolve("locks").resolve("session.lock"));
        assertThat(layout.importCheckpointFile())
                .isEqualTo(sessionRoot.resolve("checkpoints").resolve("import.ckpt"));
    }

    @Test
    void journalSegmentsFollowTheFrozenNamingContract() {
        ManagedSessionLayout layout = ManagedSessionLayout.forSession(root, ID);

        assertThat(layout.journalSegment(0)).isEqualTo(layout.rawDirectory().resolve("bes-000000.journal"));
        assertThat(layout.journalSegment(42)).isEqualTo(layout.rawDirectory().resolve("bes-000042.journal"));
        assertThatThrownBy(() -> layout.journalSegment(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createsOnlyTheDirectoriesItIsAskedFor() throws IOException {
        ManagedSessionLayout layout = ManagedSessionLayout.forSession(root, ID);

        layout.createDirectories(Set.of(SessionDirectory.RAW, SessionDirectory.LOCKS));

        // An empty exports/ would be a claim that an export happened. Plan 10.2:
        // only create what is relevant.
        assertThat(layout.existingDirectories())
                .containsExactlyInAnyOrder(SessionDirectory.RAW, SessionDirectory.LOCKS);
        assertThat(Files.exists(layout.indexesDirectory())).isFalse();
        assertThat(Files.exists(layout.exportsDirectory())).isFalse();
        assertThat(Files.exists(layout.checkpointsDirectory())).isFalse();
    }

    @Test
    void creationIsIdempotentAndPreservesContent() throws IOException {
        ManagedSessionLayout layout = ManagedSessionLayout.forSession(root, ID);
        layout.createDirectories(Set.of(SessionDirectory.RAW));
        Path segment = layout.journalSegment(1);
        Files.writeString(segment, "already here");

        layout.createDirectories(Set.of(SessionDirectory.RAW));
        layout.createRoot();
        layout.createDirectories(Set.of(SessionDirectory.RAW));

        assertThat(Files.readString(segment)).isEqualTo("already here");
        assertThat(layout.existingDirectories()).containsExactly(SessionDirectory.RAW);
    }

    @Test
    void createRootCreatesTheRootAndNothingBelowIt() throws IOException {
        ManagedSessionLayout layout = ManagedSessionLayout.forSession(root, ID);

        layout.createRoot();

        assertThat(Files.isDirectory(layout.root())).isTrue();
        assertThat(layout.existingDirectories()).isEmpty();
    }

    @Test
    void subdirectoriesCanBeAddedLaterWhenFirstNeeded() throws IOException {
        ManagedSessionLayout layout = ManagedSessionLayout.forSession(root, ID);
        layout.createDirectories(ManagedSessionLayout.CAPTURE_DIRECTORIES);

        layout.createDirectory(SessionDirectory.EXPORTS);

        assertThat(layout.existingDirectories()).contains(SessionDirectory.EXPORTS);
    }

    @Test
    void aDirectoryIsAManagedSessionExactlyWhenItHasAManifest() throws IOException {
        ManagedSessionLayout layout = ManagedSessionLayout.forSession(root, ID);
        layout.createRoot();
        assertThat(layout.isManagedSession()).isFalse();

        Files.writeString(layout.manifestFile(), "{}");

        assertThat(layout.isManagedSession()).isTrue();
    }

    @Test
    void readsTheSessionIdBackOutOfTheDirectoryName() {
        Path directory = root.resolve(ManagedSessionLayout.directoryName(ID));

        assertThat(ManagedSessionLayout.sessionIdFromDirectoryName(directory)).contains(ID);
        assertThat(ManagedSessionLayout.sessionIdFromDirectoryName(root.resolve("not-a-session"))).isEmpty();
        assertThat(ManagedSessionLayout.sessionIdFromDirectoryName(root.resolve("session-not-a-uuid"))).isEmpty();
    }
}
