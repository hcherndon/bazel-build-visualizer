package com.holtherndon.bazelviz.ui.timeline;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.core.domain.ActionOutcome;
import com.holtherndon.bazelviz.storage.entities.ActionRow;
import com.holtherndon.bazelviz.storage.entities.ActionRow.Execution;
import com.holtherndon.bazelviz.ui.inspect.InspectorHeader;
import com.holtherndon.bazelviz.ui.nav.EntityActions;
import com.holtherndon.bazelviz.ui.nav.EntityRef;
import java.awt.AWTKeyStroke;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.awt.KeyboardFocusManager;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.Action;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.text.BadLocationException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Timeline's side inspector, and the shared vocabulary it dispatches.
 *
 * <p>Before this existed, clicking a span selected a row in the Actions tab without switching to it
 * — visibly, nothing happened. Now a click shows the segment's details in place, and the details'
 * actions go through the same {@link EntityActions} facility and the same single handler as every
 * other view's, which is what these tests pin down.
 */
final class TimelineInspectorTest {

  private static final int WIDTH = 1_000;
  private static final int HEIGHT = 400;

  @BeforeAll
  static void requireHeadless() {
    assertThat(GraphicsEnvironment.isHeadless()).isTrue();
  }

  /** Records every navigation the facility dispatches. */
  private static final class Recorder implements EntityActions.Handler {
    final List<EntityActions.Command> commands = new ArrayList<>();
    final List<EntityRef> refs = new ArrayList<>();

    @Override
    public void navigate(EntityActions.Command command, EntityRef ref) {
      commands.add(command);
      refs.add(ref);
    }
  }

  private static TimelineModel aModel() {
    return aModel(TimelineModel.LiveBand.EMPTY);
  }

  private static TimelineModel aModel(TimelineModel.LiveBand liveBand) {
    TimelineLodIndex index =
        TimelineLodIndex.build(
            new SpanSource() {
              @Override
              public long spanCount() {
                return 1;
              }

              @Override
              public void forEachSpan(SpanConsumer consumer) {
                consumer.accept(0, 1_000_000, 0, 0, SpanSource.BYTES_UNKNOWN);
              }
            },
            0,
            10_000_000);
    return new TimelineModel(
        index,
        List.of(new TimelineModel.Lane("All actions", "", 1, 1_000_000, 0, 1_000_000)),
        List.of(),
        Map.of(),
        0,
        0,
        0,
        liveBand);
  }

  /** The buttons a user could actually press, invisible ones excluded. */
  private static List<JButton> buttonsIn(Container container) {
    List<JButton> found = new ArrayList<>();
    for (Component child : container.getComponents()) {
      if (child instanceof JButton button && button.isVisible()) {
        found.add(button);
      }
      if (child instanceof Container nested) {
        found.addAll(buttonsIn(nested));
      }
    }
    return found;
  }

  private static List<JMenuItem> itemsIn(JPopupMenu menu) {
    List<JMenuItem> found = new ArrayList<>();
    for (int i = 0; i < menu.getComponentCount(); i++) {
      found.add((JMenuItem) menu.getComponent(i));
    }
    return found;
  }

  private static void invokeKey(JComponent component, KeyStroke stroke) {
    Object key = actionKey(component, stroke);
    Action action = component.getActionMap().get(key);
    assertThat(action).as("action installed for %s", stroke).isNotNull();
    action.actionPerformed(
        new ActionEvent(component, ActionEvent.ACTION_PERFORMED, String.valueOf(key)));
  }

  private static Object actionKey(JComponent component, KeyStroke stroke) {
    Object key = component.getInputMap(JComponent.WHEN_FOCUSED).get(stroke);
    assertThat(key).as("action bound to %s", stroke).isNotNull();
    return key;
  }

  /** Runs enough headless layout passes for width-sensitive text to settle. */
  private static void layoutTree(Container root, int width, int height) {
    root.setSize(width, height);
    for (int pass = 0; pass < 4; pass++) {
      invalidateTree(root);
      layoutChildren(root);
    }
  }

  private static void invalidateTree(Container root) {
    root.invalidate();
    for (Component child : root.getComponents()) {
      if (child instanceof Container nested) {
        invalidateTree(nested);
      }
    }
  }

  private static void layoutChildren(Container root) {
    root.doLayout();
    for (Component child : root.getComponents()) {
      if (child instanceof Container nested) {
        layoutChildren(nested);
      }
    }
  }

  @Test
  @DisplayName("clicking a span shows the inspector and reports the selection")
  void clickShowsTheInspector() {
    TimelineView view = new TimelineView();
    view.canvasForTest().setSize(WIDTH, HEIGHT);
    view.setModel(aModel());
    SpanWindow.Builder window = SpanWindow.builder(0, 10_000_000);
    window.add(0, 5_000_000, 0, 42, "");
    view.setWindow(window.build());

    List<Long> selected = new ArrayList<>();
    List<Long> picked = new ArrayList<>();
    view.onSelection(selected::add);
    view.onActionPicked(picked::add);

    assertThat(view.inspectorVisibleForTest()).isFalse();
    // x = 100 is well inside the span (0..5s of a 10s wall over 1000 px);
    // y = 1 is the top sub-row of lane 0, and there is no live band.
    view.clickAt(100, 1);

    assertThat(view.inspectorVisibleForTest()).isTrue();
    assertThat(view.inspectorTitleForTest()).isEqualTo("Action 42");
    assertThat(selected).containsExactly(42L);
    assertThat(picked).containsExactly(42L);
    assertThat(view.viewport().orElseThrow().selectedNode()).hasValue(42);
  }

  @Test
  @DisplayName("the timeline exposes its state and exact spans to keyboard users")
  void keyboardNavigationSelectsVisibleSpans() {
    TimelineView view = new TimelineView();
    JComponent canvas = view.canvasForTest();
    canvas.setSize(WIDTH, HEIGHT);
    view.setModel(aModel());
    SpanWindow.Builder window = SpanWindow.builder(0, 10_000_000);
    window.add(0, 2_000_000, 0, 42, "");
    window.add(3_000_000, 5_000_000, 0, 43, "");
    view.setWindow(window.build());

    assertThat(canvas.isFocusable()).isTrue();
    assertThat(canvas.getBorder()).isNotNull();
    assertThat(canvas.getFocusTraversalKeys(KeyboardFocusManager.FORWARD_TRAVERSAL_KEYS))
        .contains(AWTKeyStroke.getAWTKeyStroke(KeyEvent.VK_TAB, 0));
    assertThat(canvas.getAccessibleContext().getAccessibleName())
        .isEqualTo("Build execution timeline");
    assertThat(canvas.getAccessibleContext().getAccessibleDescription())
        .contains("Showing 2 actions")
        .contains("Left and Right")
        .contains("plus or minus");

    invokeKey(canvas, KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0));
    assertThat(view.viewport().orElseThrow().selectedNode()).hasValue(42);
    invokeKey(canvas, KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0));
    assertThat(view.viewport().orElseThrow().selectedNode()).hasValue(43);
    assertThat(canvas.getAccessibleContext().getAccessibleDescription())
        .contains("Selected action 43");

    double beforeZoom = view.viewport().orElseThrow().transform().pixelsPerMicro();
    String beforeZoomDescription = canvas.getAccessibleContext().getAccessibleDescription();
    invokeKey(canvas, KeyStroke.getKeyStroke(KeyEvent.VK_EQUALS, KeyEvent.SHIFT_DOWN_MASK));
    assertThat(view.viewport().orElseThrow().transform().pixelsPerMicro())
        .isGreaterThan(beforeZoom);
    String zoomedDescription = canvas.getAccessibleContext().getAccessibleDescription();
    assertThat(zoomedDescription)
        .isNotEqualTo(beforeZoomDescription)
        .contains("Visible time range 1.000 s to 9.000 s");

    invokeKey(canvas, KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, KeyEvent.SHIFT_DOWN_MASK));
    String pannedDescription = canvas.getAccessibleContext().getAccessibleDescription();
    assertThat(pannedDescription).isNotEqualTo(zoomedDescription);

    invokeKey(canvas, KeyStroke.getKeyStroke(KeyEvent.VK_0, 0));
    assertThat(canvas.getAccessibleContext().getAccessibleDescription())
        .contains("Visible time range 0.000 s to 10.000 s");
  }

  @Test
  @DisplayName("keyboard users can apply and clear the visible time range filter")
  void keyboardSelectsAndClearsVisibleRange() {
    TimelineView view = new TimelineView();
    JComponent canvas = view.canvasForTest();
    canvas.setSize(WIDTH, HEIGHT);
    view.setModel(aModel());
    view.setWindow(SpanWindow.builder(0, 10_000_000).build());
    long[] visible = view.visibleRange().orElseThrow();
    AtomicReference<OptionalLong> reportedFrom = new AtomicReference<>(OptionalLong.empty());
    AtomicReference<OptionalLong> reportedTo = new AtomicReference<>(OptionalLong.empty());
    view.onRangeChanged(
        (from, to) -> {
          reportedFrom.set(from);
          reportedTo.set(to);
        });

    invokeKey(canvas, KeyStroke.getKeyStroke(KeyEvent.VK_R, 0));

    assertThat(view.viewport().orElseThrow().rangeFromMicros()).hasValue(visible[0]);
    assertThat(view.viewport().orElseThrow().rangeToMicros()).hasValue(visible[1]);
    assertThat(reportedFrom.get()).hasValue(visible[0]);
    assertThat(reportedTo.get()).hasValue(visible[1]);
    assertThat(canvas.getAccessibleContext().getAccessibleDescription())
        .contains("10.000 s selected")
        .contains("Escape to clear that range");

    invokeKey(canvas, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0));

    assertThat(view.viewport().orElseThrow().hasRange()).isFalse();
    assertThat(reportedFrom.get()).isEmpty();
    assertThat(reportedTo.get()).isEmpty();
  }

  @Test
  @DisplayName("keyboard users can cycle the visible in-flight targets and show shortcut help")
  void keyboardCyclesInFlightTargets() {
    TimelineModel.LiveBand band =
        new TimelineModel.LiveBand(
            true,
            List.of(
                new TimelineModel.LiveBand.InFlight("//pkg:a", 1_000_000),
                new TimelineModel.LiveBand.InFlight("//pkg:b", 2_000_000)),
            2,
            0);
    TimelineView view = new TimelineView();
    JComponent canvas = view.canvasForTest();
    canvas.setSize(WIDTH, HEIGHT);
    view.setClockForTest(() -> 10_000_000);
    view.setModel(aModel(band));
    view.setWindow(SpanWindow.builder(0, 10_000_000).build());

    try {
      invokeKey(canvas, KeyStroke.getKeyStroke(KeyEvent.VK_I, 0));
      assertThat(view.selectedInFlightTargetForTest()).contains("//pkg:a");
      assertThat(view.inspectorTitleForTest()).isEqualTo("//pkg:a");

      invokeKey(canvas, KeyStroke.getKeyStroke(KeyEvent.VK_I, 0));
      assertThat(view.selectedInFlightTargetForTest()).contains("//pkg:b");
      assertThat(canvas.getAccessibleContext().getAccessibleDescription())
          .contains("Selected in-flight target //pkg:b")
          .contains("I and Shift+I")
          .contains("F1");

      invokeKey(canvas, KeyStroke.getKeyStroke(KeyEvent.VK_I, KeyEvent.SHIFT_DOWN_MASK));
      assertThat(view.selectedInFlightTargetForTest()).contains("//pkg:a");

      List<Object> keyboardActions =
          List.of(
              actionKey(canvas, KeyStroke.getKeyStroke(KeyEvent.VK_I, 0)),
              actionKey(canvas, KeyStroke.getKeyStroke(KeyEvent.VK_I, KeyEvent.SHIFT_DOWN_MASK)),
              actionKey(canvas, KeyStroke.getKeyStroke(KeyEvent.VK_R, 0)),
              actionKey(canvas, KeyStroke.getKeyStroke(KeyEvent.VK_F1, 0)),
              actionKey(
                  canvas, KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, KeyEvent.SHIFT_DOWN_MASK)));
      assertThat(keyboardActions).doesNotHaveDuplicates();
    } finally {
      view.showEmpty("done");
    }
  }

  @Test
  @DisplayName("selecting an in-flight target replaces a prior action selection")
  void inFlightSelectionClearsSelectedAction() {
    TimelineModel.LiveBand band =
        new TimelineModel.LiveBand(
            true, List.of(new TimelineModel.LiveBand.InFlight("//pkg:live", 1_000_000)), 1, 0);
    TimelineView view = new TimelineView();
    JComponent canvas = view.canvasForTest();
    canvas.setSize(WIDTH, HEIGHT);
    view.setClockForTest(() -> 10_000_000);
    view.setModel(aModel(band));
    SpanWindow.Builder window = SpanWindow.builder(0, 10_000_000);
    window.add(0, 5_000_000, 0, 42, "");
    view.setWindow(window.build());

    try {
      invokeKey(canvas, KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0));
      assertThat(view.viewport().orElseThrow().selectedNode()).hasValue(42);

      invokeKey(canvas, KeyStroke.getKeyStroke(KeyEvent.VK_I, 0));

      assertThat(view.selectedInFlightTargetForTest()).contains("//pkg:live");
      assertThat(view.viewport().orElseThrow().selectedNode()).isEmpty();
      assertThat(canvas.getAccessibleContext().getAccessibleDescription())
          .contains("Selected in-flight target //pkg:live")
          .doesNotContain("Selected action 42");
    } finally {
      view.showEmpty("done");
    }
  }

  @Test
  @DisplayName("F1 help wraps its final shortcuts into visible rows at a constrained width")
  void keyboardHelpWrapsAtConstrainedWidth() throws Exception {
    TimelineView view = new TimelineView();
    JComponent canvas = view.canvasForTest();
    canvas.setSize(WIDTH, HEIGHT);
    view.setModel(aModel());

    SwingUtilities.invokeAndWait(
        () -> {
          layoutTree(view, 900, 600);
          invokeKey(canvas, KeyStroke.getKeyStroke(KeyEvent.VK_F1, 0));
          layoutTree(view, 900, 600);
        });

    JTextArea help = view.interactionTextForTest();
    int lineHeight = help.getFontMetrics(help.getFont()).getHeight();
    int rIndex = help.getText().indexOf("R to select");
    int escapeIndex = help.getText().indexOf("Escape to clear");
    assertThat(rIndex).isNotNegative();
    assertThat(escapeIndex).isNotNegative();

    AtomicReference<Rectangle2D> rBounds = new AtomicReference<>();
    AtomicReference<Rectangle2D> escapeBounds = new AtomicReference<>();
    SwingUtilities.invokeAndWait(
        () -> {
          try {
            rBounds.set(help.modelToView2D(rIndex));
            escapeBounds.set(help.modelToView2D(escapeIndex));
          } catch (BadLocationException e) {
            throw new AssertionError(e);
          }
        });

    assertThat(help.getWidth())
        .isLessThan(help.getFontMetrics(help.getFont()).stringWidth(help.getText()));
    assertThat(help.getHeight()).isGreaterThan(lineHeight);
    assertThat(rBounds.get().getY()).isGreaterThan(0);
    assertThat(rBounds.get().getMaxY()).isLessThanOrEqualTo(help.getHeight());
    assertThat(escapeBounds.get().getY()).isGreaterThan(0);
    assertThat(escapeBounds.get().getMaxY()).isLessThanOrEqualTo(help.getHeight());
  }

  @Test
  @DisplayName("aggregate accessibility leads with what is painted before a retained selection")
  void aggregateDescriptionPrecedesStaleSelection() {
    TimelineView view = new TimelineView();
    JComponent canvas = view.canvasForTest();
    canvas.setSize(WIDTH, HEIGHT);
    view.setModel(aModel());
    view.setWindow(SpanWindow.builder(0, 10_000_000).build());
    view.select(42);
    assertThat(view.drawingSpansForTest()).isTrue();

    invokeKey(canvas, KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, KeyEvent.SHIFT_DOWN_MASK));

    assertThat(view.drawingSpansForTest()).isFalse();
    assertThat(canvas.getAccessibleContext().getAccessibleDescription())
        .startsWith("Showing aggregate build activity")
        .contains("Selected action 42 is retained but is not painted at this zoom")
        .doesNotContain("current exact window");
  }

  @Test
  @DisplayName("action details occupy the right side instead of taking plot height")
  void inspectorIsTheRightSideOfAHorizontalSplit() {
    TimelineView view = new TimelineView();

    JSplitPane split = view.contentSplitForTest();
    assertThat(split.getOrientation()).isEqualTo(JSplitPane.HORIZONTAL_SPLIT);
    assertThat(SwingUtilities.isDescendingFrom(view.inspectorForTest(), split.getRightComponent()))
        .isTrue();
    assertThat(SwingUtilities.isDescendingFrom(view.inspectorForTest(), split.getLeftComponent()))
        .isFalse();
    assertThat(view.inspectorVisibleForTest())
        .as("the pane is stable, but initially contains only its prompt")
        .isFalse();
  }

  @Test
  @DisplayName("right-clicking a span selects it and offers its shared actions")
  void spanContextMenuUsesTheFacility() {
    TimelineView view = new TimelineView();
    view.canvasForTest().setSize(WIDTH, HEIGHT);
    view.setModel(aModel());
    SpanWindow.Builder window = SpanWindow.builder(0, 10_000_000);
    window.add(0, 5_000_000, 0, 42, "");
    view.setWindow(window.build());
    Recorder recorder = new Recorder();
    view.installEntityActions(
        new EntityActions(
            Set.of(
                EntityActions.Command.OPEN_IN_GRAPH,
                EntityActions.Command.REVEAL_ACTION,
                EntityActions.Command.SHOW_ON_TIMELINE),
            recorder));

    JPopupMenu menu = view.contextMenuAtForTest(100, 1);

    assertThat(itemsIn(menu))
        .extracting(JMenuItem::getText)
        .containsExactly("Open in graph", "Reveal action")
        .doesNotContain("Show on timeline");
    assertThat(view.viewport().orElseThrow().selectedNode()).hasValue(42);
    assertThat(view.inspectorVisibleForTest()).isTrue();

    itemsIn(menu).getFirst().doClick();
    assertThat(recorder.commands).containsExactly(EntityActions.Command.OPEN_IN_GRAPH);
    assertThat(recorder.refs).containsExactly(new EntityRef.ActionId(42));
    assertThat(view.contextMenuAtForTest(900, 1).getComponentCount()).isZero();
  }

  @Test
  @DisplayName("the inspector's actions dispatch through the shared facility")
  void inspectorActionsUseTheFacility() {
    TimelineView view = new TimelineView();
    Recorder recorder = new Recorder();
    view.installEntityActions(
        new EntityActions(
            Set.of(
                EntityActions.Command.REVEAL_ACTION,
                EntityActions.Command.OPEN_TARGET,
                EntityActions.Command.SHOW_ON_TIMELINE),
            recorder));

    view.showInspector(
        new SpanDetails(
            "//pkg:thing",
            List.of("Action 7 (Javac) — SUCCESS", "Primary output: thing.jar"),
            List.of(new EntityRef.ActionId(7), new EntityRef.TargetLabel("//pkg:thing"))));

    InspectorHeader header = view.inspectorHeaderForTest();
    // The controller's first line is the segment's one-line identity, so
    // it is the header's subtitle rather than the first body line.
    assertThat(header.subtitleForTest()).isEqualTo("Action 7 (Javac) — SUCCESS");
    assertThat(header.overflowForTest().isVisible()).isTrue();

    List<JMenuItem> items = itemsIn(header.overflowMenuForTest());
    assertThat(items)
        .extracting(JMenuItem::getText)
        .containsExactly("Open target", "Reveal action")
        // Not "Show on timeline": the segment it would show is the one
        // already under the pointer.
        .doesNotContain("Show on timeline");

    items.stream()
        .filter(item -> item.getText().equals("Reveal action"))
        .findFirst()
        .orElseThrow()
        .doClick();

    assertThat(recorder.commands).containsExactly(EntityActions.Command.REVEAL_ACTION);
    assertThat(recorder.refs).containsExactly(new EntityRef.ActionId(7));
  }

  @Test
  @DisplayName("without the facility installed, details show and no dead affordance appears")
  void noFacilityMeansNoButtons() {
    TimelineView view = new TimelineView();
    view.showInspector(
        new SpanDetails("Action 9", List.of("a line"), List.of(new EntityRef.ActionId(9))));

    assertThat(view.inspectorVisibleForTest()).isTrue();
    assertThat(view.inspectorHeaderForTest().overflowForTest().isVisible()).isFalse();
    assertThat(view.inspectorHeaderForTest().overflowMenuForTest().getComponentCount()).isZero();
    // Close is the view's own control and stays, on the same fixed edge.
    assertThat(buttonsIn(view.inspectorHeaderForTest()))
        .extracting(JButton::getText)
        .containsExactly("Close");
  }

  @Test
  @DisplayName("the controller words an action's details from what was reported, only that")
  void detailsAreHonest() {
    ActionRow row =
        new ActionRow(
            7,
            "bazel-out/k8-fastbuild/bin/pkg/thing.jar",
            Optional.of("//pkg:thing"),
            Optional.of("Javac"),
            ActionOutcome.SUCCEEDED,
            OptionalLong.empty(),
            OptionalLong.empty(),
            Optional.of("this Bazel version reports no action times"),
            OptionalInt.empty(),
            OptionalInt.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            OptionalLong.of(31),
            Execution.none());

    SpanDetails details = TimelineController.detailsOf(row);

    assertThat(details.title()).isEqualTo("//pkg:thing");
    // The unknown duration is a worded reason, never "0.000 s".
    assertThat(String.join("\n", details.lines()))
        .contains("Duration unknown: this Bazel version reports no action times")
        .doesNotContain("Duration: 0");
    assertThat(details.refs())
        .containsExactly(
            new EntityRef.ActionId(7),
            new EntityRef.TargetLabel("//pkg:thing"),
            new EntityRef.EventId(31));
  }
}
