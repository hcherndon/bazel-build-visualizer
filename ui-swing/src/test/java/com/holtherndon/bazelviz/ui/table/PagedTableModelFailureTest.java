package com.holtherndon.bazelviz.ui.table;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

/**
 * A page that fails to load must be visibly different from a page that is still loading, and must
 * not be retried forever. Rendering a failure as the loading placeholder is a silent drop.
 */
final class PagedTableModelFailureTest {

  static {
    System.setProperty("java.awt.headless", "true");
  }

  private static final int PAGE_SIZE = 10;
  private static final int CACHE_CAPACITY = 4;

  private static final class QueueExecutor implements Executor {
    private final ArrayDeque<Runnable> queue = new ArrayDeque<>();

    @Override
    public void execute(Runnable command) {
      queue.add(command);
    }

    void runAll() {
      Runnable task;
      while ((task = queue.poll()) != null) {
        task.run();
      }
    }
  }

  /** Fails every fetch after the first {@code healthyPages} distinct pages. */
  private static final class FlakySource implements RowSource<String> {
    final List<Long> attempts = new ArrayList<>();
    boolean failing = true;

    @Override
    public long rowCount() {
      return 1000;
    }

    @Override
    public Page<String> fetchPage(long pageIndex, int pageSize) {
      attempts.add(pageIndex);
      if (failing) {
        throw new IllegalStateException("synthetic source failure for page " + pageIndex);
      }
      List<String> rows = new ArrayList<>(pageSize);
      for (int i = 0; i < pageSize; i++) {
        rows.add("r" + (pageIndex * pageSize + i));
      }
      return new Page<>(pageIndex, rows);
    }
  }

  private static PagedTableModel<String> model(RowSource<String> source, Executor executor) {
    return new PagedTableModel<>(
        source, List.of(new ColumnSpec<>("value", v -> v)), executor, PAGE_SIZE, CACHE_CAPACITY);
  }

  private static void pumpEdt() throws Exception {
    SwingUtilities.invokeAndWait(() -> {});
  }

  @Test
  void failedPageRendersAsErrorNotAsLoading() throws Exception {
    FlakySource source = new FlakySource();
    QueueExecutor executor = new QueueExecutor();
    PagedTableModel<String> model = model(source, executor);

    assertThat(model.getValueAt(0, 0)).isEqualTo(PagedTableModel.PLACEHOLDER);
    executor.runAll();
    pumpEdt();

    assertThat(model.getValueAt(0, 0))
        .as("a failed page must not look identical to a loading page")
        .isEqualTo(PagedTableModel.ERROR_PLACEHOLDER);
    assertThat(model.failedFetchCount()).isEqualTo(1);
    assertThat(model.lastFailure()).isInstanceOf(IllegalStateException.class);
    assertThat(model.isPageFailed(0)).isTrue();
    assertThat(model.isPageFailed(1)).isFalse();
  }

  @Test
  void failedPageIsNotRetriedOnEveryRepaint() throws Exception {
    FlakySource source = new FlakySource();
    QueueExecutor executor = new QueueExecutor();
    PagedTableModel<String> model = model(source, executor);

    model.getValueAt(0, 0);
    executor.runAll();
    pumpEdt();
    assertThat(source.attempts).hasSize(1);

    // Simulate many repaints over the same failed page.
    for (int i = 0; i < 50; i++) {
      model.getValueAt(0, 0);
      executor.runAll();
    }
    pumpEdt();

    assertThat(source.attempts)
        .as("a failing page must not be re-fetched on every repaint")
        .hasSize(1);
  }

  @Test
  void retryFailedPagesClearsTheFailureAndRefetches() throws Exception {
    FlakySource source = new FlakySource();
    QueueExecutor executor = new QueueExecutor();
    PagedTableModel<String> model = model(source, executor);

    model.getValueAt(0, 0);
    executor.runAll();
    pumpEdt();
    assertThat(model.getValueAt(0, 0)).isEqualTo(PagedTableModel.ERROR_PLACEHOLDER);

    source.failing = false;
    SwingUtilities.invokeAndWait(model::retryFailedPages);
    assertThat(model.isPageFailed(0)).isFalse();

    assertThat(model.getValueAt(0, 0)).isEqualTo(PagedTableModel.PLACEHOLDER);
    executor.runAll();
    pumpEdt();
    assertThat(model.getValueAt(0, 0)).isEqualTo("r0");
  }

  @Test
  void rejectedSubmissionDoesNotStrandThePageOrEscapeToTheEdt() throws Exception {
    FlakySource source = new FlakySource();
    source.failing = false;
    Executor rejecting =
        command -> {
          throw new RejectedExecutionException("queue full");
        };
    PagedTableModel<String> model = model(source, rejecting);

    // getValueAt runs on the EDT paint path; it must never propagate.
    Object value = model.getValueAt(0, 0);
    pumpEdt();

    assertThat(value).isEqualTo(PagedTableModel.ERROR_PLACEHOLDER);
    assertThat(model.failedFetchCount()).isEqualTo(1);
    assertThat(model.lastFailure()).isInstanceOf(RejectedExecutionException.class);
  }
}
