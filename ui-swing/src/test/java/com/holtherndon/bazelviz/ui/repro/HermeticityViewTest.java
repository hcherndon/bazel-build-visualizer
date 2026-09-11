package com.holtherndon.bazelviz.ui.repro;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.holtherndon.bazelviz.core.filter.FilterExpression;
import com.holtherndon.bazelviz.core.repro.ReproComparison;
import com.holtherndon.bazelviz.ui.filter.FilterBuilder;
import com.holtherndon.bazelviz.ui.theme.PageToolbar;
import com.holtherndon.bazelviz.ui.theme.ScrollableViewport;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JViewport;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

class HermeticityViewTest {
  @Test
  void sourceCallbacksAndToolbarDoNotOpenServicesImplicitly() throws Exception {
    AtomicInteger choices = new AtomicInteger();
    AtomicInteger comparisons = new AtomicInteger();
    HermeticityView view = edt(HermeticityView::new);
    try {
      edt(
          () -> {
            view.onChooseA(choices::incrementAndGet);
            view.onChooseB(choices::incrementAndGet);
            view.onCompare(comparisons::incrementAndGet);
            assertFalse(button(view, "Compare").isEnabled());
            button(view, "Choose A…").doClick();
            button(view, "Choose B…").doClick();
            assertEquals(2, choices.get());
            assertFalse(button(view, "Open run A").isEnabled());
            view.setSources("A.bin", "B.bin");
            return null;
          });
      await(() -> button(view, "Compare").isEnabled());
      edt(
          () -> {
            PageToolbar toolbar = new PageToolbar("Hermeticity");
            view.installPageToolbar(toolbar);
            assertEquals(4, toolbar.actionCount());
            button(toolbar, "Compare").doClick();
            assertEquals(1, comparisons.get());
            assertEquals("Choose two runs", toolbar.metadata());
            return null;
          });
    } finally {
      close(view);
    }
  }

  @Test
  void loadsPagedRowsFiltersAndIndependentlyPagedSelectableDetails() throws Exception {
    FakeComparison source = new FakeComparison("//:first");
    HermeticityView view = edt(HermeticityView::new);
    AtomicReference<String> openedBuild = new AtomicReference<>();
    try {
      edt(
          () -> {
            view.onOpenBuildFile(openedBuild::set);
            view.openComparison(cancelled -> source, "A.bin", "B.bin");
            return null;
          });
      await(() -> table(view, "hermeticity.changes").getRowCount() == 2);
      edt(
          () -> {
            JTable actions = table(view, "hermeticity.actions");
            assertTrue(actions.getRowSelectionAllowed());
            assertEquals(2, actions.getRowCount());
            assertEquals("//:first", actions.getValueAt(0, 1));
            button(view, "Open BUILD file…").doClick();
            assertEquals("//:first", openedBuild.get());
            JTable changes = table(view, "hermeticity.changes");
            changes.changeSelection(0, 2, false, false);
            JTextArea selected = area(view, "hermeticity.value");
            assertEquals("before/".repeat(80), selected.getText());
            assertTrue(selected.getLineWrap());
            assertFalse(selected.isEditable());
            button(view, "Next changes").doClick();
            return null;
          });
      await(() -> table(view, "hermeticity.changes").getRowCount() == 1);
      edt(
          () -> {
            button(view, "Previous changes").doClick();
            return null;
          });
      await(() -> table(view, "hermeticity.changes").getRowCount() == 2);
      edt(
          () -> {
            button(view, "Next page").doClick();
            return null;
          });
      await(() -> table(view, "hermeticity.actions").getRowCount() == 1);
      edt(
          () -> {
            assertEquals("//:last", table(view, "hermeticity.actions").getValueAt(0, 1));
            assertFalse(button(view, "Next page").isEnabled());
            FilterBuilder builder = find(view, FilterBuilder.class, ignored -> true);
            builder.setExpression(
                new FilterExpression.Group(
                    FilterExpression.Junction.ALL,
                    List.of(
                        new FilterExpression.Condition(
                            "target", FilterExpression.Operator.REGEX, List.of("^//")))));
            return null;
          });
      await(() -> table(view, "hermeticity.actions").getRowCount() == 2);
      assertEquals(
          FilterExpression.Operator.REGEX,
          ((FilterExpression.Condition)
                  ((FilterExpression.Group) source.filter.get()).children().getFirst())
              .operator());
      assertFalse(source.onEdt.get());
      edt(
          () -> {
            assertTrue(
                area(view, "hermeticity.summary")
                    .getText()
                    .contains("Equal results do not prove hermeticity"));
            assertTrue(
                area(view, "hermeticity.coverage").getText().contains("Fixture coverage note"));
            return null;
          });
    } finally {
      close(view);
    }
    assertTrue(source.closed.get());
  }

  @Test
  void replacementWaitsForCleanupAndIgnoresSupersededFactories() throws Exception {
    CountDownLatch closeStarted = new CountDownLatch(1);
    CountDownLatch releaseClose = new CountDownLatch(1);
    FakeComparison first =
        new FakeComparison("//:old") {
          @Override
          public void close() throws IOException {
            closeStarted.countDown();
            try {
              if (!releaseClose.await(5, TimeUnit.SECONDS))
                throw new IOException("Cleanup timed out");
            } catch (InterruptedException interrupted) {
              throw new IOException(interrupted);
            }
            super.close();
          }
        };
    FakeComparison latest = new FakeComparison("//:latest");
    AtomicInteger staleOpened = new AtomicInteger();
    AtomicInteger latestOpened = new AtomicInteger();
    HermeticityView view = edt(HermeticityView::new);
    try {
      edt(
          () -> {
            view.openComparison(cancelled -> first, "old A", "old B");
            return null;
          });
      await(() -> table(view, "hermeticity.actions").getRowCount() == 2);
      edt(
          () -> {
            view.openComparison(
                cancelled -> {
                  staleOpened.incrementAndGet();
                  return new FakeComparison("//:stale");
                },
                "stale A",
                "stale B");
            view.openComparison(
                cancelled -> {
                  latestOpened.incrementAndGet();
                  return latest;
                },
                "new A",
                "new B");
            return null;
          });
      assertTrue(closeStarted.await(5, TimeUnit.SECONDS));
      assertEquals(0, latestOpened.get());
      releaseClose.countDown();
      await(() -> table(view, "hermeticity.actions").getRowCount() == 2);
      edt(
          () -> {
            assertEquals("//:latest", table(view, "hermeticity.actions").getValueAt(0, 1));
            return null;
          });
      assertEquals(0, staleOpened.get());
      assertEquals(1, latestOpened.get());
      assertTrue(first.closed.get());
    } finally {
      releaseClose.countDown();
      close(view);
    }
  }

  @Test
  void cancelledOpeningNeverPublishesLateRowsAndStillClosesItsSource() throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    FakeComparison source = new FakeComparison("//:late");
    HermeticityView view = edt(HermeticityView::new);
    try {
      edt(
          () -> {
            view.openComparison(
                cancelled -> {
                  entered.countDown();
                  while (release.getCount() > 0) {
                    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(2));
                    Thread.interrupted();
                  }
                  return source;
                },
                "A",
                "B");
            return null;
          });
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      edt(
          () -> {
            button(view, "Cancel").doClick();
            return null;
          });
      release.countDown();
      await(() -> area(view, "hermeticity.status").getText().startsWith("Comparison cancelled"));
      assertTrue(source.closed.get());
      edt(
          () -> {
            assertEquals(0, table(view, "hermeticity.actions").getRowCount());
            return null;
          });
    } finally {
      release.countDown();
      close(view);
    }
  }

  @Test
  void invalidSourceCanBeReplacedAfterSuccessfulCleanup() throws Exception {
    HermeticityView view = edt(HermeticityView::new);
    FakeComparison valid = new FakeComparison("//:valid");
    try {
      edt(
          () -> {
            view.openComparison(
                cancelled -> {
                  throw new IOException("Malformed fixture log");
                },
                "bad A",
                "bad B");
            return null;
          });
      await(() -> area(view, "hermeticity.status").getText().contains("Malformed fixture log"));
      edt(
          () -> {
            view.openComparison(cancelled -> valid, "valid A", "valid B");
            return null;
          });
      await(() -> table(view, "hermeticity.actions").getRowCount() == 2);
      edt(
          () -> {
            assertEquals("//:valid", table(view, "hermeticity.actions").getValueAt(0, 1));
            return null;
          });
    } finally {
      close(view);
    }
  }

  @Test
  void preservedPartialRunsCanOpenWithoutCompleteExecutionLogs() throws Exception {
    HermeticityView view = edt(HermeticityView::new);
    AtomicInteger opened = new AtomicInteger();
    try {
      edt(
          () -> {
            view.onOpenRunA(opened::incrementAndGet);
            assertTrue(button(view, "Open run A").isEnabled());
            assertFalse(button(view, "Compare").isEnabled());
            button(view, "Open run A").doClick();
            assertEquals(1, opened.get());
            return null;
          });
    } finally {
      close(view);
    }
  }

  @Test
  void changingSourcesClearsPreviouslyDisplayedEvidence() throws Exception {
    HermeticityView view = edt(HermeticityView::new);
    FakeComparison previous = new FakeComparison("//:previous");
    try {
      edt(
          () -> {
            view.openComparison(cancelled -> previous, "old A", "old B");
            return null;
          });
      await(() -> table(view, "hermeticity.actions").getRowCount() == 2);
      edt(
          () -> {
            view.setSources("new A", "new B");
            assertEquals(0, table(view, "hermeticity.actions").getRowCount());
            assertTrue(
                area(view, "hermeticity.summary")
                    .getText()
                    .contains("No differences have been calculated"));
            return null;
          });
      await(() -> button(view, "Compare").isEnabled());
      assertTrue(previous.closed.get());
    } finally {
      close(view);
    }
  }

  @Test
  void narrowDifferencesScrollInsteadOfSqueezingBothTablesBelowTheirHeaders() throws Exception {
    HermeticityView view = edt(HermeticityView::new);
    try {
      edt(
          () -> {
            view.openComparison(cancelled -> new FakeComparison("//:target"), "A.bin", "B.bin");
            return null;
          });
      await(() -> table(view, "hermeticity.changes").getRowCount() == 2);
      edt(
          () -> {
            JPanel shell = new JPanel(new BorderLayout());
            PageToolbar toolbar = new PageToolbar("Hermeticity");
            toolbar.setWorkspaceName("Bazel source");
            view.installPageToolbar(toolbar);
            shell.add(toolbar, BorderLayout.NORTH);
            shell.add(view, BorderLayout.CENTER);
            find(view, JTabbedPane.class, ignored -> true).setSelectedIndex(1);
            JScrollPane scroll =
                find(
                    view,
                    JScrollPane.class,
                    candidate -> "hermeticity.differencesScroll".equals(candidate.getName()));
            assertNotNull(scroll);
            for (int width : List.of(1000, 460)) {
              shell.setSize(width, width == 1000 ? 900 : 500);
              for (int pass = 0; pass < 8; pass++) layout(shell);
              assertEquals(width == 460, scroll.getVerticalScrollBar().isVisible());
              assertFalse(scroll.getHorizontalScrollBar().isVisible());
              assertEquals(
                  scroll.getViewport().getExtentSize().width,
                  scroll.getViewport().getView().getWidth());
              for (String name : List.of("hermeticity.actions", "hermeticity.changes")) {
                JTable table = table(view, name);
                JViewport viewport = (JViewport) table.getParent();
                assertTrue(
                    viewport.getExtentSize().height >= table.getRowHeight() * 2,
                    name + " must retain visible rows, not only its table header");
              }
            }
            return null;
          });
    } finally {
      close(view);
    }
  }

  @Test
  void narrowSummaryAndCoverageTrackViewportWidthAndScrollVertically() throws Exception {
    HermeticityView view = edt(HermeticityView::new);
    try {
      edt(
          () -> {
            view.setSources("/long/source/path/".repeat(40), "/another/source/path/".repeat(40));
            view.setContextNotes(
                List.of("Long coverage note with enough words to wrap. ".repeat(100)));
            JPanel shell = new JPanel(new BorderLayout());
            PageToolbar toolbar = new PageToolbar("Hermeticity");
            view.installPageToolbar(toolbar);
            shell.add(toolbar, BorderLayout.NORTH);
            shell.add(view, BorderLayout.CENTER);
            shell.setSize(460, 460);
            layout(shell);
            JTabbedPane tabs = find(view, JTabbedPane.class, ignored -> true);
            assertEquals(3, tabs.getTabCount());
            for (int tab : List.of(0, 2)) {
              tabs.setSelectedIndex(tab);
              layout(shell);
              JScrollPane scroll =
                  find(
                      (Container) tabs.getComponentAt(tab),
                      JScrollPane.class,
                      candidate -> candidate.getViewport().getView() instanceof ScrollableViewport);
              assertNotNull(scroll);
              assertEquals(
                  JScrollPane.HORIZONTAL_SCROLLBAR_NEVER, scroll.getHorizontalScrollBarPolicy());
              ScrollableViewport content = (ScrollableViewport) scroll.getViewport().getView();
              assertTrue(content.getScrollableTracksViewportWidth());
              assertFalse(content.getScrollableTracksViewportHeight());
              assertTrue(scroll.getWidth() <= 460);
            }
            assertTrue(area(view, "hermeticity.coverage").getLineWrap());
            assertFalse(area(view, "hermeticity.coverage").isEditable());
            return null;
          });
    } finally {
      close(view);
    }
  }

  private static class FakeComparison implements ReproComparison {
    final AtomicBoolean onEdt = new AtomicBoolean();
    final AtomicBoolean closed = new AtomicBoolean();
    final AtomicReference<FilterExpression> filter = new AtomicReference<>();
    private final String firstLabel;

    FakeComparison(String firstLabel) {
      this.firstLabel = firstLabel;
    }

    private void checkThread() {
      if (SwingUtilities.isEventDispatchThread()) onEdt.set(true);
    }

    @Override
    public Summary summary() {
      checkThread();
      return new Summary(3, 3, 3, 1, 1, 1, 0, 0, List.of("Fixture coverage note"));
    }

    @Override
    public Page page(FilterExpression expression, long after, int limit) {
      checkThread();
      filter.set(expression);
      assertEquals(100, limit);
      return new Page(
          after == 0
              ? List.of(row(1, firstLabel), row(2, "//:second"))
              : List.of(row(3, "//:last")),
          3);
    }

    @Override
    public Details details(long id, long offset, int limit) {
      checkThread();
      List<FieldDifference> differences =
          offset == 0
              ? List.of(
                  new FieldDifference("Inputs", "input/path", "before/".repeat(80), "after"),
                  new FieldDifference("Outputs", "output/path", "old digest", "new digest"))
              : List.of(
                  new FieldDifference(
                      "Arguments", "3", "[private value; changed]", "[private value; changed]"));
      return new Details(row(id, id == 1 ? firstLabel : "//:other"), differences, 3, offset == 0);
    }

    @Override
    public void close() throws IOException {
      checkThread();
      closed.set(true);
    }

    private static Row row(long id, String label) {
      return new Row(
          id,
          label,
          "Genrule",
          "out/" + id,
          Finding.INPUT_DRIFT,
          false,
          true,
          true,
          "Recorded inputs changed.");
    }
  }

  private static void close(HermeticityView view) throws Exception {
    CompletableFuture<Void> completion = edt(() -> view.closeAsync().toCompletableFuture());
    completion.get(5, TimeUnit.SECONDS);
  }

  private static void await(BooleanSupplier condition) throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    while (!edt(condition::getAsBoolean)) {
      if (System.nanoTime() > deadline)
        throw new AssertionError("UI condition did not become true");
      LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
    }
  }

  private static <T> T edt(Supplier<T> action) throws Exception {
    AtomicReference<T> result = new AtomicReference<>();
    SwingUtilities.invokeAndWait(() -> result.set(action.get()));
    return result.get();
  }

  private static JButton button(Container root, String text) {
    return find(root, JButton.class, button -> button.getText().equals(text));
  }

  private static JTable table(Container root, String name) {
    return find(root, JTable.class, table -> name.equals(table.getName()));
  }

  private static JTextArea area(Container root, String name) {
    return find(root, JTextArea.class, area -> name.equals(area.getName()));
  }

  private static <T extends Component> T find(
      Container root, Class<T> type, Predicate<T> predicate) {
    if (type.isInstance(root) && predicate.test(type.cast(root))) return type.cast(root);
    for (Component component : root.getComponents()) {
      if (type.isInstance(component) && predicate.test(type.cast(component)))
        return type.cast(component);
      if (component instanceof Container nested) {
        T result = find(nested, type, predicate);
        if (result != null) return result;
      }
    }
    return null;
  }

  private static void layout(Container container) {
    container.invalidate();
    // Headless components do not receive addNotify(), which normally installs table headers.
    if (container instanceof JScrollPane scroll
        && scroll.getViewport().getView() instanceof JTable table
        && scroll.getColumnHeader() == null) scroll.setColumnHeaderView(table.getTableHeader());
    container.doLayout();
    for (Component child : container.getComponents()) {
      if (child instanceof Container nested) layout(nested);
    }
  }
}
