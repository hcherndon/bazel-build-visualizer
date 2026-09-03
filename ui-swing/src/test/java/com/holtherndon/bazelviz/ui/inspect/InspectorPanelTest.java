package com.holtherndon.bazelviz.ui.inspect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.holtherndon.bazelviz.ui.files.FileLink;
import com.holtherndon.bazelviz.ui.nav.EntityActions;
import com.holtherndon.bazelviz.ui.nav.EntityActions.Command;
import com.holtherndon.bazelviz.ui.nav.EntityRef;
import com.holtherndon.bazelviz.ui.nav.EntityRef.EventId;
import com.holtherndon.bazelviz.ui.nav.EntityRef.TargetLabel;
import com.holtherndon.bazelviz.ui.theme.HyperlinkLabel;
import com.holtherndon.bazelviz.ui.theme.PlainText;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.border.TitledBorder;
import javax.swing.plaf.basic.BasicHTML;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What the shared inspector puts on screen.
 *
 * <p>Headless: none of these components realizes a peer. The assertion at the top makes that a
 * stated precondition rather than a coincidence.
 */
class InspectorPanelTest {

  @BeforeAll
  static void requireHeadless() {
    assertThat(GraphicsEnvironment.isHeadless())
        .as("these tests must not depend on a display")
        .isTrue();
  }

  @Test
  @DisplayName("an unknown value is rendered as a word, with its reason")
  void unknownValuesAreVisiblyUnknown() throws Exception {
    Inspection inspection =
        new Inspection.Builder("//pkg:target")
            .section("Timing")
            .field(Inspection.Field.of("Start", "12:00:00"))
            .field(
                Inspection.Field.unknown(
                    "Duration", "this Bazel version does not report action timestamps"))
            .build();

    List<String> labels = labelsOf(inspection);

    // The exit criterion in one assertion: an absence reads as an absence
    // and says why, rather than as a blank a user would take for a value.
    assertThat(labels).contains("12:00:00");
    assertThat(labels)
        .anyMatch(
            text ->
                text.startsWith(InspectorPanel.UNKNOWN)
                    && text.contains("does not report action timestamps"));
  }

  @Test
  @DisplayName("an empty value is not the same as an unknown one")
  void emptyIsNotUnknown() throws Exception {
    Inspection inspection =
        new Inspection.Builder("//pkg:target")
            .section("Result")
            .field(Inspection.Field.of("Message", ""))
            .build();

    assertThat(labelsOf(inspection)).contains("");
    assertThat(labelsOf(inspection)).noneMatch(text -> text.startsWith(InspectorPanel.UNKNOWN));
  }

  @Test
  @DisplayName("an openable file is one wrapping blue link without a separate button")
  void fileFieldsAreClickable() throws Exception {
    var link = FileLink.testLog("test.log", "file:///tmp/test.log");
    AtomicReference<FileLink> opened = new AtomicReference<>();
    InspectorPanel panel = onEdt(InspectorPanel::new);
    onEdt(
        () -> {
          panel.onOpenFile(opened::set);
          panel.show(
              new Inspection.Builder("//pkg:test")
                  .section("Logs")
                  .file("test.log", link.location(), link)
                  .build());
          return null;
        });

    HyperlinkLabel open =
        onEdt(() -> componentsOf(panel, HyperlinkLabel.class).stream().findFirst().orElseThrow());
    assertThat(open.getText()).isEqualTo(link.location());
    onEdt(
        () -> {
          open.activate();
          return null;
        });
    assertThat(opened.get()).isEqualTo(link);
    assertThat(open.getAccessibleContext().getAccessibleName()).isEqualTo("Open test.log");
    assertThat(onEdt(() -> buttonsOf(panel))).noneMatch(button -> "Open".equals(button.getText()));
    onEdt(
        () -> {
          layoutAt(panel, 320, 260);
          return null;
        });
    assertThat(onEdt(open::getWidth))
        .as("the path owns the value column instead of being squeezed beside a button")
        .isGreaterThan(100);
  }

  @Test
  @DisplayName("long values and unknown explanations wrap as the inspector narrows")
  void longFieldsWrapWithinThePane() throws Exception {
    // Output paths and labels are often one uninterrupted token. Word
    // wrapping must still fall back to character boundaries rather than
    // treating such a value as permission to grow sideways.
    String value = "bazel-out/" + "very-long-generated-output-segment/".repeat(16) + "artifact.o";
    String reason =
        "this value was unavailable because the imported session did not carry "
            + "the auxiliary source that would have reported it";
    InspectorPanel panel = onEdt(InspectorPanel::new);
    onEdt(
        () -> {
          panel.show(
              new Inspection.Builder("//pkg:target")
                  .section("Details")
                  .field("Command", value)
                  .field(Inspection.Field.unknown("Duration", reason))
                  .build());
          layoutAt(panel, 440, 320);
          return null;
        });

    List<JTextArea> fields = onEdt(() -> textAreasOf(panel));
    assertThat(fields).hasSize(2);
    assertThat(fields)
        .extracting(JTextArea::getText)
        .containsExactly(value, InspectorPanel.UNKNOWN + " — " + reason);
    assertThat(fields)
        .allSatisfy(
            field -> {
              assertThat(field.getLineWrap()).isTrue();
              assertThat(field.getWrapStyleWord()).isTrue();
              assertThat(field.getClientProperty(BasicHTML.propertyKey)).isNull();
            });

    int wideKnown = onEdt(() -> fields.get(0).getPreferredSize().height);
    int wideUnknown = onEdt(() -> fields.get(1).getPreferredSize().height);
    onEdt(
        () -> {
          layoutAt(panel, 230, 320);
          return null;
        });
    int narrowKnown = onEdt(() -> fields.get(0).getPreferredSize().height);
    int narrowUnknown = onEdt(() -> fields.get(1).getPreferredSize().height);

    assertThat(narrowKnown)
        .as("the known value gains lines at a narrow width")
        .isGreaterThan(wideKnown);
    assertThat(narrowUnknown)
        .as("the unknown reason gains lines at a narrow width")
        .isGreaterThan(wideUnknown);
    assertThat(onEdt(() -> panel.bodyForTest().getScrollableTracksViewportWidth())).isTrue();
    assertThat(onEdt(() -> panel.scrollForTest().getHorizontalScrollBarPolicy()))
        .isEqualTo(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
    assertThat(onEdt(() -> panel.scrollForTest().getHorizontalScrollBar().isVisible())).isFalse();
    assertThat(onEdt(() -> fields.get(0).getWidth()))
        .isLessThanOrEqualTo(
            onEdt(() -> panel.scrollForTest().getViewport().getExtentSize().width));
  }

  @Test
  @DisplayName("field names remain accessible labels for plain-text wrapped values")
  void wrappedValuesKeepAccessibilityAndPlainTextSafety() throws Exception {
    String hostile = "<html><img src='https://example.invalid/a.png'>reported value";
    InspectorPanel panel = onEdt(InspectorPanel::new);
    onEdt(
        () -> {
          panel.show(
              new Inspection.Builder("//pkg:target")
                  .section("Details")
                  .field("Command", hostile)
                  .field(Inspection.Field.unknown("Duration", hostile))
                  .build());
          return null;
        });

    List<JTextArea> fields = onEdt(() -> textAreasOf(panel));
    JLabel command = onEdt(() -> labelNamed(panel, "Command"));
    JLabel duration = onEdt(() -> labelNamed(panel, "Duration"));

    assertThat(command.getLabelFor()).isSameAs(fields.get(0));
    assertThat(duration.getLabelFor()).isSameAs(fields.get(1));
    assertThat(fields.get(0).getAccessibleContext().getAccessibleName()).isEqualTo("Command");
    assertThat(fields.get(1).getAccessibleContext().getAccessibleDescription())
        .contains("Unknown Duration")
        .contains(hostile);
    assertThat(fields.get(0).getText()).isEqualTo(hostile);
    assertThat(fields)
        .allSatisfy(
            field -> {
              assertThat(field.isEditable()).isFalse();
              assertThat(field.isFocusable()).isTrue();
              field.selectAll();
              assertThat(field.getSelectedText()).isEqualTo(field.getText());
            });
    assertThat(fields.get(0).getToolTipText()).isEqualTo(PlainText.tooltip(hostile));
    assertThat(fields)
        .allSatisfy(field -> assertThat(field.getClientProperty(BasicHTML.propertyKey)).isNull());
  }

  @Test
  @DisplayName("sections keep compact row heights and leave unused space below")
  void sectionsDoNotStretchToFillThePane() throws Exception {
    InspectorPanel panel = onEdt(InspectorPanel::new);
    onEdt(
        () -> {
          panel.show(
              new Inspection.Builder("//pkg:target")
                  .section("Identity")
                  .field("Label", "//pkg:target")
                  .section("Timing")
                  .field("Duration", "12.4 ms")
                  .section("Command")
                  .field("Mnemonic", "Javac")
                  .section("Result")
                  .field("Outcome", "succeeded")
                  .build());
          layoutAt(panel, 360, 760);
          return null;
        });

    List<JPanel> sections = onEdt(() -> sectionPanelsOf(panel.bodyForTest()));
    assertThat(sections).hasSize(4);
    assertThat(sections)
        .allSatisfy(
            section -> {
              assertThat(section.getHeight())
                  .as(((TitledBorder) section.getBorder()).getTitle())
                  .isLessThanOrEqualTo(section.getPreferredSize().height);
              assertThat(section.getMaximumSize().height)
                  .isEqualTo(section.getPreferredSize().height);
            });

    int lastBottom =
        onEdt(
            () -> {
              JPanel last = sections.getLast();
              return last.getY() + last.getHeight();
            });
    assertThat(lastBottom)
        .as("the sections remain at the top; the trailing glue owns unused height")
        .isLessThan(onEdt(() -> panel.bodyForTest().getHeight()) - 100);
  }

  @Test
  @DisplayName("the source-event button appears only when there is one, and reports it")
  void sourceEventButtonReportsTheEvent() throws Exception {
    AtomicLong requested = new AtomicLong(-1);
    InspectorPanel panel = onEdt(InspectorPanel::new);
    onEdt(
        () -> {
          panel.onShowSourceEvent(requested::set);
          panel.show(
              new Inspection.Builder("//pkg:target")
                  .sourceEvent(OptionalLong.of(4_812L))
                  .section("Target")
                  .field("Label", "//pkg:target")
                  .build());
          return null;
        });

    assertThat(panel.displayed().sourceEventId()).hasValue(4_812L);

    onEdt(
        () -> {
          buttonsOf(panel).forEach(button -> button.doClick());
          return null;
        });
    assertThat(requested.get()).isEqualTo(4_812L);
  }

  @Test
  @DisplayName(
      "with the shared actions installed, the overflow menu takes over"
          + " and nothing appears twice")
  void sharedActionsReplaceTheLegacyButton() throws Exception {
    List<Command> dispatched = new ArrayList<>();
    List<EntityRef> receivedRefs = new ArrayList<>();
    EntityActions actions =
        new EntityActions(
            EnumSet.of(Command.SHOW_SOURCE_EVENT, Command.OPEN_TARGET),
            (command, ref) -> {
              dispatched.add(command);
              receivedRefs.add(ref);
            });

    InspectorPanel panel = onEdt(InspectorPanel::new);
    onEdt(
        () -> {
          panel.installEntityActions(actions, Set.of());
          panel.show(
              new Inspection.Builder("//pkg:target")
                  .subtitle("Javac · SUCCEEDED")
                  .sourceEvent(OptionalLong.of(4_812L))
                  .ref(new EventId(4_812L))
                  .ref(new TargetLabel("//pkg:target"))
                  .section("Target")
                  .field("Label", "//pkg:target")
                  .build());
          return null;
        });

    InspectorHeader header = panel.headerForTest();
    assertThat(onEdt(header::titleForTest)).isEqualTo("//pkg:target");
    assertThat(onEdt(header::subtitleForTest)).isEqualTo("Javac · SUCCEEDED");

    // Exactly one "Show source event" on offer: the menu's. The legacy
    // button yields rather than doubling it, so the only visible titled
    // button left in the whole panel is the overflow's ellipsis.
    assertThat(onEdt(() -> menuTitlesOf(header)))
        .containsExactly("Open target", "Show source event");
    assertThat(onEdt(() -> titledButtonsOf(panel))).containsExactly(InspectorHeader.OVERFLOW_TEXT);

    onEdt(
        () -> {
          clickMenu(header, "Open target");
          clickMenu(header, "Show source event");
          return null;
        });
    assertThat(dispatched).containsExactly(Command.OPEN_TARGET, Command.SHOW_SOURCE_EVENT);
    assertThat(receivedRefs).containsExactly(new TargetLabel("//pkg:target"), new EventId(4_812L));

    // An inspection with no refs — a view that has not adopted the
    // facility — falls back to the legacy button, exactly as before, and
    // offers no overflow at all rather than an empty menu.
    onEdt(
        () -> {
          panel.show(
              new Inspection.Builder("//pkg:other")
                  .sourceEvent(OptionalLong.of(7L))
                  .section("Target")
                  .field("Label", "//pkg:other")
                  .build());
          return null;
        });
    assertThat(onEdt(() -> titledButtonsOf(panel))).containsExactly("Show source event");
  }

  /** The menu the header's overflow button would open, by item text. */
  private static List<String> menuTitlesOf(InspectorHeader header) {
    List<String> titles = new ArrayList<>();
    JPopupMenu menu = header.overflowMenuForTest();
    for (int i = 0; i < menu.getComponentCount(); i++) {
      titles.add(((JMenuItem) menu.getComponent(i)).getText());
    }
    return titles;
  }

  /** Activates one item of the menu the overflow button would open. */
  private static void clickMenu(InspectorHeader header, String title) {
    JPopupMenu menu = header.overflowMenuForTest();
    for (int i = 0; i < menu.getComponentCount(); i++) {
      JMenuItem item = (JMenuItem) menu.getComponent(i);
      if (item.getText().equals(title)) {
        item.doClick();
        return;
      }
    }
    throw new AssertionError("no menu item titled " + title);
  }

  /** The visible, titled buttons — the offering, without scrollbar arrows. */
  private static List<String> titledButtonsOf(Container container) {
    List<String> texts = new ArrayList<>();
    buttonsOf(container)
        .forEach(
            button -> {
              if (button.getText() != null && !button.getText().isEmpty()) {
                texts.add(button.getText());
              }
            });
    return texts;
  }

  @Test
  @DisplayName("a field cannot claim both a value and a reason for having none")
  void aFieldIsOneThingOrTheOther() {
    assertThatThrownBy(
            () ->
                new Inspection.Field(
                    "Duration", Optional.of("1.5 ms"), Optional.of("not reported")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Duration");
  }

  private static List<String> labelsOf(Inspection inspection) throws Exception {
    InspectorPanel panel = onEdt(InspectorPanel::new);
    onEdt(
        () -> {
          panel.show(inspection);
          return null;
        });
    List<String> texts = new ArrayList<>();
    collectLabels(panel, texts);
    return texts;
  }

  private static void collectLabels(Container container, List<String> into) {
    for (Component child : container.getComponents()) {
      if (child instanceof JLabel label) {
        into.add(label.getText());
      }
      if (child instanceof JTextArea area) {
        into.add(area.getText());
      }
      if (child instanceof Container nested) {
        collectLabels(nested, into);
      }
    }
  }

  private static List<JTextArea> textAreasOf(Container container) {
    List<JTextArea> fields = new ArrayList<>();
    collectTextAreas(container, fields);
    return fields;
  }

  private static void collectTextAreas(Container container, List<JTextArea> into) {
    for (Component child : container.getComponents()) {
      if (child instanceof JTextArea area && "inspection.fieldValue".equals(area.getName())) {
        into.add(area);
      }
      if (child instanceof Container nested) {
        collectTextAreas(nested, into);
      }
    }
  }

  private static JLabel labelNamed(Container container, String text) {
    for (Component child : container.getComponents()) {
      if (child instanceof JLabel label && text.equals(label.getText())) {
        return label;
      }
      if (child instanceof Container nested) {
        try {
          return labelNamed(nested, text);
        } catch (AssertionError notHere) {
          // Keep looking in sibling containers.
        }
      }
    }
    throw new AssertionError("no label named " + text);
  }

  private static List<JPanel> sectionPanelsOf(Container container) {
    List<JPanel> sections = new ArrayList<>();
    for (Component child : container.getComponents()) {
      if (child instanceof JPanel panel && panel.getBorder() instanceof TitledBorder) {
        sections.add(panel);
      }
    }
    return sections;
  }

  /** Run every layout manager explicitly; headless components have no peer to validate. */
  private static void layoutAt(InspectorPanel panel, int width, int height) {
    panel.setSize(width, height);
    layoutDeep(panel);
    JScrollPane scroll = panel.scrollForTest();
    int viewportWidth = scroll.getViewport().getExtentSize().width;
    int wantedHeight = Math.max(height, panel.bodyForTest().getPreferredSize().height);
    panel.bodyForTest().setSize(viewportWidth, wantedHeight);
    layoutDeep(panel.bodyForTest());
    // The first pass gives each wrapping text area its width; the second
    // lets GridBagLayout account for the corresponding wrapped height.
    panel
        .bodyForTest()
        .setSize(viewportWidth, Math.max(height, panel.bodyForTest().getPreferredSize().height));
    layoutDeep(panel.bodyForTest());
  }

  private static void layoutDeep(Container container) {
    container.doLayout();
    for (Component child : container.getComponents()) {
      if (child instanceof Container nested) {
        layoutDeep(nested);
      }
    }
  }

  private static List<JButton> buttonsOf(Container container) {
    List<JButton> buttons = new ArrayList<>();
    collectButtons(container, buttons);
    return buttons;
  }

  private static void collectButtons(Container container, List<JButton> into) {
    for (Component child : container.getComponents()) {
      if (child instanceof JButton button && button.isVisible()) {
        into.add(button);
      }
      if (child instanceof Container nested) {
        collectButtons(nested, into);
      }
    }
  }

  private static <T extends Component> List<T> componentsOf(Container container, Class<T> type) {
    List<T> found = new ArrayList<>();
    for (Component child : container.getComponents()) {
      if (type.isInstance(child)) {
        found.add(type.cast(child));
      }
      if (child instanceof Container nested) {
        found.addAll(componentsOf(nested, type));
      }
    }
    return found;
  }

  private static <T> T onEdt(Callable<T> work) throws Exception {
    List<T> result = new ArrayList<>(1);
    List<Exception> failure = new ArrayList<>(1);
    SwingUtilities.invokeAndWait(
        () -> {
          try {
            result.add(work.call());
          } catch (Exception e) {
            failure.add(e);
          }
        });
    if (!failure.isEmpty()) {
      throw failure.getFirst();
    }
    return result.isEmpty() ? null : result.getFirst();
  }
}
