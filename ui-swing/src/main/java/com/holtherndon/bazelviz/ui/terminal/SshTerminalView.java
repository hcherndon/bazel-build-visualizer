package com.holtherndon.bazelviz.ui.terminal;

import com.holtherndon.bazelviz.runner.runtime.CommandExecutor;
import com.holtherndon.bazelviz.runner.runtime.InteractiveChannel;
import com.holtherndon.bazelviz.runner.runtime.TerminalSize;
import com.holtherndon.bazelviz.ui.theme.PlainText;
import com.holtherndon.bazelviz.ui.theme.SelectableLabel;
import com.jediterm.core.util.TermSize;
import com.jediterm.terminal.HyperlinkStyle;
import com.jediterm.terminal.RequestOrigin;
import com.jediterm.terminal.TerminalColor;
import com.jediterm.terminal.TerminalExecutorServiceManager;
import com.jediterm.terminal.TextStyle;
import com.jediterm.terminal.TtyConnector;
import com.jediterm.terminal.model.TerminalTextBuffer;
import com.jediterm.terminal.ui.JediTermWidget;
import com.jediterm.terminal.ui.settings.DefaultSettingsProvider;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Font;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/**
 * A persistent xterm-compatible shell for the selected execution workspace.
 *
 * <p>The historical class name is retained for source compatibility. New code should use {@link
 * TerminalView}, which exposes the same transport-neutral behavior without implying that the
 * executor is necessarily remote.
 */
public class SshTerminalView extends JPanel implements AutoCloseable {

  private static final long serialVersionUID = 1L;
  private static final String CARD_EMPTY = "empty";
  private static final String CARD_TERMINAL = "terminal";

  /** Maximum scrollback lines retained by a newly constructed terminal. */
  public static final int DEFAULT_TRANSCRIPT_LINE_LIMIT = 20_000;

  /** Maximum characters accepted in one OSC or DCS string before the terminal is closed. */
  public static final int MAX_CONTROL_STRING_CHARACTERS = 64 * 1024;

  /** Maximum characters accepted in one CSI sequence before the terminal is closed. */
  public static final int MAX_CSI_CHARACTERS = 1000;

  private final ExecutorService worker;
  private final ScheduledExecutorService scheduler;
  private final int scrollbackLineLimit;
  private final JTextField location = SelectableLabel.create("No workspace is selected.");
  private final JTextField connectionStatus = SelectableLabel.create("Disconnected");
  private final JButton connect = new JButton("Connect");
  private final JButton disconnect = new JButton("Disconnect");
  private final JButton reconnect = new JButton("Reconnect");
  private final CardLayout terminalCards = new CardLayout();
  private final JPanel terminalDeck = new JPanel(terminalCards);
  private final JLabel emptyTerminal =
      new JLabel("Select a workspace to use the terminal.", SwingConstants.CENTER);

  private Binding binding;
  private ActiveTerminal active;
  private ConnectionState state = ConnectionState.UNAVAILABLE;
  private volatile long generation;
  private volatile boolean closed;
  private final AtomicInteger pendingChannelTasks = new AtomicInteger();
  private final CompletableFuture<Void> closeCompletion = new CompletableFuture<>();
  private CompletableFuture<Void> clearBindingCompletion = CompletableFuture.completedFuture(null);
  private long clearBindingGeneration = -1L;

  /**
   * Creates a terminal using the application's blocking-I/O executor.
   *
   * <p>The caller owns both executors and must keep them accepting work until {@link #closeAsync()}
   * completes. Closing this view never shuts either one down.
   */
  public SshTerminalView(ExecutorService worker, ScheduledExecutorService scheduler) {
    this(DEFAULT_TRANSCRIPT_LINE_LIMIT, worker, scheduler);
  }

  /** Creates a terminal with an explicit scrollback-line cap. */
  SshTerminalView(
      int scrollbackLineLimit, ExecutorService worker, ScheduledExecutorService scheduler) {
    super(new BorderLayout(0, 6));
    if (scrollbackLineLimit < 1) {
      throw new IllegalArgumentException("scrollbackLineLimit must be positive");
    }
    this.scrollbackLineLimit = scrollbackLineLimit;
    this.worker = Objects.requireNonNull(worker, "worker");
    this.scheduler = Objects.requireNonNull(scheduler, "scheduler");

    connect.setToolTipText(
        "Open an xterm-compatible terminal with full-screen programs and live resize.");
    connect.addActionListener(event -> connect());
    disconnect.setToolTipText("Close this terminal without closing the workspace.");
    disconnect.addActionListener(event -> disconnect());
    reconnect.setToolTipText("Close this terminal and open a fresh one in the same directory.");
    reconnect.addActionListener(event -> reconnect());

    JPanel target = new JPanel(new BorderLayout(6, 0));
    target.add(new JLabel("Workspace:"), BorderLayout.WEST);
    target.add(location, BorderLayout.CENTER);

    JPanel statePanel = new JPanel(new FlowLayout(FlowLayout.TRAILING, 6, 0));
    statePanel.add(new JLabel("Terminal:"));
    connectionStatus.setColumns(22);
    statePanel.add(connectionStatus);
    statePanel.add(connect);
    statePanel.add(disconnect);
    statePanel.add(reconnect);

    JPanel header = new JPanel(new BorderLayout(12, 0));
    header.setBorder(BorderFactory.createEmptyBorder(6, 8, 0, 8));
    header.add(target, BorderLayout.CENTER);
    header.add(statePanel, BorderLayout.EAST);

    PlainText.disableHtml(emptyTerminal);
    emptyTerminal.setEnabled(false);
    emptyTerminal.getAccessibleContext().setAccessibleName("Terminal state");
    terminalDeck.add(emptyTerminal, CARD_EMPTY);

    add(header, BorderLayout.NORTH);
    add(terminalDeck, BorderLayout.CENTER);
    showEmpty("Select a workspace to use the terminal.");
    updateControls();
  }

  /** Selects the execution used by future activation or Connect actions. */
  public void bind(String workspaceDescription, String workingDirectory, CommandExecutor executor) {
    requireEdt();
    if (closed) {
      throw new IllegalStateException("terminal view is closed");
    }
    if (!clearBindingCompletion.isDone()) {
      throw new IllegalStateException("the previous terminal binding is still closing");
    }
    Binding replacement =
        new Binding(
            requireText(workspaceDescription, "workspaceDescription"),
            requireText(workingDirectory, "workingDirectory"),
            Objects.requireNonNull(executor, "executor"));
    if (replacement.equals(binding)) {
      return;
    }
    long wanted = ++generation;
    ActiveTerminal old = detachActive();
    binding = replacement;
    location.setText(replacement.workspaceDescription + " · " + replacement.workingDirectory);
    location.setToolTipText(PlainText.tooltip(location.getText()));
    setState(ConnectionState.DISCONNECTED, "Disconnected");
    showEmpty("Open the Terminal tab to start the shell.");
    closeOffEdt(old, wanted, null);
  }

  /** Removes the selected execution and closes this view's terminal. */
  public void clearBinding() {
    clearBindingAsync();
  }

  /**
   * Removes the selected execution and completes after all old channel ownership is released.
   *
   * <p>The view remains reusable, but callers must wait for this stage before binding a new
   * execution or closing the old SSH transport. An {@code openTerminal} call already in flight is
   * included: its late channel is rejected and closed before this stage completes.
   */
  public CompletionStage<Void> clearBindingAsync() {
    requireEdt();
    if (closed) {
      return closeCompletion;
    }
    if (!clearBindingCompletion.isDone()) {
      return clearBindingCompletion;
    }
    long wanted = ++generation;
    ActiveTerminal old = detachActive();
    binding = null;
    location.setText("No workspace is selected.");
    location.setToolTipText(null);
    setState(ConnectionState.UNAVAILABLE, "Disconnected");
    showEmpty("Select a workspace to use the terminal.");
    CompletableFuture<Void> completion = new CompletableFuture<>();
    clearBindingCompletion = completion;
    clearBindingGeneration = wanted;
    closeOffEdt(old, wanted, () -> completeBindingClearIfIdle(wanted, completion));
    completeBindingClearIfIdle(wanted, completion);
    return completion;
  }

  /**
   * Activates this card, opening its shell once when a workspace is bound.
   *
   * <p>Calling this repeatedly is safe. An already connected or connecting terminal is left in
   * place, which lets navigation preserve one shell for the lifetime of the selected workspace.
   */
  public void activate() {
    connect();
  }

  /** Opens the bound shell. Transport setup runs away from the EDT. */
  public void connect() {
    requireEdt();
    if (closed
        || binding == null
        || state == ConnectionState.CONNECTING
        || state == ConnectionState.CONNECTED
        || state == ConnectionState.DISCONNECTING) {
      return;
    }
    openAfterClosing(null);
  }

  /** Closes the active terminal but keeps its execution selected. */
  public void disconnect() {
    requireEdt();
    if (closed || binding == null) {
      return;
    }
    long wanted = ++generation;
    ActiveTerminal old = detachActive();
    if (old == null) {
      setState(ConnectionState.DISCONNECTED, "Disconnected");
      showEmpty("Open the Terminal tab to start the shell.");
      return;
    }
    setState(ConnectionState.DISCONNECTING, "Disconnecting…");
    showEmpty("Closing the terminal…");
    closeOffEdt(
        old,
        wanted,
        () -> {
          setState(ConnectionState.DISCONNECTED, "Disconnected");
          showEmpty("Open the Terminal tab to start the shell.");
        });
  }

  /** Replaces the current terminal with a fresh one in the same directory. */
  public void reconnect() {
    requireEdt();
    if (closed || binding == null) {
      return;
    }
    openAfterClosing(detachActive());
  }

  /** The current visible connection state. */
  public ConnectionState connectionState() {
    return state;
  }

  /** Recolours an active terminal without reconnecting or replacing its PTY. */
  public void refreshTheme() {
    requireEdt();
    ActiveTerminal current = active;
    if (current == null) {
      return;
    }
    current.settings.refreshTheme();
    current.widget.getTerminal().getStyleState().setDefaultStyle(current.settings.defaultStyle());
    current.widget.updateUI();
    current.widget.getTerminalPanel().revalidate();
    current.widget.getTerminalPanel().repaint();
  }

  @Override
  public void close() {
    closeAsync();
  }

  /**
   * Closes this terminal and completes after all owned channel work finishes bounded teardown.
   *
   * <p>This includes an {@code openTerminal} call that has not returned yet: a channel arriving
   * after close is rejected and closed before the stage completes. The stage never blocks its
   * caller. It gives a workspace owner a hand-off point before releasing the SSH execution and
   * shutting down the injected terminal executors.
   */
  public CompletionStage<Void> closeAsync() {
    if (!SwingUtilities.isEventDispatchThread()) {
      try {
        runOnEdtAndWait(this::beginClose);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        closeCompletion.completeExceptionally(interrupted);
      }
      return closeCompletion;
    }
    beginClose();
    return closeCompletion;
  }

  private void beginClose() {
    requireEdt();
    if (closed) {
      return;
    }
    CompletableFuture<Void> clearing = clearBindingCompletion;
    closeCompletion.whenComplete(
        (ignored, failure) -> {
          if (failure == null) {
            clearing.complete(null);
          } else {
            clearing.completeExceptionally(failure);
          }
        });
    closed = true;
    long wanted = ++generation;
    ActiveTerminal old = detachActive();
    binding = null;
    setState(ConnectionState.UNAVAILABLE, "Closed");
    showEmpty("Terminal closed.");
    closeOffEdt(old, wanted, this::completeCloseIfIdle);
  }

  private void openAfterClosing(ActiveTerminal old) {
    Binding wantedBinding = binding;
    long wanted = ++generation;
    setState(ConnectionState.CONNECTING, "Connecting…");
    showEmpty("Opening the terminal…");
    executeChannelTask(
        () -> {
          InteractiveChannel opened = null;
          try {
            if (old != null) {
              old.connector.close();
            }
            if (closed || wanted != generation) {
              return;
            }
            opened = wantedBinding.executor.openTerminal(wantedBinding.workingDirectory);
            if (closed || wanted != generation) {
              opened.close();
              opened = null;
              return;
            }
            InteractiveChannel candidate = opened;
            AtomicBoolean adopted = new AtomicBoolean();
            runOnEdtAndWait(
                () ->
                    adopted.set(
                        channelOpened(candidate, wantedBinding.workspaceDescription, wanted)));
            if (adopted.get()) {
              opened = null;
            }
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            SwingUtilities.invokeLater(() -> connectionFailed(interrupted, wanted));
          } catch (IOException | RuntimeException failure) {
            SwingUtilities.invokeLater(() -> connectionFailed(failure, wanted));
          } finally {
            if (opened != null) {
              opened.close();
            }
          }
        });
  }

  /** Returns whether the EDT adopted ownership of {@code opened}. */
  private boolean channelOpened(
      InteractiveChannel opened, String workspaceDescription, long wanted) {
    requireEdt();
    if (closed || wanted != generation) {
      return false;
    }
    try {
      BbvTerminalSettings settings = new BbvTerminalSettings(scrollbackLineLimit);
      ChannelTtyConnector connector =
          new ChannelTtyConnector(
              opened,
              "Terminal " + workspaceDescription,
              failure ->
                  SwingUtilities.invokeLater(
                      () -> terminalTransportFailed(opened, wanted, failure)));
      TerminalExecutorServiceManager executorManager =
          new BorrowedTerminalExecutorServiceManager(worker, scheduler);
      JediTermWidget widget = ManagedJediTermWidget.create(80, 24, settings, executorManager);
      widget.getTerminal().getStyleState().setDefaultStyle(settings.defaultStyle());
      widget.getAccessibleContext().setAccessibleName("Terminal");
      widget.setTtyConnector(connector);
      active = new ActiveTerminal(opened, connector, widget, settings);
      terminalDeck.add(widget, CARD_TERMINAL);
      terminalCards.show(terminalDeck, CARD_TERMINAL);
      terminalDeck.revalidate();
      terminalDeck.repaint();
      widget.start();
      widget.requestFocusInWindow();
      setState(ConnectionState.CONNECTED, "Connected");
      awaitExit(active, wanted);
      return true;
    } catch (RuntimeException failure) {
      connectionFailed(failure, wanted);
      return false;
    }
  }

  private void awaitExit(ActiveTerminal terminal, long wanted) {
    execute(
        () -> {
          int exitCode;
          try {
            exitCode = terminal.channel.awaitExit();
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return;
          } catch (RuntimeException failure) {
            SwingUtilities.invokeLater(() -> connectionFailed(failure, wanted));
            return;
          }
          int completedExitCode = exitCode;
          SwingUtilities.invokeLater(() -> channelExited(terminal, wanted, completedExitCode));
        });
  }

  private void channelExited(ActiveTerminal exited, long wanted, int exitCode) {
    requireEdt();
    if (closed || wanted != generation || active != exited) {
      return;
    }
    detachActive();
    if (exitCode == 0) {
      setState(ConnectionState.DISCONNECTED, "Shell exited (0)");
    } else {
      setState(ConnectionState.FAILED, "Shell exited (" + exitCode + ")");
    }
    showEmpty("The shell has exited. Select Reconnect to open a new terminal.");
  }

  private void terminalTransportFailed(
      InteractiveChannel failed, long wanted, IOException failure) {
    requireEdt();
    if (closed || wanted != generation || active == null || active.channel != failed) {
      return;
    }
    ActiveTerminal old = detachActive();
    long closeGeneration = ++generation;
    String detail = "Terminal closed: " + describe(failure);
    setState(ConnectionState.FAILED, detail);
    showEmpty(detail);
    closeOffEdt(old, closeGeneration, null);
  }

  private void connectionFailed(Throwable failure, long wanted) {
    requireEdt();
    if (closed || wanted != generation) {
      return;
    }
    ActiveTerminal old = detachActive();
    String detail = "Could not connect: " + describe(failure);
    setState(ConnectionState.FAILED, detail);
    showEmpty(detail);
    closeOffEdt(old, wanted, null);
  }

  private ActiveTerminal detachActive() {
    requireEdt();
    ActiveTerminal old = active;
    active = null;
    if (old != null) {
      old.widget.close();
      terminalDeck.remove(old.widget);
      terminalDeck.revalidate();
      terminalDeck.repaint();
    }
    return old;
  }

  private void showEmpty(String message) {
    emptyTerminal.setText(message);
    emptyTerminal.setToolTipText(PlainText.tooltip(message));
    terminalCards.show(terminalDeck, CARD_EMPTY);
  }

  private void closeOffEdt(ActiveTerminal old, long wanted, Runnable afterCloseOnEdt) {
    if (old == null) {
      if (afterCloseOnEdt != null
          && (!closed || state == ConnectionState.UNAVAILABLE)
          && wanted == generation) {
        afterCloseOnEdt.run();
      }
      return;
    }
    Runnable closeTask =
        () -> {
          old.connector.close();
          if (afterCloseOnEdt != null) {
            SwingUtilities.invokeLater(
                () -> {
                  if ((!closed || state == ConnectionState.UNAVAILABLE) && wanted == generation) {
                    afterCloseOnEdt.run();
                  }
                });
          }
        };
    executeChannelTask(closeTask);
  }

  private void execute(Runnable task) {
    worker.execute(task);
  }

  /**
   * Runs work that temporarily owns a terminal channel or connector.
   *
   * <p>The reservation is made before enqueueing so {@link #closeAsync()} cannot observe an empty
   * {@code active} slot and finish while an {@code openTerminal} call is still in flight.
   */
  private void executeChannelTask(Runnable task) {
    pendingChannelTasks.incrementAndGet();
    try {
      worker.execute(
          () -> {
            try {
              task.run();
            } finally {
              channelTaskFinished();
            }
          });
    } catch (RuntimeException rejected) {
      channelTaskFinished();
      throw rejected;
    }
  }

  private void channelTaskFinished() {
    int remaining = pendingChannelTasks.decrementAndGet();
    if (remaining < 0) {
      closeCompletion.completeExceptionally(
          new IllegalStateException("terminal channel accounting became negative"));
      return;
    }
    if (remaining == 0) {
      SwingUtilities.invokeLater(
          () -> {
            if (closed) {
              completeCloseIfIdle();
            } else {
              completeBindingClearIfIdle(clearBindingGeneration, clearBindingCompletion);
            }
          });
    }
  }

  private void completeBindingClearIfIdle(long wanted, CompletableFuture<Void> completion) {
    requireEdt();
    if (!closed
        && wanted == generation
        && binding == null
        && active == null
        && pendingChannelTasks.get() == 0) {
      completion.complete(null);
    }
  }

  private void completeCloseIfIdle() {
    requireEdt();
    if (closed && active == null && pendingChannelTasks.get() == 0) {
      closeCompletion.complete(null);
    }
  }

  private static void runOnEdtAndWait(Runnable task) throws InterruptedException {
    if (SwingUtilities.isEventDispatchThread()) {
      task.run();
      return;
    }
    try {
      SwingUtilities.invokeAndWait(task);
    } catch (InvocationTargetException failure) {
      Throwable cause = failure.getCause();
      if (cause instanceof RuntimeException runtimeFailure) {
        throw runtimeFailure;
      }
      if (cause instanceof Error error) {
        throw error;
      }
      throw new IllegalStateException("terminal EDT handoff failed", cause);
    }
  }

  private void setState(ConnectionState newState, String detail) {
    state = newState;
    connectionStatus.setText(detail);
    connectionStatus.setToolTipText(PlainText.tooltip(detail));
    updateControls();
  }

  private void updateControls() {
    boolean bound = !closed && binding != null;
    connect.setEnabled(
        bound
            && state != ConnectionState.CONNECTING
            && state != ConnectionState.CONNECTED
            && state != ConnectionState.DISCONNECTING);
    disconnect.setEnabled(
        bound && (state == ConnectionState.CONNECTING || state == ConnectionState.CONNECTED));
    reconnect.setEnabled(
        bound && state != ConnectionState.CONNECTING && state != ConnectionState.DISCONNECTING);
  }

  private static String describe(Throwable failure) {
    String message = failure.getMessage();
    return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
  }

  private static String requireText(String value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isBlank()) {
      throw new IllegalArgumentException(name + " cannot be blank");
    }
    return value;
  }

  private static void requireEdt() {
    if (!SwingUtilities.isEventDispatchThread()) {
      throw new IllegalStateException("terminal UI changes must run on the EDT");
    }
  }

  /** States exposed to the terminal card and its enclosing navigation shell. */
  public enum ConnectionState {
    UNAVAILABLE,
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    DISCONNECTING,
    FAILED
  }

  private record Binding(
      String workspaceDescription, String workingDirectory, CommandExecutor executor) {}

  private record ActiveTerminal(
      InteractiveChannel channel,
      ChannelTtyConnector connector,
      JediTermWidget widget,
      BbvTerminalSettings settings) {}

  /** JediTerm's pull/resize contract over BBV's transport-neutral terminal channel. */
  private static final class ChannelTtyConnector implements TtyConnector {

    private final InteractiveChannel channel;
    private final Reader reader;
    private final String name;
    private final Consumer<IOException> failureListener;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean failureReported = new AtomicBoolean();

    private ChannelTtyConnector(
        InteractiveChannel channel, String name, Consumer<IOException> inputFailure) {
      this.channel = Objects.requireNonNull(channel, "channel");
      this.failureListener = Objects.requireNonNull(inputFailure, "inputFailure");
      this.reader =
          new BoundedTerminalReader(
              new InputStreamReader(channel.input(), StandardCharsets.UTF_8),
              MAX_CSI_CHARACTERS,
              MAX_CONTROL_STRING_CHARACTERS,
              this::reportFailure);
      this.name = requireText(name, "name");
    }

    @Override
    public int read(char[] buffer, int offset, int length) throws IOException {
      try {
        return reader.read(buffer, offset, length);
      } catch (IOException failure) {
        reportFailure(failure);
        throw failure;
      }
    }

    @Override
    public void write(byte[] bytes) throws IOException {
      try {
        channel.write(bytes, 0, bytes.length);
      } catch (IOException failure) {
        throw report("could not write to the terminal", failure);
      }
    }

    @Override
    public void write(String text) throws IOException {
      try {
        channel.write(text);
      } catch (IOException failure) {
        throw report("could not write to the terminal", failure);
      }
    }

    @Override
    public boolean isConnected() {
      return !closed.get() && channel.isOpen();
    }

    @Override
    public void resize(TermSize size) {
      try {
        channel.resize(new TerminalSize(size.getColumns(), size.getRows()));
      } catch (IOException failure) {
        IOException reported = report("could not resize the terminal", failure);
        throw new TerminalTransportException(reported.getMessage(), reported);
      }
    }

    @Override
    public int waitFor() throws InterruptedException {
      return channel.awaitExit();
    }

    @Override
    public boolean ready() throws IOException {
      try {
        return reader.ready();
      } catch (IOException failure) {
        reportFailure(failure);
        throw failure;
      }
    }

    @Override
    public String getName() {
      return name;
    }

    @Override
    public void close() {
      if (closed.compareAndSet(false, true)) {
        channel.close();
      }
    }

    private IOException report(String message, IOException cause) {
      IOException failure = new IOException(message + ": " + describe(cause), cause);
      reportFailure(failure);
      return failure;
    }

    private void reportFailure(IOException failure) {
      if (failureReported.compareAndSet(false, true)) {
        failureListener.accept(failure);
      }
    }
  }

  private static final class TerminalTransportException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private TerminalTransportException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  /**
   * Prevents JediTerm from creating its default cached and scheduled platform thread pools. The
   * window owns the supplied services, so a widget close stops only its session; the window shuts
   * the shared services down after the terminal view has handed off all closing work.
   */
  private record BorrowedTerminalExecutorServiceManager(
      ExecutorService worker, ScheduledExecutorService scheduler)
      implements TerminalExecutorServiceManager {

    private BorrowedTerminalExecutorServiceManager {
      Objects.requireNonNull(worker, "worker");
      Objects.requireNonNull(scheduler, "scheduler");
    }

    @Override
    public ScheduledExecutorService getSingleThreadScheduledExecutor() {
      return scheduler;
    }

    @Override
    public ExecutorService getUnboundedExecutorService() {
      return worker;
    }

    @Override
    public void shutdownWhenAllExecuted() {
      // Borrowed services: their MainWindow owner closes them.
    }
  }

  /**
   * JediTerm calls its executor-manager factory from its superclass constructor. A
   * construction-scoped value is therefore needed to supply the already-owned manager before
   * subclass fields can be assigned.
   */
  private static final class ManagedJediTermWidget extends JediTermWidget {

    private static final long serialVersionUID = 1L;
    private static final ThreadLocal<TerminalExecutorServiceManager> CONSTRUCTING_MANAGER =
        new ThreadLocal<>();

    private ManagedJediTermWidget(int columns, int rows, BbvTerminalSettings settings) {
      super(columns, rows, settings);
    }

    private static ManagedJediTermWidget create(
        int columns,
        int rows,
        BbvTerminalSettings settings,
        TerminalExecutorServiceManager manager) {
      Objects.requireNonNull(manager, "manager");
      if (CONSTRUCTING_MANAGER.get() != null) {
        throw new IllegalStateException("nested JediTerm widget construction");
      }
      CONSTRUCTING_MANAGER.set(manager);
      try {
        return new ManagedJediTermWidget(columns, rows, settings);
      } finally {
        CONSTRUCTING_MANAGER.remove();
      }
    }

    @Override
    protected TerminalExecutorServiceManager createExecutorServiceManager() {
      TerminalExecutorServiceManager manager = CONSTRUCTING_MANAGER.get();
      if (manager == null) {
        throw new IllegalStateException("JediTerm executor manager was not supplied");
      }
      return manager;
    }
  }

  /** FlatLaf-aware terminal colours with bounded JediTerm scrollback. */
  private static final class BbvTerminalSettings extends DefaultSettingsProvider {

    private final int scrollbackLineLimit;
    private volatile Font font;
    private volatile TerminalColor foreground;
    private volatile TerminalColor background;
    private volatile TextStyle selection;
    private volatile TextStyle found;
    private volatile TextStyle hyperlink;

    private BbvTerminalSettings(int scrollbackLineLimit) {
      this.scrollbackLineLimit = scrollbackLineLimit;
      refreshTheme();
    }

    private void refreshTheme() {
      Color panelForeground = uiColor("TextArea.foreground", Color.BLACK);
      Color panelBackground = uiColor("TextArea.background", Color.WHITE);
      Color selectedForeground = uiColor("TextArea.selectionForeground", panelForeground);
      Color selectedBackground = uiColor("TextArea.selectionBackground", new Color(82, 109, 165));
      Color foundBackground = uiColor("Component.focusColor", new Color(255, 220, 90));
      Color link = uiColor("Component.linkColor", new Color(45, 105, 190));
      Font uiFont = UIManager.getFont("TextArea.font");
      float size = uiFont == null ? super.getTerminalFontSize() : uiFont.getSize2D();
      font = new Font(Font.MONOSPACED, Font.PLAIN, Math.max(10, Math.round(size)));
      foreground = terminalColor(panelForeground);
      background = terminalColor(panelBackground);
      selection =
          new TextStyle(terminalColor(selectedForeground), terminalColor(selectedBackground));
      found =
          new TextStyle(terminalColor(contrast(foundBackground)), terminalColor(foundBackground));
      hyperlink = new TextStyle(terminalColor(link), background);
    }

    private TextStyle defaultStyle() {
      return new TextStyle(foreground, background);
    }

    @Override
    public Font getTerminalFont() {
      return font;
    }

    @Override
    public float getTerminalFontSize() {
      return font.getSize2D();
    }

    @Override
    public TerminalColor getDefaultForeground() {
      return foreground;
    }

    @Override
    public TerminalColor getDefaultBackground() {
      return background;
    }

    @Override
    public TextStyle getSelectionColor() {
      return selection;
    }

    @Override
    public TextStyle getFoundPatternColor() {
      return found;
    }

    @Override
    public TextStyle getHyperlinkColor() {
      return hyperlink;
    }

    @Override
    public HyperlinkStyle.HighlightMode getHyperlinkHighlightingMode() {
      return HyperlinkStyle.HighlightMode.HOVER;
    }

    @Override
    public int getBufferMaxLinesCount() {
      return scrollbackLineLimit;
    }

    @Override
    public boolean audibleBell() {
      return false;
    }

    private static Color uiColor(String key, Color fallback) {
      Color value = UIManager.getColor(key);
      return value == null ? fallback : value;
    }

    private static TerminalColor terminalColor(Color value) {
      return TerminalColor.rgb(value.getRed(), value.getGreen(), value.getBlue());
    }

    private static Color contrast(Color background) {
      double luminance =
          0.2126 * background.getRed()
              + 0.7152 * background.getGreen()
              + 0.0722 * background.getBlue();
      return luminance < 140 ? Color.WHITE : Color.BLACK;
    }
  }

  // Focused package tests use these instead of depending on component ordering.
  String renderedTranscriptForTest() {
    ActiveTerminal current = active;
    if (current == null) {
      return "";
    }
    TerminalTextBuffer buffer = current.widget.getTerminalTextBuffer();
    buffer.lock();
    try {
      StringBuilder text = new StringBuilder();
      for (int index = 0; index < buffer.getHistoryLinesStorage().getSize(); index++) {
        text.append(buffer.getHistoryLinesStorage().get(index).getText()).append('\n');
      }
      return text.append(buffer.getScreenLines()).toString();
    } finally {
      buffer.unlock();
    }
  }

  int historyLinesForTest() {
    ActiveTerminal current = active;
    return current == null ? 0 : current.widget.getTerminalTextBuffer().getHistoryLinesCount();
  }

  int scrollbackLineLimitForTest() {
    return scrollbackLineLimit;
  }

  int historyLimitForTest() {
    return scrollbackLineLimit;
  }

  String capabilityForTest() {
    return connect.getToolTipText();
  }

  String terminalComponentClassForTest() {
    return active == null ? "" : active.widget.getClass().getName();
  }

  int terminalDefaultBackgroundRgbForTest() {
    if (active == null) {
      return 0;
    }
    com.jediterm.core.Color colour =
        active.widget.getTerminal().getStyleState().getDefaultBackground().toColor();
    return (colour.getRed() << 16) | (colour.getGreen() << 8) | colour.getBlue();
  }

  boolean usesBorrowedExecutorsForTest() {
    if (active == null) {
      return false;
    }
    TerminalExecutorServiceManager manager = active.widget.getExecutorServiceManager();
    return manager.getUnboundedExecutorService() == worker
        && manager.getSingleThreadScheduledExecutor() == scheduler;
  }

  String statusForTest() {
    return connectionStatus.getText();
  }

  String locationForTest() {
    return location.getText();
  }

  String emptyMessageForTest() {
    return emptyTerminal.getText();
  }

  String screenTextForTest() {
    return renderedTranscriptForTest();
  }

  boolean alternateScreenForTest() {
    ActiveTerminal current = active;
    return current != null && current.widget.getTerminalTextBuffer().isUsingAlternateBuffer();
  }

  void sendTextForTest(String text) {
    requireEdt();
    ActiveTerminal current = active;
    if (current != null) {
      current.widget.getTerminalStarter().sendString(text, false);
    }
  }

  void resizeForTest(int columns, int rows) {
    requireEdt();
    ActiveTerminal current = active;
    if (current != null) {
      current
          .widget
          .getTerminalStarter()
          .postResize(new TermSize(columns, rows), RequestOrigin.User);
    }
  }

  void requestResizeForTest(int columns, int rows) {
    resizeForTest(columns, rows);
  }
}
