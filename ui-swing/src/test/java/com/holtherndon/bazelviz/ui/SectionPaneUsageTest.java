package com.holtherndon.bazelviz.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.ui.actions.ActionsView;
import com.holtherndon.bazelviz.ui.criticalpath.CriticalPathView;
import com.holtherndon.bazelviz.ui.errors.ErrorsView;
import com.holtherndon.bazelviz.ui.events.EventsView;
import com.holtherndon.bazelviz.ui.metrics.FindingsView;
import com.holtherndon.bazelviz.ui.query.QueryView;
import com.holtherndon.bazelviz.ui.targets.AllTargetsView;
import com.holtherndon.bazelviz.ui.targets.TargetsView;
import com.holtherndon.bazelviz.ui.tests.TestsView;
import com.holtherndon.bazelviz.ui.theme.SectionPane;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import javax.swing.border.TitledBorder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Keeps the shared visual grouping present on views with adjacent logical regions. */
final class SectionPaneUsageTest {

  @Test
  @DisplayName("master/detail and editor/result views frame their logical regions")
  void relatedViewsUseNamedSectionPanes() throws Exception {
    assertSections(ActionsView::new, "Actions", "Action details");
    assertSections(TargetsView::new, "Top level targets", "Target details");
    assertSections(AllTargetsView::new, "All targets", "Target details");
    assertSections(TestsView::new, "Tests", "Test details");
    assertSections(ErrorsView::new, "Errors", "Error details");
    assertSections(EventsView::new, "Events", "Event details");
    assertSections(
        CriticalPathView::new,
        "Critical path summary",
        "Path steps",
        "Observed action evidence",
        "Step details",
        "Coverage & interpretation");
    assertSections(FindingsView::new, "Coverage & analysis", "Findings", "Finding details");
    assertSections(QueryView::new, "Query", "Results");
  }

  private static void assertSections(Supplier<? extends JComponent> factory, String... expected)
      throws Exception {
    JComponent view = onEdt(factory);
    List<String> titles = new ArrayList<>();
    collectTitles(view, titles);
    assertThat(titles).contains(expected);
  }

  private static void collectTitles(Component component, List<String> titles) {
    if (component instanceof SectionPane section) {
      assertThat(section.getBorder()).isInstanceOf(TitledBorder.class);
      titles.add(((TitledBorder) section.getBorder()).getTitle());
      assertThat(section.getAccessibleContext().getAccessibleName()).isEqualTo(titles.getLast());
      assertThat(section.content().getParent()).isSameAs(section);
    }
    if (component instanceof Container container) {
      for (Component child : container.getComponents()) {
        collectTitles(child, titles);
      }
    }
  }

  private static <T> T onEdt(Supplier<T> supplier) throws Exception {
    AtomicReference<T> value = new AtomicReference<>();
    SwingUtilities.invokeAndWait(() -> value.set(supplier.get()));
    return value.get();
  }
}
