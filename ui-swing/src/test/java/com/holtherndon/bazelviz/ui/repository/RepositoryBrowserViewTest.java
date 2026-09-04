package com.holtherndon.bazelviz.ui.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.runner.files.DirectoryPage;
import com.holtherndon.bazelviz.runner.files.ExecutionFileSystem;
import com.holtherndon.bazelviz.runner.files.ExecutionPath;
import com.holtherndon.bazelviz.runner.files.FileContents;
import com.holtherndon.bazelviz.runner.files.FileMetadata;
import com.holtherndon.bazelviz.runner.files.FileVersion;
import com.holtherndon.bazelviz.runner.files.UploadMode;
import com.holtherndon.bazelviz.ui.theme.PageToolbar;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The repository tree's bounded, lazy filesystem interaction. */
final class RepositoryBrowserViewTest {

  @Test
  @DisplayName("only the root is listed until a directory expands")
  void loadsOneDirectoryAtATimeAndIgnoresOrdinarySymlinks() throws Exception {
    ExecutionPath root = path("/workspace");
    ExecutionPath directory = path("/workspace/lib");
    ExecutionPath file = path("/workspace/BUILD.bazel");
    ExecutionPath link = path("/workspace/vendor");
    FakeFileSystem files =
        new FakeFileSystem(
            "ssh:builder",
            root,
            List.of(
                metadata(directory, FileMetadata.Kind.DIRECTORY, OptionalLong.empty()),
                metadata(file, FileMetadata.Kind.REGULAR_FILE, OptionalLong.of(42)),
                metadata(link, FileMetadata.Kind.SYMBOLIC_LINK, OptionalLong.empty())));
    files.put(
        directory,
        List.of(
            metadata(
                path("/workspace/lib/BUILD.bazel"),
                FileMetadata.Kind.REGULAR_FILE,
                OptionalLong.of(7))));
    AtomicReference<ExecutionPath> opened = new AtomicReference<>();
    RepositoryBrowserView view = onEdt(RepositoryBrowserView::new);
    PageToolbar toolbar = onEdt(() -> new PageToolbar("Browse Repository"));

    onEdt(
        () -> {
          view.installPageToolbar(toolbar);
          view.onOpenFile(opened::set);
          view.openRepository("builder.example", files, root);
          return null;
        });
    await(() -> onEdt(() -> view.visibleEntriesForTest() == 3));

    assertThat(files.calls(root)).isEqualTo(1);
    assertThat(files.calls(directory)).isZero();
    assertThat(files.edtCall).isFalse();
    assertThat(onEdt(toolbar::actionCount)).isOne();
    assertThat(onEdt(toolbar::metadata)).isEqualTo("/workspace");
    assertThat(onEdt(view::locationForTest)).isEqualTo("builder.example · /workspace");
    assertThat(onEdt(view::rootChildrenForTest))
        .containsExactly("lib/", "BUILD.bazel · 42 bytes", "vendor · symbolic link");

    onEdt(
        () -> {
          view.expandRootChildForTest(0);
          view.expandRootChildForTest(2);
          view.openRootChildForTest(1);
          return null;
        });
    await(() -> files.calls(directory) == 1);
    await(() -> onEdt(() -> view.visibleEntriesForTest() == 4));

    assertThat(files.calls(link)).isZero();
    assertThat(opened).hasValue(file);
    assertThat(files.edtCall).isFalse();
    onEdt(
        () -> {
          view.close();
          return null;
        });
  }

  @Test
  @DisplayName("only exact root Bazel convenience links are recognized")
  void recognizesBazelDirectoryLinksWithoutBroadSymlinkTraversal() {
    for (String name :
        List.of("bazel-out", "bazel-bin", "bazel-testlogs", "bazel-genfiles", "bazel-workspace")) {
      assertThat(
              BazelOutputSymlink.isCandidate(
                  FileMetadata.Kind.SYMBOLIC_LINK, name, true, "workspace"))
          .as(name)
          .isTrue();
    }

    assertThat(
            BazelOutputSymlink.isCandidate(
                FileMetadata.Kind.SYMBOLIC_LINK, "bazel-vendor", true, "workspace"))
        .isFalse();
    assertThat(
            BazelOutputSymlink.isCandidate(
                FileMetadata.Kind.SYMBOLIC_LINK, "bazel-out", false, "workspace"))
        .isFalse();
    assertThat(
            BazelOutputSymlink.isCandidate(
                FileMetadata.Kind.DIRECTORY, "bazel-out", true, "workspace"))
        .isFalse();
  }

  @Test
  @DisplayName("Bazel convenience symlinks resolve lazily and remain navigable")
  void followsBazelDirectorySymlinksOffTheEdt() throws Exception {
    ExecutionPath root = path("/workspace");
    ExecutionPath bazelOut = path("/workspace/bazel-out");
    ExecutionPath canonicalOut = path("/cache/execroot/project/bazel-out");
    ExecutionPath output = path("/cache/execroot/project/bazel-out/result.txt");
    ExecutionPath workspaceLink = path("/workspace/bazel-workspace");
    ExecutionPath canonicalWorkspace = path("/cache/execroot/project");
    ExecutionPath workspaceFile = path("/cache/execroot/project/BUILD.bazel");
    ExecutionPath lookalike = path("/workspace/bazel-vendor");
    ExecutionPath ordinaryLink = path("/workspace/vendor");
    FakeFileSystem files =
        new FakeFileSystem(
            "ssh:builder",
            root,
            List.of(
                metadata(bazelOut, FileMetadata.Kind.SYMBOLIC_LINK, OptionalLong.empty()),
                metadata(workspaceLink, FileMetadata.Kind.SYMBOLIC_LINK, OptionalLong.empty()),
                metadata(lookalike, FileMetadata.Kind.SYMBOLIC_LINK, OptionalLong.empty()),
                metadata(ordinaryLink, FileMetadata.Kind.SYMBOLIC_LINK, OptionalLong.empty())));
    files.alias(bazelOut, canonicalOut);
    files.alias(workspaceLink, canonicalWorkspace);
    files.put(
        canonicalOut,
        List.of(metadata(output, FileMetadata.Kind.REGULAR_FILE, OptionalLong.of(12))));
    files.put(
        canonicalWorkspace,
        List.of(metadata(workspaceFile, FileMetadata.Kind.REGULAR_FILE, OptionalLong.of(8))));
    RepositoryBrowserView view = onEdt(RepositoryBrowserView::new);

    onEdt(
        () -> {
          view.openRepository("builder.example", files, root);
          return null;
        });
    await(() -> onEdt(() -> view.visibleEntriesForTest() == 4));

    assertThat(onEdt(view::rootChildrenForTest))
        .containsExactly(
            "bazel-out/ · Bazel symlink",
            "bazel-workspace/ · Bazel symlink",
            "bazel-vendor · symbolic link",
            "vendor · symbolic link");
    onEdt(
        () -> {
          view.expandRootChildForTest(0);
          view.expandRootChildForTest(1);
          view.expandRootChildForTest(2);
          view.expandRootChildForTest(3);
          return null;
        });
    await(() -> files.calls(canonicalOut) == 1);
    await(() -> files.calls(canonicalWorkspace) == 1);
    await(() -> onEdt(() -> view.visibleEntriesForTest() == 6));

    assertThat(files.calls(bazelOut)).isZero();
    assertThat(files.calls(workspaceLink)).isZero();
    assertThat(files.calls(lookalike)).isZero();
    assertThat(files.calls(ordinaryLink)).isZero();
    assertThat(onEdt(() -> view.rootChildChildrenForTest(0)))
        .containsExactly("result.txt · 12 bytes");
    assertThat(onEdt(() -> view.rootChildChildrenForTest(1)))
        .containsExactly("BUILD.bazel · 8 bytes");
    assertThat(files.edtCall).isFalse();
    onEdt(
        () -> {
          view.close();
          return null;
        });
  }

  @Test
  @DisplayName("a broken Bazel link reports its failure instead of looking empty")
  void brokenBazelDirectoryLinkIsExplicit() throws Exception {
    ExecutionPath root = path("/broken-workspace");
    ExecutionPath bazelOut = path("/broken-workspace/bazel-out");
    FakeFileSystem files =
        new FakeFileSystem(
            "ssh:builder",
            root,
            List.of(metadata(bazelOut, FileMetadata.Kind.SYMBOLIC_LINK, OptionalLong.empty())));
    files.failCanonicalization(bazelOut, new IOException("link target does not exist"));
    RepositoryBrowserView view = onEdt(RepositoryBrowserView::new);

    onEdt(
        () -> {
          view.openRepository("builder.example", files, root);
          return null;
        });
    await(() -> onEdt(() -> view.visibleEntriesForTest() == 1));
    onEdt(
        () -> {
          view.expandRootChildForTest(0);
          return null;
        });
    await(() -> onEdt(() -> view.statusForTest().contains("link target does not exist")));

    assertThat(onEdt(() -> view.rootChildChildrenForTest(0)))
        .containsExactly("Could not load: link target does not exist");
    assertThat(onEdt(view::statusForTest)).contains("Could not list", "bazel-out");
    assertThat(files.edtCall).isFalse();
    onEdt(
        () -> {
          view.close();
          return null;
        });
  }

  @Test
  @DisplayName("tree rows use cached type icons while status rows stay plain")
  void rendersFileTypeIconsWithoutChangingPlainTextSafety() throws Exception {
    ExecutionPath root = path("/icons");
    FakeFileSystem files =
        new FakeFileSystem(
            "ssh:builder",
            root,
            List.of(
                metadata(path("/icons/src"), FileMetadata.Kind.DIRECTORY, OptionalLong.empty()),
                metadata(
                    path("/icons/One.java"), FileMetadata.Kind.REGULAR_FILE, OptionalLong.of(1)),
                metadata(
                    path("/icons/Two.JAVA"), FileMetadata.Kind.REGULAR_FILE, OptionalLong.of(2)),
                metadata(
                    path("/icons/tool.py"), FileMetadata.Kind.REGULAR_FILE, OptionalLong.of(3)),
                metadata(
                    path("/icons/notes.unknown"),
                    FileMetadata.Kind.REGULAR_FILE,
                    OptionalLong.of(4)),
                metadata(
                    path("/icons/bazel-testlogs"),
                    FileMetadata.Kind.SYMBOLIC_LINK,
                    OptionalLong.empty())));
    RepositoryBrowserView view = onEdt(RepositoryBrowserView::new);

    onEdt(
        () -> {
          view.openRepository("builder.example", files, root);
          return null;
        });
    await(() -> onEdt(() -> view.visibleEntriesForTest() == 6 && view.iconsReadyForTest()));

    var rowIcons = onEdt(view::rootChildIconsForTest);
    assertThat(rowIcons).hasSize(6).doesNotContainNull();
    assertThat(rowIcons.get(0)).isNotSameAs(rowIcons.get(1));
    assertThat(rowIcons.get(1)).isSameAs(rowIcons.get(2));
    assertThat(rowIcons.get(1)).isNotSameAs(rowIcons.get(3));
    assertThat(rowIcons.get(3)).isNotSameAs(rowIcons.get(4));
    assertThat(rowIcons.get(5)).isNotSameAs(rowIcons.get(0)).isNotSameAs(rowIcons.get(4));
    assertThat(onEdt(view::messageIconForTest)).isNull();
    assertThat(onEdt(view::rendererHtmlDisabledForTest)).isEqualTo(Boolean.TRUE);

    onEdt(
        () -> {
          view.close();
          return null;
        });
  }

  @Test
  @DisplayName("paging stops at the exact visible cap and reports a partial tree")
  void visibleLimitIsExactAndExplicit() throws Exception {
    ExecutionPath root = path("/many");
    List<FileMetadata> entries = new ArrayList<>();
    for (int index = 0; index < 8; index++) {
      entries.add(
          metadata(
              path("/many/file-" + index), FileMetadata.Kind.REGULAR_FILE, OptionalLong.of(index)));
    }
    FakeFileSystem files = new FakeFileSystem("ssh:many", root, entries);
    files.maximumPageSize = 2;
    RepositoryBrowserView view = onEdt(() -> new RepositoryBrowserView(3));

    onEdt(
        () -> {
          view.openRepository("many.example", files, root);
          return null;
        });
    await(() -> onEdt(() -> view.visibleEntriesForTest() == 3));

    assertThat(files.calls(root)).isEqualTo(2);
    assertThat(onEdt(view::rootChildrenForTest)).hasSize(3);
    assertThat(onEdt(view::statusForTest))
        .contains("3 entries loaded", "View limit: 3", "view is partial");
    onEdt(
        () -> {
          view.close();
          return null;
        });
  }

  @Test
  @DisplayName("a late directory result cannot replace a newly selected repository")
  void staleGenerationIsIgnored() throws Exception {
    ExecutionPath oldRoot = new ExecutionPath("ssh:old", "/old");
    BlockingFileSystem oldFiles =
        new BlockingFileSystem(
            "ssh:old",
            oldRoot,
            List.of(
                metadata(
                    new ExecutionPath("ssh:old", "/old/stale"),
                    FileMetadata.Kind.REGULAR_FILE,
                    OptionalLong.of(1))));
    ExecutionPath newRoot = new ExecutionPath("ssh:new", "/new");
    FakeFileSystem newFiles =
        new FakeFileSystem(
            "ssh:new",
            newRoot,
            List.of(
                metadata(
                    new ExecutionPath("ssh:new", "/new/current"),
                    FileMetadata.Kind.REGULAR_FILE,
                    OptionalLong.of(2))));
    RepositoryBrowserView view = onEdt(RepositoryBrowserView::new);

    onEdt(
        () -> {
          view.openRepository("old.example", oldFiles, oldRoot);
          return null;
        });
    assertThat(oldFiles.started.await(5, TimeUnit.SECONDS)).isTrue();
    onEdt(
        () -> {
          view.openRepository("new.example", newFiles, newRoot);
          return null;
        });
    oldFiles.release.countDown();

    await(() -> onEdt(() -> view.rootChildrenForTest().equals(List.of("current · 2 bytes"))));
    assertThat(onEdt(view::locationForTest)).startsWith("new.example");
    assertThat(oldFiles.edtCalled()).isFalse();
    assertThat(newFiles.edtCalled()).isFalse();
    onEdt(
        () -> {
          view.close();
          return null;
        });
  }

  @Test
  @DisplayName("listing failures remain visible instead of looking like empty directories")
  void errorsAreExplicit() throws Exception {
    ExecutionPath root = path("/denied");
    FakeFileSystem files = new FakeFileSystem("ssh:builder", root, List.of());
    files.failure = new IOException("permission denied");
    RepositoryBrowserView view = onEdt(RepositoryBrowserView::new);

    onEdt(
        () -> {
          view.openRepository("builder.example", files, root);
          return null;
        });
    await(() -> onEdt(() -> view.statusForTest().contains("permission denied")));

    assertThat(onEdt(view::statusForTest)).contains("Could not list /denied", "permission denied");
    assertThat(onEdt(view::rootChildrenForTest))
        .containsExactly("Could not load: permission denied");
    assertThat(files.edtCall).isFalse();
    onEdt(
        () -> {
          view.close();
          return null;
        });
  }

  @Test
  @DisplayName("asynchronous close cancels and joins an in-flight directory listing")
  void closeAsyncWaitsForInFlightListingWithoutBlockingTheEdt() throws Exception {
    ExecutionPath root = path("/closing");
    UninterruptibleFileSystem files = new UninterruptibleFileSystem(root);
    RepositoryBrowserView view = onEdt(RepositoryBrowserView::new);
    onEdt(
        () -> {
          view.openRepository("builder.example", files, root);
          return null;
        });
    assertThat(files.started.await(5, TimeUnit.SECONDS)).isTrue();

    long started = System.nanoTime();
    CompletableFuture<Void> closed = onEdt(() -> view.closeAsync().toCompletableFuture());
    long edtMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

    assertThat(edtMillis).isLessThan(2_000L);
    assertThat(files.interrupted.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(closed).isNotDone();
    files.release.countDown();
    closed.get(5, TimeUnit.SECONDS);
  }

  @Test
  @DisplayName("clearing a repository joins old reads while leaving the browser reusable")
  void clearRepositoryAsyncRetiresOnlyTheOldWorker() throws Exception {
    ExecutionPath oldRoot = path("/old-context");
    UninterruptibleFileSystem oldFiles = new UninterruptibleFileSystem(oldRoot);
    RepositoryBrowserView view = onEdt(RepositoryBrowserView::new);
    onEdt(
        () -> {
          view.openRepository("old.example", oldFiles, oldRoot);
          return null;
        });
    assertThat(oldFiles.started.await(5, TimeUnit.SECONDS)).isTrue();

    CompletableFuture<Void> cleared =
        onEdt(() -> view.clearRepositoryAsync().toCompletableFuture());

    assertThat(oldFiles.interrupted.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(cleared).isNotDone();
    assertThat(onEdt(view::locationForTest)).isEqualTo("No repository is connected.");
    oldFiles.release.countDown();
    cleared.get(5, TimeUnit.SECONDS);

    ExecutionPath newRoot = path("/new-context");
    FakeFileSystem newFiles =
        new FakeFileSystem(
            "ssh:builder",
            newRoot,
            List.of(
                metadata(
                    path("/new-context/BUILD.bazel"),
                    FileMetadata.Kind.REGULAR_FILE,
                    OptionalLong.of(4))));
    onEdt(
        () -> {
          view.openRepository("new.example", newFiles, newRoot);
          return null;
        });
    await(() -> onEdt(() -> view.rootChildrenForTest().equals(List.of("BUILD.bazel · 4 bytes"))));
    onEdt(() -> view.closeAsync().toCompletableFuture()).get(5, TimeUnit.SECONDS);
  }

  private static ExecutionPath path(String value) {
    return new ExecutionPath("ssh:builder", value);
  }

  private static FileMetadata metadata(
      ExecutionPath path, FileMetadata.Kind kind, OptionalLong bytes) {
    return FileMetadata.present(path, kind, bytes, 123L);
  }

  private static class FakeFileSystem implements ExecutionFileSystem {
    private final String executionId;
    private final Map<ExecutionPath, List<FileMetadata>> listings = new ConcurrentHashMap<>();
    private final Map<ExecutionPath, AtomicInteger> calls = new ConcurrentHashMap<>();
    private final Map<ExecutionPath, ExecutionPath> aliases = new ConcurrentHashMap<>();
    private final Map<ExecutionPath, IOException> canonicalFailures = new ConcurrentHashMap<>();
    private final AtomicBoolean edtCall = new AtomicBoolean();
    private volatile IOException failure;
    private volatile int maximumPageSize = Integer.MAX_VALUE;

    FakeFileSystem(String executionId, ExecutionPath root, List<FileMetadata> rootEntries) {
      this.executionId = executionId;
      put(root, rootEntries);
    }

    void put(ExecutionPath directory, List<FileMetadata> entries) {
      listings.put(directory, List.copyOf(entries));
    }

    void alias(ExecutionPath link, ExecutionPath target) {
      aliases.put(link, target);
    }

    void failCanonicalization(ExecutionPath link, IOException failure) {
      canonicalFailures.put(link, failure);
    }

    int calls(ExecutionPath directory) {
      AtomicInteger count = calls.get(directory);
      return count == null ? 0 : count.get();
    }

    boolean edtCalled() {
      return edtCall.get();
    }

    @Override
    public String executionId() {
      markThread();
      return executionId;
    }

    @Override
    public DirectoryPage list(
        ExecutionPath directory, Optional<String> continuationToken, int maxEntries)
        throws IOException {
      markThread();
      calls.computeIfAbsent(directory, ignored -> new AtomicInteger()).incrementAndGet();
      if (failure != null) {
        throw failure;
      }
      List<FileMetadata> entries = listings.getOrDefault(directory, List.of());
      int offset = continuationToken.map(Integer::parseInt).orElse(0);
      int count = Math.min(Math.min(maxEntries, maximumPageSize), entries.size() - offset);
      int end = offset + Math.max(0, count);
      Optional<String> next =
          end < entries.size() ? Optional.of(Integer.toString(end)) : Optional.empty();
      return new DirectoryPage(
          directory, entries.subList(offset, end), next, OptionalLong.of(entries.size()));
    }

    private void markThread() {
      edtCall.compareAndSet(false, SwingUtilities.isEventDispatchThread());
    }

    @Override
    public ExecutionPath path(String value) {
      throw new UnsupportedOperationException();
    }

    @Override
    public ExecutionPath resolve(ExecutionPath base, String child) {
      throw new UnsupportedOperationException();
    }

    @Override
    public ExecutionPath canonicalize(ExecutionPath path) throws IOException {
      markThread();
      IOException failure = canonicalFailures.get(path);
      if (failure != null) {
        throw failure;
      }
      return aliases.getOrDefault(path, path);
    }

    @Override
    public boolean isWithin(ExecutionPath root, ExecutionPath candidate) {
      throw new UnsupportedOperationException();
    }

    @Override
    public FileMetadata stat(ExecutionPath path) {
      throw new UnsupportedOperationException();
    }

    @Override
    public FileContents read(ExecutionPath path, long maxBytes) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void download(ExecutionPath source, Path localDestination, long maxBytes) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void upload(
        Path localSource, ExecutionPath destination, long maxBytes, UploadMode mode) {
      throw new UnsupportedOperationException();
    }

    @Override
    public FileContents replaceAtomically(
        ExecutionPath path, FileVersion expected, byte[] replacement, long maxBytes) {
      throw new UnsupportedOperationException();
    }
  }

  private static final class BlockingFileSystem extends FakeFileSystem {
    private final CountDownLatch started = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);

    BlockingFileSystem(String executionId, ExecutionPath root, List<FileMetadata> rootEntries) {
      super(executionId, root, rootEntries);
    }

    @Override
    public DirectoryPage list(
        ExecutionPath directory, Optional<String> continuationToken, int maxEntries)
        throws IOException {
      started.countDown();
      try {
        if (!release.await(5, TimeUnit.SECONDS)) {
          throw new IOException("test did not release blocked listing");
        }
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IOException("interrupted", interrupted);
      }
      return super.list(directory, continuationToken, maxEntries);
    }
  }

  /** Simulates a transport call that observes cancellation but returns on its own boundary. */
  private static final class UninterruptibleFileSystem extends FakeFileSystem {
    private final CountDownLatch started = new CountDownLatch(1);
    private final CountDownLatch interrupted = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);

    UninterruptibleFileSystem(ExecutionPath root) {
      super("ssh:builder", root, List.of());
    }

    @Override
    public DirectoryPage list(
        ExecutionPath directory, Optional<String> continuationToken, int maxEntries)
        throws IOException {
      started.countDown();
      boolean released = false;
      while (!released) {
        try {
          released = release.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
          interrupted.countDown();
        }
      }
      return super.list(directory, continuationToken, maxEntries);
    }
  }

  private static void await(Checked condition) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (!condition.get()) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("condition never became true");
      }
      TimeUnit.MILLISECONDS.sleep(10);
    }
  }

  @FunctionalInterface
  private interface Checked {
    boolean get() throws Exception;
  }

  private static <T> T onEdt(Callable<T> work) throws Exception {
    if (SwingUtilities.isEventDispatchThread()) {
      return work.call();
    }
    AtomicReference<T> value = new AtomicReference<>();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    SwingUtilities.invokeAndWait(
        () -> {
          try {
            value.set(work.call());
          } catch (Throwable caught) {
            failure.set(caught);
          }
        });
    if (failure.get() != null) {
      throw new AssertionError(failure.get());
    }
    return value.get();
  }
}
