package com.holtherndon.bazelviz.ui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import javax.swing.BorderFactory;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;

/**
 * Main application window shell (plan section 17.1). Phase 0 only proves the
 * window launches; launcher bar, navigation, inspector and status bar arrive
 * with their backing services in later phases.
 */
public final class MainWindow extends JFrame {

    public MainWindow() {
        super("Bazel Build Visualizer");
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setMinimumSize(new Dimension(960, 640));
        setSize(1280, 840);
        setLocationByPlatform(true);

        JPanel content = new JPanel(new BorderLayout());
        JLabel placeholder = new JLabel(
                "Bazel Build Visualizer — Phase 0 shell", SwingConstants.CENTER);
        placeholder.setBorder(BorderFactory.createEmptyBorder(24, 24, 24, 24));
        content.add(placeholder, BorderLayout.CENTER);
        setContentPane(content);
    }
}
