package com.holtherndon.bazelviz.ui.nav;

import java.awt.FlowLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.IntFunction;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTable;
import javax.swing.JTree;
import javax.swing.tree.TreePath;

/**
 * The shared cross-view navigation actions: one vocabulary of "take me to this entity elsewhere"
 * commands, offered as a context menu, a button strip, or an inspector region, and dispatched
 * through a single handler.
 *
 * <h2>Why one component</h2>
 *
 * <p>Before this existed, every view that could jump to another grew its own {@code LongConsumer}
 * setter and {@code MainWindow} wired each one by hand — the same five lines, copied per view per
 * destination, with no guarantee two views' "show source event" behaved alike. This class replaces
 * the pattern: a view builds refs for its selected row, this class builds the affordance, and the
 * one handler in {@code MainWindow} decides what each command means. When a destination card moves
 * — the planned Graph/Tree split — the re-point is one switch arm in that handler, not a hunt
 * through the views.
 *
 * <h2>Honest absence</h2>
 *
 * <p>An item is offered only when there is something real behind it: the command must be
 * {@linkplain #EntityActions(Set, Handler) wired}, and one of the refs must be of the kind it acts
 * on. A row whose event carries no parseable label simply offers no label actions — no disabled
 * stubs, no blank menu. {@link #popupFor} can therefore return an empty menu, and callers must not
 * show one.
 *
 * <h2>Threading</h2>
 *
 * <p>EDT-only, like every Swing component. Everything here is pure widget construction over values
 * already in hand; the handler is where navigation — and any I/O it schedules — begins, and
 * implementations must return quickly.
 *
 * <h2>Anticipated adopters</h2>
 *
 * <p>The Timeline's planned inline inspector embeds these actions by calling {@link
 * #buttonStripFor} with the refs of the span under inspection — nothing here needs to change for
 * it, which is the design intent: adopters change, the vocabulary and the wiring point do not.
 */
public final class EntityActions {

  /**
   * The navigation vocabulary. Two members are deliberately ahead of the wiring: {@link
   * #OPEN_IN_TREE} exists for the planned Graph/Tree split (today's Graph card becomes the Tree
   * card and a new Graph card arrives), and {@link #SHOW_EVENTS_FOR_LABEL} awaits an
   * events-by-label read path. Neither is offered anywhere until {@code MainWindow} wires it, which
   * is how a command stays honestly absent instead of present-but-dead.
   */
  public enum Command {
    /** Show the label in the Targets tab, expanded and selected. */
    OPEN_TARGET("Open target"),
    /** Select this exact checksum in the Configurations tab. */
    VIEW_CONFIGURATION("View Configuration"),
    /** Open the main-workspace BUILD file that owns this label. */
    OPEN_BUILD_FILE("Open Build File…"),
    /** Show the entity in the dependency tree view (post-split name of today's Graph card). */
    OPEN_IN_TREE("Open in tree"),
    /** Show the entity in the dependency graph view (today: the Graph card). */
    OPEN_IN_GRAPH("Open in graph"),
    /** Filter the Actions tab to this label. */
    SHOW_ACTIONS_FOR_LABEL("Show actions for this target"),
    /** Show this label's events in the Events tab. */
    SHOW_EVENTS_FOR_LABEL("Show events for this target"),
    /** Select the action in the Actions tab. */
    REVEAL_ACTION("Reveal action"),
    /** Highlight the action on the timeline. */
    SHOW_ON_TIMELINE("Show on timeline"),
    /** Open the entity's source event in the Events tab. */
    SHOW_SOURCE_EVENT("Show source event");

    private final String title;

    Command(String title) {
      this.title = title;
    }

    /** The menu-item and button text. */
    public String title() {
      return title;
    }

    /** Whether this command can act on {@code ref} at all. */
    public boolean appliesTo(EntityRef ref) {
      return switch (this) {
        case VIEW_CONFIGURATION -> ref instanceof EntityRef.ConfigurationChecksum;
        case OPEN_TARGET, OPEN_BUILD_FILE, SHOW_ACTIONS_FOR_LABEL, SHOW_EVENTS_FOR_LABEL ->
            ref instanceof EntityRef.TargetLabel;
        // The widening this switch was written expecting: the tree and
        // the canvas take a target label as well as an action, because
        // a label is a node in its own right in the configured-target
        // graph and the owner of actions in the action graph. Both
        // arms of the handler take both kinds.
        case OPEN_IN_TREE, OPEN_IN_GRAPH ->
            ref instanceof EntityRef.ActionId || ref instanceof EntityRef.TargetLabel;
        case REVEAL_ACTION, SHOW_ON_TIMELINE -> ref instanceof EntityRef.ActionId;
        case SHOW_SOURCE_EVENT -> ref instanceof EntityRef.EventId;
      };
    }
  }

  /** Receives every activated command, on the EDT. */
  @FunctionalInterface
  public interface Handler {
    void navigate(Command command, EntityRef ref);
  }

  private final Set<Command> wired;
  private final Handler handler;

  /**
   * @param wired the commands something real is behind; anything else is never offered
   * @param handler where every activation goes — in the application, the single {@code MainWindow}
   *     switch
   */
  public EntityActions(Set<Command> wired, Handler handler) {
    Objects.requireNonNull(wired, "wired");
    this.wired = wired.isEmpty() ? EnumSet.noneOf(Command.class) : EnumSet.copyOf(wired);
    this.handler = Objects.requireNonNull(handler, "handler");
  }

  /**
   * Whether something real is behind {@code command} in this application.
   *
   * <p>Menus and strips never need to ask: an unwired command is simply not offered, which is the
   * honest form of absence for an affordance built per selection. A <em>fixed</em> toolbar cannot
   * do that — its buttons exist before anything is selected — so the Targets card asks this and
   * disables the button with the reason in its tooltip, which is the honest form of absence for a
   * control that is always on screen. What neither form allows is a control that clicks into
   * silence.
   */
  public boolean isWired(Command command) {
    return wired.contains(Objects.requireNonNull(command, "command"));
  }

  /**
   * The (command, ref) pairs that would be offered for {@code refs}, in vocabulary order. This is
   * the one place the offering is decided; the menu and the button strip both render exactly this
   * list.
   */
  public List<Offer> offersFor(List<EntityRef> refs, Set<Command> omit) {
    Objects.requireNonNull(refs, "refs");
    Objects.requireNonNull(omit, "omit");
    List<Offer> offers = new ArrayList<>();
    for (Command command : Command.values()) {
      if (!wired.contains(command) || omit.contains(command)) {
        continue;
      }
      for (EntityRef ref : refs) {
        if (command.appliesTo(ref)) {
          offers.add(new Offer(command, ref));
          break; // one item per command; the first applicable ref wins
        }
      }
    }
    return offers;
  }

  /** A command and the ref it would act on. */
  public record Offer(Command command, EntityRef ref) {}

  /**
   * A context menu for {@code refs}. May be empty — callers must check {@code getComponentCount()}
   * before showing it, because an empty menu on screen is a blank rectangle claiming there was
   * something to offer.
   *
   * @param omit commands not to offer here even though they are wired — a view omits the command
   *     that would navigate to itself
   */
  public JPopupMenu popupFor(List<EntityRef> refs, Set<Command> omit) {
    JPopupMenu menu = new JPopupMenu();
    for (Offer offer : offersFor(refs, omit)) {
      JMenuItem item = new JMenuItem(offer.command().title());
      item.addActionListener(event -> handler.navigate(offer.command(), offer.ref()));
      menu.add(item);
    }
    return menu;
  }

  /**
   * A horizontal strip of buttons for {@code refs}, for a toolbar or an inspector region. Empty
   * (zero components) when nothing is offered, which renders as nothing — the honest form of "no
   * actions here".
   */
  public JComponent buttonStripFor(List<EntityRef> refs, Set<Command> omit) {
    JPanel strip = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
    strip.setOpaque(false);
    for (Offer offer : offersFor(refs, omit)) {
      JButton button = new JButton(offer.command().title());
      button.addActionListener(event -> handler.navigate(offer.command(), offer.ref()));
      strip.add(button);
    }
    return strip;
  }

  /**
   * Installs a right-click menu on a table's rows.
   *
   * <p>The row under the pointer is selected first — acting on a row the user can see is not
   * selected would apply the action somewhere other than where they clicked. A row whose refs offer
   * nothing shows no menu.
   *
   * @param refsAtRow the refs for a <em>model</em> row index; called on the EDT at popup time, so
   *     it must be a field read over the row already fetched — never a query. A row whose page has
   *     not arrived returns an empty list and gets no menu.
   */
  public void installRowMenu(
      JTable table, IntFunction<List<EntityRef>> refsAtRow, Set<Command> omit) {
    Objects.requireNonNull(table, "table");
    Objects.requireNonNull(refsAtRow, "refsAtRow");
    Set<Command> omitted = omit.isEmpty() ? Set.of() : EnumSet.copyOf(omit);
    table.addMouseListener(
        new MouseAdapter() {
          @Override
          public void mousePressed(MouseEvent event) {
            maybeShow(event);
          }

          @Override
          public void mouseReleased(MouseEvent event) {
            maybeShow(event);
          }

          private void maybeShow(MouseEvent event) {
            if (!event.isPopupTrigger()) {
              return;
            }
            int viewRow = table.rowAtPoint(event.getPoint());
            if (viewRow < 0) {
              return;
            }
            table.setRowSelectionInterval(viewRow, viewRow);
            int modelRow = table.convertRowIndexToModel(viewRow);
            JPopupMenu menu = popupFor(refsAtRow.apply(modelRow), omitted);
            if (menu.getComponentCount() == 0) {
              return;
            }
            menu.show(table, event.getX(), event.getY());
          }
        });
  }

  /**
   * Installs the same selection-first context menu on a tree. The resolver receives the
   * already-materialized path and must only inspect its node value; it runs on the EDT and may not
   * query or touch the filesystem.
   */
  public void installTreeMenu(
      JTree tree, Function<TreePath, List<EntityRef>> refsAtPath, Set<Command> omit) {
    Set<Command> omitted = omit.isEmpty() ? Set.of() : EnumSet.copyOf(omit);
    installTreeMenu(tree, refsAtPath, path -> omitted);
  }

  /** Tree menu variant whose omissions can differ for package and leaf rows. */
  public void installTreeMenu(
      JTree tree,
      Function<TreePath, List<EntityRef>> refsAtPath,
      Function<TreePath, Set<Command>> omissionsAtPath) {
    Objects.requireNonNull(tree, "tree");
    Objects.requireNonNull(refsAtPath, "refsAtPath");
    Objects.requireNonNull(omissionsAtPath, "omissionsAtPath");
    tree.addMouseListener(
        new MouseAdapter() {
          @Override
          public void mousePressed(MouseEvent event) {
            maybeShow(event);
          }

          @Override
          public void mouseReleased(MouseEvent event) {
            maybeShow(event);
          }

          private void maybeShow(MouseEvent event) {
            if (!event.isPopupTrigger()) {
              return;
            }
            TreePath path = tree.getPathForLocation(event.getX(), event.getY());
            if (path == null) {
              return;
            }
            tree.setSelectionPath(path);
            Set<Command> omitted = omissionsAtPath.apply(path);
            JPopupMenu menu = popupFor(refsAtPath.apply(path), omitted);
            if (menu.getComponentCount() > 0) {
              menu.show(tree, event.getX(), event.getY());
            }
          }
        });
  }

  /**
   * Dispatches one command directly, for a bespoke affordance that migrated onto the facility — the
   * Actions tab's Dependencies and On-timeline buttons keep their place in the toolbar and act
   * through the same handler as every menu item.
   *
   * @throws IllegalArgumentException when the command is not wired; a control routed at an unwired
   *     command is a programming error and must fail loudly, not click into silence
   */
  public void navigate(Command command, EntityRef ref) {
    Objects.requireNonNull(command, "command");
    Objects.requireNonNull(ref, "ref");
    if (!wired.contains(command)) {
      throw new IllegalArgumentException(command + " is not wired");
    }
    handler.navigate(command, ref);
  }

  /** Visible for tests: the wired commands. */
  public Set<Command> wiredForTest() {
    return wired.isEmpty() ? Set.of() : EnumSet.copyOf(wired);
  }
}
