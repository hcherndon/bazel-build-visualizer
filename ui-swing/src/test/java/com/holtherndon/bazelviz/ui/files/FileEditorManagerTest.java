package com.holtherndon.bazelviz.ui.files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Window semantics for the modeless file viewer. */
final class FileEditorManagerTest {

  @Test
  @DisplayName("file viewers are normal windows rather than owner-bound floating dialogs")
  void viewersParticipateInNormalWindowStacking() {
    assertThat(FileEditorManager.EditorWindow.class).isAssignableTo(JFrame.class);
    assertThat(JDialog.class.isAssignableFrom(FileEditorManager.EditorWindow.class)).isFalse();
  }

  @Test
  void noDirtyEditorsAllowCloseWithoutPrompting() throws Exception {
    AtomicInteger confirmations = new AtomicInteger();
    FileEditorManager manager =
        new FileEditorManager(
            null,
            (owner, count) -> {
              confirmations.incrementAndGet();
              return false;
            });

    boolean allowed = onEdt(manager::confirmCloseAllowed);

    assertThat(allowed).isTrue();
    assertThat(confirmations).hasValue(0);
    onEdt(
        () -> {
          manager.close();
          return null;
        });
  }

  @Test
  void dirtyEditorsProduceOneAggregateDecisionWithoutChangingEditors(@TempDir Path directory)
      throws Exception {
    FileEditorPanel first = editablePanel(directory.resolve("first.bazel"), "first = 1\n");
    FileEditorPanel second = editablePanel(directory.resolve("second.bazel"), "second = 1\n");
    FileEditorPanel clean = editablePanel(directory.resolve("clean.bazel"), "clean = 1\n");
    onEdt(
        () -> {
          first.editorForTest().setText("first = 2\n");
          second.editorForTest().setText("second = 2\n");
          return null;
        });
    AtomicInteger confirmations = new AtomicInteger();
    AtomicInteger describedCount = new AtomicInteger();
    FileEditorManager manager =
        new FileEditorManager(
            null,
            (owner, count) -> {
              confirmations.incrementAndGet();
              describedCount.set(count);
              return false;
            });

    boolean allowed = onEdt(() -> manager.confirmCloseAllowed(List.of(first, clean, second)));

    assertThat(allowed).isFalse();
    assertThat(confirmations).hasValue(1);
    assertThat(describedCount).hasValue(2);
    assertThat(onEdt(first::isDirty)).isTrue();
    assertThat(onEdt(second::isDirty)).isTrue();
    assertThat(onEdt(() -> first.editorForTest().getText())).isEqualTo("first = 2\n");
    assertThat(onEdt(() -> second.editorForTest().getText())).isEqualTo("second = 2\n");
    assertThat(Files.readString(directory.resolve("first.bazel"))).isEqualTo("first = 1\n");
    assertThat(Files.readString(directory.resolve("second.bazel"))).isEqualTo("second = 1\n");
    onEdt(
        () -> {
          first.close();
          second.close();
          clean.close();
          manager.close();
          return null;
        });
  }

  @Test
  void confirmationCanAllowCloseAndItsMessageStatesTheExactCount() throws Exception {
    AtomicInteger describedCount = new AtomicInteger();
    FileEditorManager manager =
        new FileEditorManager(
            null,
            (owner, count) -> {
              describedCount.set(count);
              return true;
            });
    Path file = Files.createTempFile("bbv-close-check", ".bazel");
    Files.writeString(file, "value = 1\n");
    FileEditorPanel panel = editablePanel(file, "value = 1\n");
    onEdt(
        () -> {
          panel.editorForTest().setText("value = 2\n");
          return null;
        });

    assertThat(onEdt(() -> manager.confirmCloseAllowed(List.of(panel)))).isTrue();
    assertThat(describedCount).hasValue(1);
    assertThat(FileEditorManager.dirtyEditorCloseMessage(1)).contains("1 open file editor");
    assertThat(FileEditorManager.dirtyEditorCloseMessage(3)).contains("3 open file editors");
    assertThat(onEdt(panel::isDirty)).isTrue();
    onEdt(
        () -> {
          panel.close();
          manager.close();
          return null;
        });
    Files.deleteIfExists(file);
  }

  @Test
  void executionContextChangeUsesItsOwnAggregateConfirmation(@TempDir Path directory)
      throws Exception {
    FileEditorPanel dirty = editablePanel(directory.resolve("BUILD.bazel"), "value = 1\n");
    onEdt(
        () -> {
          dirty.editorForTest().setText("value = 2\n");
          return null;
        });
    AtomicInteger closeConfirmations = new AtomicInteger();
    AtomicInteger contextConfirmations = new AtomicInteger();
    FileEditorManager manager =
        new FileEditorManager(
            null,
            (owner, count) -> {
              closeConfirmations.incrementAndGet();
              return true;
            },
            (owner, count) -> {
              contextConfirmations.incrementAndGet();
              return false;
            });

    boolean allowed = onEdt(() -> manager.confirmContextChangeAllowed(List.of(dirty)));

    assertThat(allowed).isFalse();
    assertThat(closeConfirmations).hasValue(0);
    assertThat(contextConfirmations).hasValue(1);
    assertThat(FileEditorManager.dirtyEditorContextChangeMessage(1))
        .contains("Apply the new Workspace connection")
        .contains("discard it");
    assertThat(onEdt(dirty::isDirty)).isTrue();
    onEdt(
        () -> {
          dirty.close();
          manager.close();
          return null;
        });
  }

  @Test
  void contextChangeInvalidatesFileOpensStillResolving() throws Exception {
    FileEditorManager manager = new FileEditorManager(null, (owner, count) -> true);
    long priorGeneration = manager.contextGenerationForTest();

    onEdt(
        () -> {
          manager.closeEditorsForContextChange();
          return null;
        });

    assertThat(manager.contextGenerationForTest()).isGreaterThan(priorGeneration);
    onEdt(
        () -> {
          manager.close();
          return null;
        });
  }

  @Test
  void closePreflightRejectsCallsOutsideTheEventThread() {
    FileEditorManager manager = new FileEditorManager(null, (owner, count) -> true);

    assertThatThrownBy(manager::confirmCloseAllowed)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("EDT");
    assertThatThrownBy(manager::confirmContextChangeAllowed)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("EDT");
    manager.close();
  }

  @Test
  @DisplayName("close cancels reads but lets an accepted save finish before completing")
  void closeAsyncPreservesInFlightSave() throws Exception {
    ExecutorService reads = Executors.newSingleThreadExecutor();
    ExecutorService saves = Executors.newSingleThreadExecutor();
    CountDownLatch readStarted = new CountDownLatch(1);
    CountDownLatch saveStarted = new CountDownLatch(1);
    CountDownLatch readInterrupted = new CountDownLatch(1);
    CountDownLatch releaseRead = new CountDownLatch(1);
    CountDownLatch releaseSave = new CountDownLatch(1);
    AtomicBoolean saveInterrupted = new AtomicBoolean();
    reads.execute(() -> waitForRelease(readStarted, readInterrupted, releaseRead));
    saves.execute(
        () -> {
          saveStarted.countDown();
          try {
            releaseSave.await();
          } catch (InterruptedException interrupted) {
            saveInterrupted.set(true);
            Thread.currentThread().interrupt();
          }
        });
    assertThat(readStarted.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(saveStarted.await(5, TimeUnit.SECONDS)).isTrue();
    FileEditorManager manager =
        new FileEditorManager(null, (owner, count) -> true, (owner, count) -> true, reads, saves);

    CompletableFuture<Void> closed = onEdt(() -> manager.closeAsync().toCompletableFuture());

    assertThat(readInterrupted.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(saveInterrupted).isFalse();
    assertThat(closed).isNotDone();
    releaseRead.countDown();
    assertThat(closed).isNotDone();
    releaseSave.countDown();
    closed.get(5, TimeUnit.SECONDS);
    assertThat(saveInterrupted).isFalse();
    assertThat(reads.isTerminated()).isTrue();
    assertThat(saves.isTerminated()).isTrue();
  }

  @Test
  @DisplayName("context change drains old workers without permanently closing the manager")
  void contextChangeAsyncRetiresOldWorkers() throws Exception {
    ExecutorService reads = Executors.newSingleThreadExecutor();
    ExecutorService saves = Executors.newSingleThreadExecutor();
    CountDownLatch readStarted = new CountDownLatch(1);
    CountDownLatch saveStarted = new CountDownLatch(1);
    CountDownLatch readInterrupted = new CountDownLatch(1);
    CountDownLatch releaseRead = new CountDownLatch(1);
    CountDownLatch releaseSave = new CountDownLatch(1);
    AtomicBoolean saveInterrupted = new AtomicBoolean();
    reads.execute(() -> waitForRelease(readStarted, readInterrupted, releaseRead));
    saves.execute(
        () -> {
          saveStarted.countDown();
          try {
            releaseSave.await();
          } catch (InterruptedException interrupted) {
            saveInterrupted.set(true);
            Thread.currentThread().interrupt();
          }
        });
    assertThat(readStarted.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(saveStarted.await(5, TimeUnit.SECONDS)).isTrue();
    FileEditorManager manager =
        new FileEditorManager(null, (owner, count) -> true, (owner, count) -> true, reads, saves);

    CompletableFuture<Void> contextClosed =
        onEdt(() -> manager.closeEditorsForContextChangeAsync().toCompletableFuture());

    assertThat(readInterrupted.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(contextClosed).isNotDone();
    assertThat(saveInterrupted).isFalse();
    releaseRead.countDown();
    releaseSave.countDown();
    contextClosed.get(5, TimeUnit.SECONDS);
    assertThat(saveInterrupted).isFalse();

    // The context transition rotates, rather than permanently closing, the manager workers.
    onEdt(() -> manager.closeAsync().toCompletableFuture()).get(5, TimeUnit.SECONDS);
  }

  private static void waitForRelease(
      CountDownLatch started, CountDownLatch interrupted, CountDownLatch release) {
    started.countDown();
    boolean released = false;
    while (!released) {
      try {
        release.await();
        released = true;
      } catch (InterruptedException ignored) {
        interrupted.countDown();
      }
    }
  }

  private static FileEditorPanel editablePanel(Path file, String original) throws Exception {
    Files.writeString(file, original);
    FileEditorPanel panel =
        onEdt(
            () ->
                new FileEditorPanel(
                    file, true, command -> new Thread(command, "test-close-check-worker").start()));
    await(() -> onEdt(() -> panel.editorForTest().isEditable()));
    return panel;
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

  @FunctionalInterface
  private interface Checked {
    boolean get() throws Exception;
  }
}
