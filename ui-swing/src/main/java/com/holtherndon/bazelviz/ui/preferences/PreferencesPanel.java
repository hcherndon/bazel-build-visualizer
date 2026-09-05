package com.holtherndon.bazelviz.ui.preferences;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.util.Objects;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;

/** The shared, extensible container for application settings. */
public final class PreferencesPanel extends JPanel {

  private static final long serialVersionUID = 1L;

  private final JTabbedPane tabs = new JTabbedPane();

  public PreferencesPanel(
      ThemePreferencesPanel theme,
      WorkspaceDiscoveryPreferencesPanel discovery,
      Runnable closeCallback) {
    super(new BorderLayout(0, 8));
    Objects.requireNonNull(theme, "theme");
    Objects.requireNonNull(discovery, "discovery");
    Objects.requireNonNull(closeCallback, "closeCallback");

    setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
    tabs.addTab("Theme", theme);
    tabs.addTab("Discovery", discovery);
    tabs.getAccessibleContext().setAccessibleName("Preference categories");
    tabs.getAccessibleContext()
        .setAccessibleDescription("Choose a category of application settings.");
    add(tabs, BorderLayout.CENTER);

    JButton close = new JButton("Close");
    close.addActionListener(event -> closeCallback.run());
    JPanel footer = new JPanel(new FlowLayout(FlowLayout.TRAILING, 0, 0));
    footer.add(close);
    add(footer, BorderLayout.SOUTH);
  }

  JTabbedPane tabsForTest() {
    return tabs;
  }

  /** Selects the Discovery settings category. */
  public void selectDiscovery() {
    tabs.setSelectedIndex(1);
  }
}
