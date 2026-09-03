package com.holtherndon.bazelviz.ui.events;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildEvent;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.File;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.NamedSetOfFiles;
import com.google.protobuf.ByteString;
import com.holtherndon.bazelviz.core.journal.JournalFormat.SourceKind;
import com.holtherndon.bazelviz.runner.files.DirectoryPage;
import com.holtherndon.bazelviz.runner.files.ExecutionFileSystem;
import com.holtherndon.bazelviz.runner.files.ExecutionPath;
import com.holtherndon.bazelviz.runner.files.FileContents;
import com.holtherndon.bazelviz.runner.files.FileMetadata;
import com.holtherndon.bazelviz.runner.files.FileVersion;
import com.holtherndon.bazelviz.runner.files.LocalExecutionFileSystem;
import com.holtherndon.bazelviz.runner.files.UploadMode;
import com.holtherndon.bazelviz.ui.files.WorkspaceFileAccess;
import com.holtherndon.bazelviz.ui.session.RawPayload;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class EventFileLoaderTest {

  @Test
  @DisplayName("a named set exposes protocol and local metadata without hiding inline files")
  void namedSetFilesCarryMetadata(@TempDir Path temporary) throws Exception {
    Path present = temporary.resolve("present.log");
    Files.writeString(present, "hello\n");
    BuildEvent event =
        BuildEvent.newBuilder()
            .setNamedSetOfFiles(
                NamedSetOfFiles.newBuilder()
                    .addFiles(
                        File.newBuilder()
                            .setName("present.log")
                            .setUri(present.toUri().toString())
                            .setDigest("abc123")
                            .setLength(6))
                    .addFiles(
                        File.newBuilder()
                            .setName("inline.txt")
                            .setContents(ByteString.copyFromUtf8("inline"))))
            .build();

    EventFileInspection inspection =
        EventFileLoader.load(
            7,
            new RawPayload(event.toByteArray(), SourceKind.BEP_BINARY),
            Optional.empty(),
            Optional.empty());

    assertThat(inspection.state()).isEqualTo(EventFileInspection.State.LOADED);
    assertThat(inspection.totalFiles()).isEqualTo(2);
    assertThat(inspection.files()).hasSize(2);
    EventFileInspection.FileEntry local = inspection.files().getFirst();
    assertThat(local.role()).isEqualTo("Named set");
    assertThat(local.localPath()).contains(present);
    assertThat(local.exists()).isTrue();
    assertThat(local.actualBytes()).hasValue(6);
    assertThat(local.declaredBytes()).hasValue(6);
    assertThat(local.digest()).contains("abc123");
    assertThat(inspection.files().get(1).kind()).startsWith("Inline");
    assertThat(inspection.files().get(1).localPath()).isEmpty();
  }

  @Test
  @DisplayName("execution metadata is batched and remote paths never become desktop Paths")
  void remoteMetadataUsesOneBatch(@TempDir Path workspace) throws Exception {
    Files.writeString(workspace.resolve("one.txt"), "one");
    Files.writeString(workspace.resolve("two.txt"), "two");
    CountingRemoteFiles files = new CountingRemoteFiles();
    WorkspaceFileAccess access =
        new WorkspaceFileAccess(
            files, Optional.of(files.path(workspace.toString())), Optional.empty());
    BuildEvent event =
        BuildEvent.newBuilder()
            .setNamedSetOfFiles(
                NamedSetOfFiles.newBuilder()
                    .addFiles(File.newBuilder().setName("one.txt"))
                    .addFiles(File.newBuilder().setName("two.txt")))
            .build();

    EventFileInspection inspection =
        EventFileLoader.load(
            9, new RawPayload(event.toByteArray(), SourceKind.BEP_BINARY), Optional.of(access));

    assertThat(files.statBatches).hasValue(1);
    assertThat(inspection.files())
        .hasSize(2)
        .allSatisfy(
            entry -> {
              assertThat(entry.executionPath()).isPresent();
              assertThat(entry.localPath()).isEmpty();
              assertThat(entry.metadataState()).isEqualTo(FileMetadata.State.PRESENT);
              assertThat(entry.actualBytes()).hasValue(3);
            });
  }

  /** Local bytes behind a transport that deliberately exposes no local Path capability. */
  private static final class CountingRemoteFiles implements ExecutionFileSystem {
    private final LocalExecutionFileSystem delegate = new LocalExecutionFileSystem("ssh-test");
    private final AtomicInteger statBatches = new AtomicInteger();

    @Override
    public String executionId() {
      return delegate.executionId();
    }

    @Override
    public ExecutionPath path(String value) throws IOException {
      return delegate.path(value);
    }

    @Override
    public ExecutionPath resolve(ExecutionPath base, String child) throws IOException {
      return delegate.resolve(base, child);
    }

    @Override
    public ExecutionPath canonicalize(ExecutionPath path) throws IOException {
      return delegate.canonicalize(path);
    }

    @Override
    public boolean isWithin(ExecutionPath root, ExecutionPath candidate) throws IOException {
      return delegate.isWithin(root, candidate);
    }

    @Override
    public FileMetadata stat(ExecutionPath path) throws IOException {
      return delegate.stat(path);
    }

    @Override
    public List<FileMetadata> statAll(List<ExecutionPath> paths) throws IOException {
      statBatches.incrementAndGet();
      return delegate.statAll(paths);
    }

    @Override
    public DirectoryPage list(ExecutionPath directory, Optional<String> token, int maxEntries)
        throws IOException {
      return delegate.list(directory, token, maxEntries);
    }

    @Override
    public FileContents read(ExecutionPath path, long maxBytes) throws IOException {
      return delegate.read(path, maxBytes);
    }

    @Override
    public void download(ExecutionPath source, Path destination, long maxBytes) throws IOException {
      delegate.download(source, destination, maxBytes);
    }

    @Override
    public void upload(Path source, ExecutionPath destination, long maxBytes, UploadMode mode)
        throws IOException {
      delegate.upload(source, destination, maxBytes, mode);
    }

    @Override
    public FileContents replaceAtomically(
        ExecutionPath path, FileVersion expected, byte[] replacement, long maxBytes)
        throws IOException {
      return delegate.replaceAtomically(path, expected, replacement, maxBytes);
    }
  }
}
