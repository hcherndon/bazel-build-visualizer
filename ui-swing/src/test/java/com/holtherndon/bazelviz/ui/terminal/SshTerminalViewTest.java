package com.holtherndon.bazelviz.ui.terminal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.holtherndon.bazelviz.runner.runtime.CommandExecutor;
import com.holtherndon.bazelviz.runner.runtime.CommandRequest;
import com.holtherndon.bazelviz.runner.runtime.CommandResult;
import com.holtherndon.bazelviz.runner.runtime.InteractiveChannel;
import com.holtherndon.bazelviz.runner.runtime.RunningCommand;
import com.holtherndon.bazelviz.runner.runtime.TerminalSize;
import com.holtherndon.bazelviz.ui.theme.AppTheme;
import com.holtherndon.bazelviz.ui.theme.PageToolbar;
import com.holtherndon.bazelviz.ui.theme.Themes;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Persistent JediTerm lifecycle over a fake execution-scoped terminal. */
final class SshTerminalViewTest {

  private static final ExecutorService TERMINAL_IO =
      Executors.newThreadPerTaskExecutor(
          Thread.ofVirtual().name("bbv-terminal-test-", 0).factory());
  private static final ScheduledExecutorService TERMINAL_SCHEDULER =
      Executors.newSingleThreadScheduledExecutor(
          Thread.ofVirtual().name("bbv-terminal-test-timer-", 0).factory());

  @AfterAll
  static void closeTerminalIo() {
    TERMINAL_SCHEDULER.shutdownNow();
    TERMINAL_IO.shutdownNow();
  }

  @Test
  @DisplayName("the input guard preserves ANSI and terminated OSC and DCS strings")
  void boundedReaderPreservesNormalTerminalInput() throws Exception {
    String input =
        "plain\u001b[31mred\u001b[0m"
            + "\u009b32mgreen"
            + "\u001b]0;window title\u0007"
            + "\u001bPdevice data\u001b\\"
            + "\u009d8;;https://example.test\u009clink";
    List<IOException> failures = new ArrayList<>();
    ReaderProbe guarded =
        new ReaderProbe(new BoundedTerminalReader(new StringReader(input), 32, 64, failures::add));

    assertThat(guarded.readInChunks(3)).isEqualTo(input);
    assertThat(failures).isEmpty();
  }

  @Test
  @DisplayName("the input guard rejects one overlong control string without retaining it")
  void boundedReaderRejectsOverlongControlString() {
    List<IOException> failures = new ArrayList<>();
    BoundedTerminalReader guarded =
        new BoundedTerminalReader(new StringReader("\u001b]1234567"), 32, 8, failures::add);

    assertThatThrownBy(() -> guarded.read(new char[64], 0, 64))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("8-character safety limit");
    assertThat(failures).hasSize(1);
  }

  @Test
  @DisplayName("the input guard rejects overlong 7-bit and C1 CSI sequences")
  void boundedReaderRejectsOverlongCsi() {
    for (String introducer : List.of("\u001b[", "\u009b")) {
      List<IOException> failures = new ArrayList<>();
      BoundedTerminalReader guarded =
          new BoundedTerminalReader(
              new StringReader(introducer + "1;".repeat(8) + "m"), 12, 64, failures::add);

      assertThatThrownBy(() -> guarded.read(new char[64], 0, 64))
          .as("introducer %s", introducer.equals("\u009b") ? "C1 CSI" : "ESC [")
          .isInstanceOf(IOException.class)
          .hasMessageContaining("CSI sequence", "12-character safety limit");
      assertThat(failures).hasSize(1);
    }
  }

  @Test
  @DisplayName("ordinary output is not subject to the control-string limit")
  void boundedReaderDoesNotLimitPlainText() throws Exception {
    String input = "ordinary output ".repeat(2_000);
    ReaderProbe guarded =
        new ReaderProbe(
            new BoundedTerminalReader(
                new StringReader(input),
                8,
                8,
                failure -> {
                  throw new AssertionError(failure);
                }));

    assertThat(guarded.readInChunks(257)).isEqualTo(input);
  }

  @Test
  @DisplayName("connect, VT output, keyboard input, and disconnect keep I/O off the EDT")
  void terminalIoRunsOffEdtAndSurvivesBeingHidden() throws Exception {
    FakeCommandExecutor executor = new FakeCommandExecutor();
    FakeChannel channel = executor.enqueueChannel();
    SshTerminalView view = onEdt(() -> new SshTerminalView(TERMINAL_IO, TERMINAL_SCHEDULER));
    PageToolbar toolbar = onEdt(() -> new PageToolbar("Terminal"));

    onEdt(
        () -> {
          view.installPageToolbar(toolbar);
          view.bind("builder.example", "/work/repo", executor);
          view.connect();
          return null;
        });
    await(() -> onEdt(() -> view.connectionState() == SshTerminalView.ConnectionState.CONNECTED));

    assertThat(executor.openedOnEdt).isFalse();
    assertThat(onEdt(toolbar::actionCount)).isEqualTo(3);
    assertThat(onEdt(toolbar::metadata)).isEqualTo("/work/repo · Connected");
    assertThat(onEdt(view::locationForTest)).isEqualTo("builder.example · /work/repo");
    assertThat(onEdt(view::capabilityForTest))
        .contains("xterm-compatible", "full-screen", "resize");
    assertThat(onEdt(view::usesBorrowedExecutorsForTest)).isTrue();

    onEdt(
        () -> {
          view.setVisible(false);
          return null;
        });
    assertThat(channel.isOpen()).isTrue();

    channel.emit("\u001b[32mready\u001b[0m\r\nrepo$ ");
    await(
        () ->
            onEdt(
                () ->
                    view.screenTextForTest().contains("ready")
                        && view.screenTextForTest().contains("repo$")));
    assertThat(channel.readOnEdt).isFalse();

    onEdt(
        () -> {
          view.sendTextForTest("pwd\r");
          return null;
        });
    await(() -> channel.writtenText().contains("pwd\r"));
    assertThat(channel.writtenOnEdt).isFalse();

    onEdt(
        () -> {
          view.requestResizeForTest(132, 43);
          return null;
        });
    await(() -> new TerminalSize(132, 43).equals(channel.lastSize.get()));
    assertThat(channel.resizedOnEdt).isFalse();

    onEdt(
        () -> {
          view.disconnect();
          return null;
        });
    await(
        () -> onEdt(() -> view.connectionState() == SshTerminalView.ConnectionState.DISCONNECTED));
    assertThat(channel.isOpen()).isFalse();
    onEdt(
        () -> {
          view.close();
          return null;
        });
  }

  @Test
  @DisplayName("activation opens one transport-neutral terminal and repeated activation is inert")
  void activationIsIdempotent() throws Exception {
    FakeCommandExecutor executor = new FakeCommandExecutor();
    FakeChannel channel = executor.enqueueChannel();
    TerminalView view = onEdt(() -> new TerminalView(TERMINAL_IO, TERMINAL_SCHEDULER));

    try {
      assertThat(onEdt(view::locationForTest)).isEqualTo("No workspace is selected.");
      assertThat(onEdt(view::emptyMessageForTest))
          .isEqualTo("Select a workspace to use the terminal.");

      onEdt(
          () -> {
            view.bind("This computer", "/work/repo", executor);
            return null;
          });
      assertThat(executor.opens).hasValue(0);

      onEdt(
          () -> {
            view.activate();
            view.activate();
            return null;
          });
      await(() -> onEdt(() -> view.connectionState() == SshTerminalView.ConnectionState.CONNECTED));

      onEdt(
          () -> {
            view.bind("This computer", "/work/repo", executor);
            view.activate();
            return null;
          });

      assertThat(executor.opens).hasValue(1);
      assertThat(channel.isOpen()).isTrue();
      assertThat(onEdt(view::locationForTest)).isEqualTo("This computer · /work/repo");
    } finally {
      onEdt(
          () -> {
            view.close();
            return null;
          });
      await(() -> !channel.isOpen());
    }
  }

  @Test
  @DisplayName("an active terminal changes palette without reconnecting its SSH channel")
  void activeTerminalFollowsThemeWithoutReconnect() throws Exception {
    FakeCommandExecutor executor = new FakeCommandExecutor();
    FakeChannel channel = executor.enqueueChannel();
    SshTerminalView view =
        onEdt(
            () -> {
              Themes.install(AppTheme.LIGHT);
              return new SshTerminalView(TERMINAL_IO, TERMINAL_SCHEDULER);
            });

    try {
      onEdt(
          () -> {
            view.bind("builder.example", "/work/repo", executor);
            view.connect();
            return null;
          });
      await(() -> onEdt(() -> view.connectionState() == SshTerminalView.ConnectionState.CONNECTED));
      int light = onEdt(view::terminalDefaultBackgroundRgbForTest);

      int expectedDark =
          onEdt(
              () -> {
                Themes.install(AppTheme.DARK);
                view.refreshTheme();
                Color background = UIManager.getColor("TextArea.background");
                return (background.getRed() << 16)
                    | (background.getGreen() << 8)
                    | background.getBlue();
              });
      int dark = onEdt(view::terminalDefaultBackgroundRgbForTest);

      assertThat(dark).isEqualTo(expectedDark).isNotEqualTo(light);
      assertThat(executor.opens).hasValue(1);
      assertThat(channel.isOpen()).isTrue();
    } finally {
      onEdt(
          () -> {
            view.close();
            Themes.installDefault();
            return null;
          });
    }
  }

  @Test
  @DisplayName("reconnect closes the old terminal before opening a fresh one")
  void reconnectReplacesTheChannel() throws Exception {
    FakeCommandExecutor executor = new FakeCommandExecutor();
    FakeChannel first = executor.enqueueChannel();
    FakeChannel second = executor.enqueueChannel();
    SshTerminalView view = onEdt(() -> new SshTerminalView(TERMINAL_IO, TERMINAL_SCHEDULER));

    onEdt(
        () -> {
          view.bind("builder.example", "/workspace", executor);
          view.connect();
          return null;
        });
    await(
        () ->
            executor.opens.get() == 1
                && onEdt(
                    () -> view.connectionState() == SshTerminalView.ConnectionState.CONNECTED));
    onEdt(
        () -> {
          view.reconnect();
          return null;
        });
    await(
        () ->
            executor.opens.get() == 2
                && onEdt(
                    () -> view.connectionState() == SshTerminalView.ConnectionState.CONNECTED));

    assertThat(first.isOpen()).isFalse();
    assertThat(second.isOpen()).isTrue();
    onEdt(
        () -> {
          view.close();
          return null;
        });
    await(() -> !second.isOpen());
  }

  @Test
  @DisplayName("rebinding removes the old terminal and ignores its late lifecycle callback")
  void staleTerminalCannotCrossBindings() throws Exception {
    FakeCommandExecutor firstExecutor = new FakeCommandExecutor();
    FakeChannel first = firstExecutor.enqueueChannel();
    FakeCommandExecutor secondExecutor = new FakeCommandExecutor();
    secondExecutor.enqueueChannel();
    SshTerminalView view = onEdt(() -> new SshTerminalView(TERMINAL_IO, TERMINAL_SCHEDULER));

    onEdt(
        () -> {
          view.bind("old.example", "/old", firstExecutor);
          view.connect();
          return null;
        });
    await(() -> onEdt(() -> view.connectionState() == SshTerminalView.ConnectionState.CONNECTED));
    onEdt(
        () -> {
          view.bind("new.example", "/new", secondExecutor);
          return null;
        });

    await(() -> !first.isOpen());
    assertThat(onEdt(view::screenTextForTest)).isEmpty();
    assertThat(onEdt(view::locationForTest)).startsWith("new.example");
    assertThat(onEdt(view::connectionState))
        .isEqualTo(SshTerminalView.ConnectionState.DISCONNECTED);
    onEdt(
        () -> {
          view.close();
          return null;
        });
  }

  @Test
  @DisplayName("JediTerm supports alternate-screen control and bounded scrollback")
  void terminalEmulationAndScrollbackAreBounded() throws Exception {
    FakeCommandExecutor executor = new FakeCommandExecutor();
    FakeChannel channel = executor.enqueueChannel();
    SshTerminalView view = onEdt(() -> new SshTerminalView(2, TERMINAL_IO, TERMINAL_SCHEDULER));
    onEdt(
        () -> {
          view.bind("builder.example", "/workspace", executor);
          view.connect();
          return null;
        });
    await(() -> onEdt(() -> view.connectionState() == SshTerminalView.ConnectionState.CONNECTED));

    StringBuilder lines = new StringBuilder();
    for (int index = 0; index < 40; index++) {
      lines.append("line-").append(index).append("\r\n");
    }
    channel.emit(lines.toString());
    await(() -> onEdt(() -> view.screenTextForTest().contains("line-39")));
    assertThat(onEdt(view::historyLinesForTest)).isLessThanOrEqualTo(2);
    assertThat(onEdt(view::historyLimitForTest)).isEqualTo(2);

    channel.emit("\u001b[?1049h\u001b[2Jalternate-screen");
    await(
        () ->
            onEdt(
                () ->
                    view.alternateScreenForTest()
                        && view.screenTextForTest().contains("alternate-screen")));
    assertThat(onEdt(view::screenTextForTest)).contains("alternate-screen");
    channel.emit("\u001b[?1049l");
    await(() -> !onEdt(view::alternateScreenForTest));

    onEdt(
        () -> {
          view.close();
          return null;
        });
  }

  @Test
  @DisplayName("an overlong remote OSC is visible and closes the terminal")
  void overlongOscFailsClosed() throws Exception {
    FakeCommandExecutor executor = new FakeCommandExecutor();
    FakeChannel channel = executor.enqueueChannel();
    SshTerminalView view = onEdt(() -> new SshTerminalView(TERMINAL_IO, TERMINAL_SCHEDULER));
    onEdt(
        () -> {
          view.bind("builder.example", "/workspace", executor);
          view.connect();
          return null;
        });
    await(() -> onEdt(() -> view.connectionState() == SshTerminalView.ConnectionState.CONNECTED));

    channel.emitUntilClosed(
        "\u001b]0;" + "x".repeat(SshTerminalView.MAX_CONTROL_STRING_CHARACTERS + 1));

    await(() -> onEdt(() -> view.connectionState() == SshTerminalView.ConnectionState.FAILED));
    assertThat(onEdt(view::statusForTest)).contains("safety limit");
    await(() -> !channel.isOpen());
    onEdt(
        () -> {
          view.close();
          return null;
        });
  }

  @Test
  @DisplayName("connection failures are visible and leave reconnect available")
  void connectionFailureIsVisible() throws Exception {
    FakeCommandExecutor executor = new FakeCommandExecutor();
    executor.failure = new IOException("host key rejected");
    SshTerminalView view = onEdt(() -> new SshTerminalView(TERMINAL_IO, TERMINAL_SCHEDULER));

    onEdt(
        () -> {
          view.bind("builder.example", "/workspace", executor);
          view.connect();
          return null;
        });
    await(() -> onEdt(() -> view.connectionState() == SshTerminalView.ConnectionState.FAILED));

    assertThat(onEdt(view::statusForTest)).contains("host key rejected");
    assertThat(executor.openedOnEdt).isFalse();
    onEdt(
        () -> {
          view.close();
          return null;
        });
  }

  @Test
  @DisplayName("closing a view leaves its app-owned executor available")
  void closeDoesNotOwnTheInjectedExecutor() throws Exception {
    SshTerminalView view = onEdt(() -> new SshTerminalView(TERMINAL_IO, TERMINAL_SCHEDULER));

    CompletionStage<Void> closed = onEdt(view::closeAsync);

    assertThat(closed.toCompletableFuture()).isCompleted();
    assertThat(TERMINAL_IO.isShutdown()).isFalse();
  }

  @Test
  @DisplayName("close completion waits for an adopted terminal channel")
  void closeCompletionFollowsChannelTeardown() throws Exception {
    FakeCommandExecutor executor = new FakeCommandExecutor();
    FakeChannel channel = executor.enqueueChannel();
    SshTerminalView view = onEdt(() -> new SshTerminalView(TERMINAL_IO, TERMINAL_SCHEDULER));
    onEdt(
        () -> {
          view.bind("builder.example", "/workspace", executor);
          view.connect();
          return null;
        });
    await(() -> onEdt(() -> view.connectionState() == SshTerminalView.ConnectionState.CONNECTED));

    CompletionStage<Void> closed = onEdt(view::closeAsync);

    closed.toCompletableFuture().get(5, TimeUnit.SECONDS);
    assertThat(channel.isOpen()).isFalse();
  }

  @Test
  @DisplayName("closing during a blocked open cannot orphan the returned channel")
  void closeDuringOpenRetainsBackgroundChannelOwnership() throws Exception {
    ExecutorService owner =
        Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("bbv-terminal-race-test-", 0).factory());
    ScheduledExecutorService ownerScheduler =
        Executors.newSingleThreadScheduledExecutor(
            Thread.ofVirtual().name("bbv-terminal-race-test-timer-", 0).factory());
    FakeCommandExecutor executor = new FakeCommandExecutor();
    FakeChannel channel = executor.enqueueChannel();
    executor.blockOpen = new CountDownLatch(1);
    SshTerminalView view = onEdt(() -> new SshTerminalView(owner, ownerScheduler));

    try {
      onEdt(
          () -> {
            view.bind("builder.example", "/workspace", executor);
            view.connect();
            return null;
          });
      assertThat(executor.openStarted.await(5, TimeUnit.SECONDS)).isTrue();

      CompletionStage<Void> closed = onEdt(view::closeAsync);

      assertThat(closed.toCompletableFuture()).isNotDone();
      executor.blockOpen.countDown();

      closed.toCompletableFuture().get(5, TimeUnit.SECONDS);
      assertThat(channel.isOpen()).isFalse();

      ownerScheduler.shutdown();
      owner.shutdown();
      assertThat(owner.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    } finally {
      executor.blockOpen.countDown();
      ownerScheduler.shutdownNow();
      owner.shutdownNow();
    }
  }

  @Test
  @DisplayName("clearing during a blocked open closes the late channel before reuse")
  void clearBindingDuringOpenRetainsBackgroundChannelOwnership() throws Exception {
    ExecutorService owner =
        Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("bbv-terminal-clear-race-test-", 0).factory());
    ScheduledExecutorService ownerScheduler =
        Executors.newSingleThreadScheduledExecutor(
            Thread.ofVirtual().name("bbv-terminal-clear-race-test-timer-", 0).factory());
    FakeCommandExecutor oldExecutor = new FakeCommandExecutor();
    FakeChannel oldChannel = oldExecutor.enqueueChannel();
    oldExecutor.blockOpen = new CountDownLatch(1);
    SshTerminalView view = onEdt(() -> new SshTerminalView(owner, ownerScheduler));

    try {
      onEdt(
          () -> {
            view.bind("old.example", "/old", oldExecutor);
            view.connect();
            return null;
          });
      assertThat(oldExecutor.openStarted.await(5, TimeUnit.SECONDS)).isTrue();

      CompletionStage<Void> cleared = onEdt(view::clearBindingAsync);

      assertThat(cleared.toCompletableFuture()).isNotDone();
      assertThatThrownBy(
              () ->
                  onEdt(
                      () -> {
                        view.bind("too-soon.example", "/new", new FakeCommandExecutor());
                        return null;
                      }))
          .hasRootCauseMessage("the previous terminal binding is still closing");
      oldExecutor.blockOpen.countDown();
      cleared.toCompletableFuture().get(5, TimeUnit.SECONDS);
      assertThat(oldChannel.isOpen()).isFalse();

      onEdt(
          () -> {
            view.bind("new.example", "/new", new FakeCommandExecutor());
            return null;
          });
      assertThat(onEdt(view::locationForTest)).contains("new.example", "/new");
      onEdt(view::closeAsync).toCompletableFuture().get(5, TimeUnit.SECONDS);
    } finally {
      oldExecutor.blockOpen.countDown();
      ownerScheduler.shutdownNow();
      owner.shutdownNow();
    }
  }

  private static final class FakeCommandExecutor implements CommandExecutor {
    private final Queue<FakeChannel> channels = new ArrayDeque<>();
    private final AtomicInteger opens = new AtomicInteger();
    private final CountDownLatch openStarted = new CountDownLatch(1);
    private volatile CountDownLatch blockOpen;
    private volatile boolean openedOnEdt;
    private volatile IOException failure;

    FakeChannel enqueueChannel() throws IOException {
      FakeChannel channel = new FakeChannel();
      channels.add(channel);
      return channel;
    }

    @Override
    public InteractiveChannel openTerminal(String workingDirectory) throws IOException {
      openedOnEdt |= SwingUtilities.isEventDispatchThread();
      opens.incrementAndGet();
      openStarted.countDown();
      CountDownLatch blocker = blockOpen;
      if (blocker != null) {
        try {
          blocker.await();
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new IOException("interrupted while opening fake terminal", interrupted);
        }
      }
      if (failure != null) {
        throw failure;
      }
      FakeChannel channel = channels.poll();
      if (channel == null) {
        throw new IOException("no fake channel was queued");
      }
      return channel;
    }

    @Override
    public CommandResult run(CommandRequest request, Duration timeout) {
      throw new UnsupportedOperationException();
    }

    @Override
    public CommandResult runRedirectingStdout(
        CommandRequest request, Duration timeout, Path localOutputFile) {
      throw new UnsupportedOperationException();
    }

    @Override
    public RunningCommand start(CommandRequest request) {
      throw new UnsupportedOperationException();
    }
  }

  private static final class FakeChannel implements InteractiveChannel {
    private final PipedInputStream terminalInput = new PipedInputStream(64 * 1024);
    private final PipedOutputStream remoteOutput;
    private final InputStream trackedInput;
    private final ByteArrayOutputStream written = new ByteArrayOutputStream();
    private final CountDownLatch closed = new CountDownLatch(1);
    private final AtomicBoolean open = new AtomicBoolean(true);
    private final AtomicReference<TerminalSize> lastSize = new AtomicReference<>();
    private volatile boolean readOnEdt;
    private volatile boolean writtenOnEdt;
    private volatile boolean resizedOnEdt;

    private FakeChannel() throws IOException {
      remoteOutput = new PipedOutputStream(terminalInput);
      trackedInput =
          new InputStream() {
            @Override
            public int read() throws IOException {
              readOnEdt |= SwingUtilities.isEventDispatchThread();
              return terminalInput.read();
            }

            @Override
            public int read(byte[] bytes, int offset, int length) throws IOException {
              readOnEdt |= SwingUtilities.isEventDispatchThread();
              return terminalInput.read(bytes, offset, length);
            }

            @Override
            public int available() throws IOException {
              return terminalInput.available();
            }

            @Override
            public void close() throws IOException {
              terminalInput.close();
            }
          };
    }

    void emit(String text) throws IOException {
      remoteOutput.write(text.getBytes(StandardCharsets.UTF_8));
      remoteOutput.flush();
    }

    void emitUntilClosed(String text) {
      try {
        emit(text);
      } catch (IOException closedByGuard) {
        // The expected fail-closed path may close the pipe during this write.
      }
    }

    @Override
    public InputStream input() {
      return trackedInput;
    }

    @Override
    public synchronized void write(byte[] data, int offset, int length) {
      writtenOnEdt |= SwingUtilities.isEventDispatchThread();
      written.write(data, offset, length);
    }

    synchronized String writtenText() {
      return written.toString(StandardCharsets.UTF_8);
    }

    @Override
    public void resize(TerminalSize size) {
      resizedOnEdt |= SwingUtilities.isEventDispatchThread();
      lastSize.set(size);
    }

    @Override
    public boolean isOpen() {
      return open.get();
    }

    @Override
    public int awaitExit() throws InterruptedException {
      closed.await();
      return 0;
    }

    @Override
    public void close() {
      if (!open.compareAndSet(true, false)) {
        return;
      }
      try {
        remoteOutput.close();
      } catch (IOException ignored) {
        // Test teardown may race the emulator reaching EOF.
      }
      try {
        trackedInput.close();
      } catch (IOException ignored) {
        // Test teardown may race the emulator reaching EOF.
      }
      closed.countDown();
    }
  }

  private record ReaderProbe(Reader reader) {
    String readInChunks(int chunkSize) throws IOException {
      char[] chunk = new char[chunkSize];
      StringBuilder text = new StringBuilder();
      int count;
      while ((count = reader.read(chunk, 0, chunk.length)) >= 0) {
        text.append(chunk, 0, count);
      }
      return text.toString();
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
