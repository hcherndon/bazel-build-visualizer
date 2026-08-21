package com.holtherndon.bazelviz.ui;

import com.holtherndon.bazelviz.ui.nav.NavEntry;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.JSplitPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;

/**
 * Main application window shell (plan section 17.1). Phase 0 lays out the
 * frame regions — launcher bar, navigation sidebar, card-switched center,
 * status bar — as non-functional placeholders; each region gains its backing
 * service in the phase noted on its card.
 */
public final class MainWindow extends JFrame {

    // Capture presets A-D (plan section 4.2); Performance Diagnostics is the
    // recommended default. Placeholder-only in Phase 0 — the launcher bar
    // becomes functional with bazel-runner in Phase 2.
    private static final String[] PRESET_NAMES = {
        "Live Essentials",
        "Performance Diagnostics",
        "Full Graph Diagnostics",
        "Custom",
    };

    // Unknown-is-not-zero: counts are unknown until a session exists, so the
    // status bar shows an em dash, never "0".
    private static final String UNKNOWN = "—";

    public MainWindow() {
        super("Bazel Build Visualizer");
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setMinimumSize(new Dimension(960, 640));
        setSize(1280, 840);
        setLocationByPlatform(true);

        CardLayout cardLayout = new CardLayout();
        JPanel cards = new JPanel(cardLayout);
        for (NavEntry entry : NavEntry.values()) {
            cards.add(placeholderCard(entry), entry.cardName());
        }

        JSplitPane split = new JSplitPane(
                JSplitPane.HORIZONTAL_SPLIT, buildNavigation(cardLayout, cards), cards);
        split.setDividerLocation(180);
        split.setResizeWeight(0);

        JPanel content = new JPanel(new BorderLayout());
        content.add(buildLauncherBar(), BorderLayout.NORTH);
        content.add(split, BorderLayout.CENTER);
        content.add(buildStatusBar(), BorderLayout.SOUTH);
        setContentPane(content);
    }

    private static JComponent buildLauncherBar() {
        JComboBox<String> presets = new JComboBox<>(PRESET_NAMES);
        presets.setEnabled(false);

        JTextField command = new JTextField();
        command.setEnabled(false);

        JButton run = new JButton("Run");
        run.setEnabled(false);

        JPanel bar = new JPanel(new BorderLayout(8, 0));
        bar.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        bar.add(presets, BorderLayout.WEST);
        bar.add(command, BorderLayout.CENTER);
        bar.add(run, BorderLayout.EAST);

        JPanel north = new JPanel(new BorderLayout());
        north.add(bar, BorderLayout.CENTER);
        north.add(new JSeparator(), BorderLayout.SOUTH);
        return north;
    }

    private static JComponent buildNavigation(CardLayout cardLayout, JPanel cards) {
        JList<NavEntry> nav = new JList<>(NavEntry.values());
        nav.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        nav.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(
                    JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(
                        list, ((NavEntry) value).title(), index, isSelected, cellHasFocus);
                setBorder(BorderFactory.createEmptyBorder(6, 12, 6, 12));
                return this;
            }
        });
        nav.addListSelectionListener(event -> {
            if (event.getValueIsAdjusting()) {
                return;
            }
            NavEntry selected = nav.getSelectedValue();
            if (selected != null) {
                cardLayout.show(cards, selected.cardName());
            }
        });
        nav.setSelectedIndex(0);
        return new JScrollPane(nav);
    }

    private static JComponent placeholderCard(NavEntry entry) {
        JLabel label = new JLabel(
                entry.title() + " — arrives in Phase " + entry.arrivalPhase(),
                SwingConstants.CENTER);
        label.setEnabled(false);
        JPanel card = new JPanel(new BorderLayout());
        card.add(label, BorderLayout.CENTER);
        return card;
    }

    private static JComponent buildStatusBar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 16, 4));
        bar.add(new JLabel("Session: NEW"));
        bar.add(new JLabel("Events: " + UNKNOWN));
        bar.add(new JLabel("Actions: " + UNKNOWN));

        JPanel south = new JPanel(new BorderLayout());
        south.add(new JSeparator(), BorderLayout.NORTH);
        south.add(bar, BorderLayout.CENTER);
        return south;
    }
}
