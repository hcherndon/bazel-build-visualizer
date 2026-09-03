package com.holtherndon.bazelviz.ui.theme;

import java.awt.BorderLayout;
import java.util.Objects;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JPanel;

/** A consistently framed, named region of a larger view. */
public final class SectionPane extends JPanel {

  private static final long serialVersionUID = 1L;

  private final JComponent content;

  public SectionPane(String title, JComponent content) {
    super(new BorderLayout());
    String name = Objects.requireNonNull(title, "title");
    this.content = Objects.requireNonNull(content, "content");
    setBorder(BorderFactory.createTitledBorder(name));
    getAccessibleContext().setAccessibleName(name);
    add(content, BorderLayout.CENTER);
  }

  /** The original component, retained without changing its own layout or scrolling. */
  public JComponent content() {
    return content;
  }
}
