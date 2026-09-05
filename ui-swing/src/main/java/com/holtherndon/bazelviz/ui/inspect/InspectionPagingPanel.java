package com.holtherndon.bazelviz.ui.inspect;

import com.holtherndon.bazelviz.storage.CountedPage;
import com.holtherndon.bazelviz.ui.theme.PlainText;
import com.holtherndon.bazelviz.ui.theme.WrapLayout;
import java.awt.FlowLayout;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;

/** Body-local controls for independently paged inspector collections. */
public final class InspectionPagingPanel extends JPanel {

  private static final long serialVersionUID = 1L;

  private final Map<String, PageControl> controls = new LinkedHashMap<>();

  public InspectionPagingPanel() {
    super(new WrapLayout(FlowLayout.LEFT, 8, 4));
    setBorder(BorderFactory.createEmptyBorder(0, 4, 4, 4));
    setVisible(false);
  }

  /** Removes every collection when the inspected entity changes. */
  public void clear() {
    controls.clear();
    removeAll();
    setVisible(false);
    revalidate();
    repaint();
  }

  /**
   * Shows one bounded page and its exact recorded-row total.
   *
   * <p>Updating one key leaves the other independently loaded collections in place. Only the
   * current page is retained; its range makes that replacement explicit.
   */
  public void setPage(String key, String pluralName, CountedPage<?, ?> page, Runnable loadNext) {
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(pluralName, "pluralName");
    Objects.requireNonNull(page, "page");
    Objects.requireNonNull(loadNext, "loadNext");
    controls.put(key, new PageControl(pluralName, page, loadNext));
    rebuild();
  }

  private void rebuild() {
    removeAll();
    for (PageControl control : controls.values()) {
      add(control.panel());
    }
    setVisible(!controls.isEmpty());
    revalidate();
    repaint();
  }

  private record PageControl(String pluralName, CountedPage<?, ?> page, Runnable loadNext) {
    private PageControl {
      Objects.requireNonNull(pluralName, "pluralName");
      Objects.requireNonNull(page, "page");
      Objects.requireNonNull(loadNext, "loadNext");
    }

    private JPanel panel() {
      JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
      long shown = page.rows().size();
      long omitted = page.totalRows() - shown;
      long through = page.shownThrough();
      long first = shown == 0 ? 0 : through - shown + 1;
      String range =
          shown == 0
              ? "0"
              : first == through
                  ? EntityFormat.count(first)
                  : EntityFormat.count(first) + "–" + EntityFormat.count(through);
      JLabel status =
          PlainText.disableHtml(
              new JLabel(
                  pluralName
                      + " "
                      + range
                      + " of "
                      + EntityFormat.count(page.totalRows())
                      + " · "
                      + EntityFormat.count(omitted)
                      + " not on this page"));
      panel.add(status);
      page.nextAnchor()
          .ifPresent(
              ignored -> {
                JButton next = new JButton("Load next " + pluralName.toLowerCase());
                next.addActionListener(event -> loadNext.run());
                panel.add(next);
              });
      return panel;
    }
  }
}
