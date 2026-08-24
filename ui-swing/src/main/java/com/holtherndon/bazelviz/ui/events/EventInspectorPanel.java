package com.holtherndon.bazelviz.ui.events;

import com.holtherndon.bazelviz.storage.events.RawLocation;
import com.holtherndon.bazelviz.ui.nav.EntityActions;
import com.holtherndon.bazelviz.ui.nav.EntityRef;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.SwingConstants;

/**
 * The raw protobuf inspector (plan 17.11): the selected event's stored columns,
 * its decoded text, and a hex dump of the bytes the journal holds.
 *
 * <p>Rendering only. {@link EventInspectorModel} does the reading, off the EDT,
 * for the selected event alone. When a record did not decode, the decode pane
 * says so and the hex pane still shows every byte — the bytes are the evidence,
 * and a build that cannot read them today is not a reason to hide them.
 *
 * <p>EDT only.
 */
public final class EventInspectorPanel extends JPanel {

    private static final long serialVersionUID = 1L;

    private final JLabel headline = new JLabel(" ");
    private final JLabel typeValue = value();
    private final JLabel decodeValue = value();
    private final JLabel identityValue = value();
    private final JLabel locationValue = value();
    private final JLabel sizeValue = value();
    private final JLabel timeValue = value();
    private final JTextArea notices = monospaced();
    private final JTextArea decoded = monospaced();
    private final JTextArea hex = monospaced();
    private final JScrollPane noticesScroll;

    /**
     * Where the shared navigation actions for the inspected event's target
     * label appear, once {@link #installEntityActions} has run. Empty — and
     * invisible in effect — for an event that names no parseable label:
     * honestly absent, not a strip of dead buttons.
     */
    private final JPanel actionsRegion = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    private EntityActions entityActions;
    private EventInspection shown = EventInspection.none();

    public EventInspectorPanel() {
        super(new BorderLayout());
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        JPanel fields = new JPanel(new GridLayout(0, 6, 12, 2));
        fields.add(caption("Type"));
        fields.add(typeValue);
        fields.add(caption("Decode"));
        fields.add(decodeValue);
        fields.add(caption("Event time"));
        fields.add(timeValue);
        fields.add(caption("Event id"));
        fields.add(identityValue);
        fields.add(caption("Raw location"));
        fields.add(locationValue);
        fields.add(caption("Raw size"));
        fields.add(sizeValue);

        JPanel header = new JPanel(new BorderLayout(0, 6));
        header.add(headline, BorderLayout.NORTH);
        header.add(fields, BorderLayout.CENTER);
        actionsRegion.setOpaque(false);
        header.add(actionsRegion, BorderLayout.SOUTH);

        noticesScroll = new JScrollPane(notices);
        noticesScroll.setVisible(false);
        noticesScroll.setBorder(BorderFactory.createTitledBorder("Notices"));

        JTabbedPane panes = new JTabbedPane();
        panes.addTab("Decoded", new JScrollPane(decoded));
        panes.addTab("Raw bytes (hex)", new JScrollPane(hex));

        JSplitPane body = new JSplitPane(JSplitPane.VERTICAL_SPLIT, noticesScroll, panes);
        body.setResizeWeight(0);
        body.setDividerSize(4);

        add(header, BorderLayout.NORTH);
        add(body, BorderLayout.CENTER);
        show(EventInspection.none());
    }

    /**
     * Installs the shared cross-view actions. Until this runs the panel shows
     * no navigation affordances at all, which is how the tests and any host
     * without the facility keep the panel's old behaviour exactly.
     */
    public void installEntityActions(EntityActions actions) {
        this.entityActions = Objects.requireNonNull(actions, "actions");
        rebuildActions();
    }

    /** Renders one inspector state. */
    public void show(EventInspection inspection) {
        Objects.requireNonNull(inspection, "inspection");
        shown = inspection;
        rebuildActions();
        switch (inspection.state()) {
            case NONE -> {
                headline.setText("Select an event to inspect its raw bytes.");
                clearFields();
                decoded.setText("");
                hex.setText("");
                setNotices(List.of());
            }
            case LOADING -> {
                headline.setText("Reading event " + inspection.eventId().getAsLong()
                        + " from the journal…");
                clearFields();
                decoded.setText("");
                hex.setText("");
                setNotices(List.of());
            }
            case FAILED -> {
                headline.setText("Event " + inspection.eventId().getAsLong()
                        + " could not be inspected.");
                clearFields();
                decoded.setText(inspection.loadFailure().orElse(EventValueFormat.UNKNOWN));
                hex.setText("");
                setNotices(List.of());
            }
            case LOADED -> showLoaded(inspection);
        }
        decoded.setCaretPosition(0);
        hex.setCaretPosition(0);
    }

    private void showLoaded(EventInspection inspection) {
        EventRow row = inspection.row().orElseThrow();
        RawPayloadRenderer.Rendered rendered = inspection.rendered().orElseThrow();
        headline.setText("Event row " + row.id() + ", sequence " + row.sequence()
                + ", from " + inspection.payload().orElseThrow().sourceKind());
        typeValue.setText(EventTableColumns.eventType(row));
        decodeValue.setText(EventTableColumns.decode(row)
                + (row.hasUnknownFields() ? " (carried unknown fields)" : ""));
        identityValue.setText(EventTableColumns.identity(row));
        RawLocation location = row.rawLocation();
        locationValue.setText("segment " + location.segment() + ", offset " + location.offset());
        sizeValue.setText(EventValueFormat.count(row.rawLength()) + " bytes ("
                + EventValueFormat.bytes(row.rawLength()) + ")");
        timeValue.setText(EventValueFormat.timestamp(row.eventMicros()));

        StringBuilder text = new StringBuilder();
        rendered.decodeFailure().ifPresent(failure ->
                text.append("Decode failed: ").append(failure).append("\n\n"));
        text.append(rendered.text());
        decoded.setText(text.toString());
        hex.setText(inspection.hexDump().orElse(""));
        setNotices(rendered.notices());
    }

    /**
     * Rebuilds the label-actions strip for what is shown right now: the
     * shared component's buttons for the inspected event's target label, or
     * nothing when there is no facility, no loaded event, or no label.
     */
    private void rebuildActions() {
        actionsRegion.removeAll();
        if (entityActions != null && shown.state() == EventInspection.State.LOADED) {
            shown.targetLabel().ifPresent(label -> actionsRegion.add(
                    entityActions.buttonStripFor(
                            List.of(new EntityRef.TargetLabel(label)), Set.of())));
        }
        actionsRegion.revalidate();
        actionsRegion.repaint();
    }

    /** Visible for testing: the strip the shared actions render into. */
    JPanel actionsRegionForTest() {
        return actionsRegion;
    }

    private void setNotices(List<String> messages) {
        if (messages.isEmpty()) {
            noticesScroll.setVisible(false);
            notices.setText("");
            return;
        }
        notices.setText(String.join("\n", messages));
        notices.setCaretPosition(0);
        noticesScroll.setVisible(true);
    }

    private void clearFields() {
        typeValue.setText(EventValueFormat.UNKNOWN);
        decodeValue.setText(EventValueFormat.UNKNOWN);
        identityValue.setText(EventValueFormat.UNKNOWN);
        locationValue.setText(EventValueFormat.UNKNOWN);
        sizeValue.setText(EventValueFormat.UNKNOWN);
        timeValue.setText(EventValueFormat.UNKNOWN);
    }

    private static JLabel caption(String text) {
        JLabel label = new JLabel(text, SwingConstants.RIGHT);
        label.setEnabled(false);
        return label;
    }

    private static JLabel value() {
        return new JLabel(EventValueFormat.UNKNOWN);
    }

    private static JTextArea monospaced() {
        JTextArea area = new JTextArea();
        area.setEditable(false);
        area.setLineWrap(false);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        return area;
    }
}
