package com.holtherndon.bazelviz.ui.theme;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.util.Objects;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.UIManager;

/** Shared, per-window chrome above one navigation page. */
public final class PageToolbar extends JPanel {

  private static final long serialVersionUID = 1L;
  private static final int MAX_WORKSPACE_WIDTH = 260;
  private static final int MAX_METADATA_WIDTH = 460;

  private final JLabel title;
  private final JLabel workspaceSeparator = separator();
  private final CappedSelectableLabel workspace = new CappedSelectableLabel(MAX_WORKSPACE_WIDTH);
  private final JLabel metadataSeparator = separator();
  private final CappedSelectableLabel metadata = new CappedSelectableLabel(MAX_METADATA_WIDTH);
  private final JPanel heading = new JPanel(new WrapLayout(FlowLayout.LEADING, 6, 2));
  private final JPanel controls = new JPanel(new BorderLayout());
  private int actionCount;

  public PageToolbar(String pageTitle) {
    setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
    setBorder(BorderFactory.createEmptyBorder(5, 8, 4, 8));

    title = PlainText.disableHtml(new JLabel(requireText(pageTitle, "pageTitle")));
    title.setFont(title.getFont().deriveFont(Font.BOLD));
    title.getAccessibleContext().setAccessibleName(pageTitle);
    workspace.getAccessibleContext().setAccessibleName("Workspace");
    metadata.getAccessibleContext().setAccessibleName("Page metadata");

    heading.setAlignmentX(LEFT_ALIGNMENT);
    controls.setAlignmentX(LEFT_ALIGNMENT);
    heading.add(title);
    heading.add(workspaceSeparator);
    heading.add(workspace);
    heading.add(metadataSeparator);
    heading.add(metadata);
    add(heading);
    add(controls);
    controls.setVisible(false);
    updateSegments();
    getAccessibleContext().setAccessibleName(pageTitle + " page toolbar");
  }

  /** Shows the selected live workspace, or omits that segment when no workspace is active. */
  public void setWorkspaceName(String value) {
    setSelectableText(workspace, value, value);
    updateSegments();
  }

  /** Shows concise already-loaded page state, with the same text in its tooltip. */
  public void setMetadata(String value) {
    setMetadata(value, value);
  }

  /** Shows concise state while preserving a fuller accessible tooltip. */
  public void setMetadata(String value, String fullValue) {
    setSelectableText(metadata, value, fullValue);
    updateSegments();
  }

  /** Moves one existing compact page action into the shared first row. */
  public void addAction(Component action) {
    heading.add(Objects.requireNonNull(action, "action"));
    actionCount++;
    revalidate();
    repaint();
  }

  /** Moves an existing filter/control group below the shared identity row. */
  public void setControls(JComponent component) {
    controls.removeAll();
    if (component == null) {
      controls.setVisible(false);
    } else {
      controls.add(component, BorderLayout.CENTER);
      controls.setVisible(true);
    }
    revalidate();
    repaint();
  }

  /** Visible for page-shell and focused layout tests. */
  public String pageTitle() {
    return title.getText();
  }

  /** Visible for page-shell and focused layout tests. */
  public String workspaceName() {
    return workspace.getText();
  }

  /** Visible for page-shell and focused layout tests. */
  public String metadata() {
    return metadata.getText();
  }

  /** Visible for page-shell and focused layout tests. */
  public int actionCount() {
    return actionCount;
  }

  /** Visible for focused layout tests. */
  JPanel headingForTest() {
    return heading;
  }

  /** Visible for focused layout tests. */
  JPanel controlsForTest() {
    return controls;
  }

  /** Visible for focused plain-text and accessibility tests. */
  JTextField workspaceForTest() {
    return workspace;
  }

  /** Visible for focused plain-text and accessibility tests. */
  JTextField metadataForTest() {
    return metadata;
  }

  private void updateSegments() {
    boolean hasWorkspace = !workspace.getText().isBlank();
    boolean hasMetadata = !metadata.getText().isBlank();
    workspace.setVisible(hasWorkspace);
    workspaceSeparator.setVisible(hasWorkspace);
    metadata.setVisible(hasMetadata);
    metadataSeparator.setVisible(hasMetadata);
    revalidate();
    repaint();
  }

  private static void setSelectableText(
      CappedSelectableLabel field, String value, String tooltipValue) {
    String text = value == null ? "" : value.strip();
    String tooltip = tooltipValue == null ? text : tooltipValue.strip();
    field.setText(text);
    field.setCaretPosition(0);
    field.setToolTipText(tooltip.isBlank() ? null : PlainText.tooltip(tooltip));
    field.getAccessibleContext().setAccessibleDescription(tooltip.isBlank() ? null : tooltip);
  }

  private static JLabel separator() {
    JLabel separator = PlainText.disableHtml(new JLabel("·"));
    separator.setEnabled(false);
    return separator;
  }

  private static String requireText(String value, String name) {
    String text = Objects.requireNonNull(value, name).strip();
    if (text.isEmpty()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return text;
  }

  /** A selectable label which cannot make a toolbar wider than the application window. */
  private static final class CappedSelectableLabel extends JTextField {

    private static final long serialVersionUID = 1L;
    private final int maximumPreferredWidth;

    CappedSelectableLabel(int maximumPreferredWidth) {
      this.maximumPreferredWidth = maximumPreferredWidth;
      setEditable(false);
      setFocusable(true);
      setOpaque(false);
      setBorder(BorderFactory.createEmptyBorder());
      if (UIManager.getFont("Label.font") != null) {
        setFont(UIManager.getFont("Label.font"));
      }
      if (UIManager.getColor("Label.foreground") != null) {
        setForeground(UIManager.getColor("Label.foreground"));
      }
    }

    @Override
    public Dimension getPreferredSize() {
      Dimension preferred = super.getPreferredSize();
      return new Dimension(Math.min(maximumPreferredWidth, preferred.width), preferred.height);
    }

    @Override
    public Dimension getMinimumSize() {
      Dimension minimum = super.getMinimumSize();
      return new Dimension(0, minimum.height);
    }
  }
}
