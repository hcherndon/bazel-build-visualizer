package com.holtherndon.bazelviz.ui.inspect;

import com.holtherndon.bazelviz.ui.nav.EntityActions;
import com.holtherndon.bazelviz.ui.nav.EntityRef;
import com.holtherndon.bazelviz.ui.theme.PlainText;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Insets;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;

/**
 * The one header every inspector wears: a title line, a subtitle line, and an
 * overflow button that opens the entity's cross-view actions.
 *
 * <h2>Why the actions are a menu and not a strip</h2>
 *
 * <p>Every inspector before this put {@link EntityActions#buttonStripFor a row
 * of buttons} at the header's right edge. A row of buttons has a preferred
 * width that grows with the number of offers and never shrinks, and every one
 * of these headers lives in a pane the user can drag narrow. {@code
 * BorderLayout} hands its {@code EAST} child that full preferred width whatever
 * the container's own width is, so at a narrow split the strip walked left off
 * its own panel: the toolbar was, in the user's words, "entirely hidden unless
 * the pane is pulled all the way to the side".
 *
 * <p>One button of fixed width cannot do that. The offers move into the menu it
 * opens — a menu is a window, so it is bounded by the screen rather than by the
 * pane — and the edge of the header stops competing with the title for space.
 * What shrinks at a narrow width is the <em>text</em>: the two labels ellipsize
 * and keep the whole string in a tooltip, so nothing is lost, only folded.
 *
 * <h2>Honest absence</h2>
 *
 * <p>The button exists only when {@link EntityActions} would actually offer
 * something for the refs in hand: no facility installed, no refs, or no wired
 * command that applies means no button at all, rather than a button that opens
 * an empty rectangle. This mirrors {@link EntityActions#popupFor}'s own
 * contract, which is explicit that an empty menu must never be shown.
 *
 * <h2>Threading</h2>
 *
 * <p>EDT only, like every Swing component. Everything here is widget
 * construction over values already in hand; the navigation handler is where
 * work begins, and it is the host's job to keep that quick.
 */
public final class InspectorHeader extends JPanel {

    private static final long serialVersionUID = 1L;

    /**
     * The overflow button's face. A single ellipsis character, so the button
     * is as narrow as a button gets — its whole job is to not grow.
     */
    static final String OVERFLOW_TEXT = "…";

    /** Gap between the text block and the fixed edge. */
    private static final int EDGE_GAP = 8;

    /**
     * The width the text block is guaranteed even at the narrowest: enough for
     * an ellipsis and a character or two of what was elided. It exists so the
     * header's minimum size is a small number a split pane can honour rather
     * than the natural width of whatever label the build happened to produce.
     */
    private static final int TEXT_FLOOR = 32;

    private final JLabel titleLabel = new JLabel(" ");
    private final JLabel subtitleLabel = new JLabel(" ");
    private final JButton overflow = new JButton(OVERFLOW_TEXT);
    /** The fixed right edge: the overflow button, then any host-specific control. */
    private final JPanel edge = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));

    private EntityActions entityActions;
    private Set<EntityActions.Command> omit = Set.of();
    private List<EntityRef> refs = List.of();

    public InspectorHeader() {
        super(new BorderLayout(EDGE_GAP, 0));
        setOpaque(false);

        PlainText.disableHtml(titleLabel);
        PlainText.disableHtml(subtitleLabel);
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD));
        subtitleLabel.setEnabled(false);

        // GridLayout inside BorderLayout's CENTER, not BoxLayout: both of
        // those hand a child exactly the width available, which is the
        // condition a JLabel needs to ellipsize. BoxLayout would floor each
        // label at its own preferred width and let it overhang instead.
        JPanel titles = new JPanel(new GridLayout(2, 1, 0, 0));
        titles.setOpaque(false);
        titles.add(titleLabel);
        titles.add(subtitleLabel);

        overflow.setToolTipText("More actions for this entity");
        overflow.getAccessibleContext().setAccessibleName("More actions");
        overflow.setMargin(new Insets(2, 6, 2, 6));
        overflow.setVisible(false);
        overflow.addActionListener(event -> openMenu());

        edge.setOpaque(false);
        edge.add(overflow);

        add(titles, BorderLayout.CENTER);
        add(edge, BorderLayout.EAST);
    }

    /**
     * Adds a control of the host's own to the fixed edge, right of the
     * overflow button — the Timeline's Close, the shared panel's legacy
     * source-event button. It shares the edge's guarantee: the text shrinks
     * around it, it does not shrink.
     */
    public void addTrailing(JComponent control) {
        edge.add(Objects.requireNonNull(control, "control"));
    }

    /**
     * Adopts the shared cross-view actions. Until this runs the header shows
     * no overflow button at all, which is how a host that has not adopted the
     * facility renders exactly as it did.
     *
     * @param omit commands never offered here — a host passes the one that
     *     would navigate to what is already on screen
     */
    public void installEntityActions(EntityActions actions, Set<EntityActions.Command> omit) {
        this.entityActions = Objects.requireNonNull(actions, "actions");
        this.omit = Set.copyOf(Objects.requireNonNull(omit, "omit"));
        refreshOverflow();
    }

    /** Shows one entity. Must be called on the EDT. */
    public void show(String title, Optional<String> subtitle, List<EntityRef> refs) {
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(subtitle, "subtitle");
        this.refs = List.copyOf(Objects.requireNonNull(refs, "refs"));
        setLine(titleLabel, title);
        setLine(subtitleLabel, subtitle.orElse(""));
        refreshOverflow();
    }

    /** Shows nothing: blank lines, no actions. */
    public void clear() {
        show("", Optional.empty(), List.of());
    }

    /**
     * Whether the overflow menu currently offers {@code command}. Hosts ask so
     * a bespoke button of their own can yield rather than double an offer the
     * menu already carries.
     */
    public boolean offers(EntityActions.Command command) {
        Objects.requireNonNull(command, "command");
        return offers().stream().anyMatch(offer -> offer.command() == command);
    }

    /**
     * A width a split pane can actually reach.
     *
     * <p>The default would be the natural width of the title, which is the
     * width of whatever label the build produced — and an ancestor honouring
     * it is an ancestor that refuses to narrow. The edge is what must survive,
     * so the floor is the edge plus room for an ellipsis.
     */
    @Override
    public Dimension getMinimumSize() {
        if (isMinimumSizeSet()) {
            return super.getMinimumSize();
        }
        Insets insets = getInsets();
        return new Dimension(
                insets.left + insets.right + edge.getPreferredSize().width
                        + EDGE_GAP + TEXT_FLOOR,
                getPreferredSize().height);
    }

    /** Visible for tests: the title exactly as set. */
    public String titleForTest() {
        return titleLabel.getText();
    }

    /** Visible for tests: the subtitle exactly as set. */
    public String subtitleForTest() {
        return subtitleLabel.getText();
    }

    /** Visible for tests: the full-text tooltip the title carries when elided. */
    public String titleTooltipForTest() {
        return titleLabel.getToolTipText();
    }

    /** Visible for tests: the full-text tooltip the subtitle carries when elided. */
    public String subtitleTooltipForTest() {
        return subtitleLabel.getToolTipText();
    }

    /** Visible for tests: the overflow button, invisible when nothing is offered. */
    public JButton overflowForTest() {
        return overflow;
    }

    /** Visible for tests: the title line, for measuring what a narrow width did to it. */
    JLabel titleLabelForTest() {
        return titleLabel;
    }

    /** Visible for tests: the menu the overflow button would open. */
    public JPopupMenu overflowMenuForTest() {
        return menu();
    }

    private void setLine(JLabel label, String text) {
        // A blank line keeps its height, so the header does not change shape
        // between an entity with a subtitle and one without.
        label.setText(text.isEmpty() ? " " : text);
        label.setToolTipText(text.isEmpty() ? null : PlainText.tooltip(text));
    }

    private List<EntityActions.Offer> offers() {
        if (entityActions == null || refs.isEmpty()) {
            return List.of();
        }
        return entityActions.offersFor(refs, omit);
    }

    private JPopupMenu menu() {
        return entityActions == null
                ? new JPopupMenu() : entityActions.popupFor(refs, omit);
    }

    private void openMenu() {
        JPopupMenu menu = menu();
        if (menu.getComponentCount() == 0) {
            // Unreachable while the button appears only with offers, and kept
            // because popupFor's contract says an empty menu is never shown.
            return;
        }
        menu.show(overflow, 0, overflow.getHeight());
    }

    private void refreshOverflow() {
        overflow.setVisible(!offers().isEmpty());
        edge.revalidate();
        edge.repaint();
        revalidate();
        repaint();
    }
}
