package com.holtherndon.bazelviz.ui.inspect;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.Objects;
import java.util.function.LongConsumer;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingConstants;
import javax.swing.UIManager;

/**
 * The shared inspector: one pane that shows whatever entity is selected, in
 * whichever view (plan 17.11).
 *
 * <h2>Unknown looks different from blank</h2>
 *
 * <p>A field with no value renders as {@code unknown} in the theme's disabled
 * colour, followed by the reason when there is one — "not reported by this
 * Bazel version", "no spawn ran". A field whose value is genuinely an empty
 * string renders as an empty string. Those are different facts and a user
 * chasing a missing number needs to be able to tell which one they are looking
 * at, so they never render alike (plan 11.4).
 *
 * <h2>Every entity can name its source event</h2>
 *
 * <p>When the inspection carries one, a button offers to open it in the Events
 * view. That is the whole of "event-to-domain provenance is inspectable": from
 * any row in any table, one click to the bytes Bazel actually sent.
 */
public final class InspectorPanel extends JPanel {

    private static final long serialVersionUID = 1L;

    /** What a field with no value says. Deliberately a word, not a dash. */
    static final String UNKNOWN = "unknown";

    private final JLabel titleLabel = new JLabel(" ");
    private final JLabel subtitleLabel = new JLabel(" ");
    private final JButton sourceButton = new JButton("Show source event");
    private final JPanel body = new JPanel();
    private final JLabel emptyLabel =
            new JLabel("Select a row to inspect it.", SwingConstants.CENTER);

    private LongConsumer sourceEventHandler = eventId -> { };
    private Inspection current = Inspection.NONE;

    public InspectorPanel() {
        super(new BorderLayout());

        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD));
        subtitleLabel.setEnabled(false);
        sourceButton.setVisible(false);
        sourceButton.addActionListener(event ->
                current.sourceEventId().ifPresent(id -> sourceEventHandler.accept(id)));

        JPanel titles = new JPanel();
        titles.setLayout(new BoxLayout(titles, BoxLayout.Y_AXIS));
        titleLabel.setAlignmentX(LEFT_ALIGNMENT);
        subtitleLabel.setAlignmentX(LEFT_ALIGNMENT);
        titles.add(titleLabel);
        titles.add(subtitleLabel);

        JPanel header = new JPanel(new BorderLayout(8, 0));
        header.setBorder(BorderFactory.createEmptyBorder(8, 8, 4, 8));
        header.add(titles, BorderLayout.CENTER);
        header.add(sourceButton, BorderLayout.EAST);

        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setBorder(BorderFactory.createEmptyBorder(0, 8, 8, 8));

        JScrollPane scroll = new JScrollPane(body);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        scroll.setMinimumSize(new Dimension(280, 120));

        emptyLabel.setEnabled(false);

        add(header, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
        show(Inspection.NONE);
    }

    /** Called on the EDT with the event id when the user asks to see the source. */
    public void onShowSourceEvent(LongConsumer handler) {
        this.sourceEventHandler = Objects.requireNonNull(handler, "handler");
    }

    /** Replaces what is displayed. Must be called on the EDT. */
    public void show(Inspection inspection) {
        current = Objects.requireNonNull(inspection, "inspection");
        body.removeAll();

        if (inspection.isEmpty()) {
            titleLabel.setText(" ");
            subtitleLabel.setText(" ");
            sourceButton.setVisible(false);
            JPanel empty = new JPanel(new BorderLayout());
            empty.add(emptyLabel, BorderLayout.CENTER);
            body.add(empty);
        } else {
            titleLabel.setText(inspection.title());
            titleLabel.setToolTipText(inspection.title());
            subtitleLabel.setText(inspection.subtitle().orElse(" "));
            sourceButton.setVisible(inspection.sourceEventId().isPresent());
            inspection.sourceEventId().ifPresent(id ->
                    sourceButton.setToolTipText("Open event " + id + " in the Events view"));
            for (Inspection.Section section : inspection.sections()) {
                body.add(sectionPanel(section));
                body.add(Box.createVerticalStrut(8));
            }
        }
        body.revalidate();
        body.repaint();
    }

    /** Visible for testing: what is on screen right now. */
    public Inspection displayed() {
        return current;
    }

    private static JPanel sectionPanel(Inspection.Section section) {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createTitledBorder(section.heading()));
        panel.setAlignmentX(LEFT_ALIGNMENT);

        GridBagConstraints name = new GridBagConstraints();
        name.gridx = 0;
        name.anchor = GridBagConstraints.NORTHWEST;
        name.insets = new Insets(1, 4, 1, 8);
        GridBagConstraints value = new GridBagConstraints();
        value.gridx = 1;
        value.weightx = 1;
        value.fill = GridBagConstraints.HORIZONTAL;
        value.anchor = GridBagConstraints.NORTHWEST;
        value.insets = new Insets(1, 0, 1, 4);

        int row = 0;
        for (Inspection.Field field : section.fields()) {
            name.gridy = row;
            value.gridy = row;
            JLabel nameLabel = new JLabel(field.name());
            nameLabel.setEnabled(false);
            panel.add(nameLabel, name);
            panel.add(valueLabel(field), value);
            row++;
        }
        return panel;
    }

    private static Component valueLabel(Inspection.Field field) {
        if (field.isKnown()) {
            JLabel label = new JLabel(field.value().orElseThrow());
            label.setToolTipText(field.value().orElseThrow());
            return label;
        }
        // The two halves are one label rather than two components so they wrap
        // and elide together: an explanation that scrolled out of view beside a
        // word saying "unknown" would be no explanation at all.
        String note = field.unknownNote().map(why -> " — " + why).orElse("");
        JLabel label = new JLabel(UNKNOWN + note);
        label.setFont(label.getFont().deriveFont(Font.ITALIC));
        Color disabled = UIManager.getColor("Label.disabledForeground");
        if (disabled != null) {
            label.setForeground(disabled);
        }
        label.setToolTipText(field.unknownNote()
                .orElse("This value was not reported, and is not zero."));
        return label;
    }
}
