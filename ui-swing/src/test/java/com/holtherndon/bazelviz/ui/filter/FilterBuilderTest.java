package com.holtherndon.bazelviz.ui.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.holtherndon.bazelviz.core.filter.FilterExpression;
import com.holtherndon.bazelviz.core.filter.FilterExpression.Condition;
import com.holtherndon.bazelviz.core.filter.FilterExpression.Group;
import com.holtherndon.bazelviz.core.filter.FilterExpression.Junction;
import com.holtherndon.bazelviz.core.filter.FilterExpression.Operator;
import com.holtherndon.bazelviz.ui.filter.FilterField.Choice;
import com.holtherndon.bazelviz.ui.filter.FilterField.Kind;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

class FilterBuilderTest {
  private static final List<FilterField> FIELDS =
      List.of(
          new FilterField(
              "type",
              "Type",
              Kind.CHOICE,
              List.of(new Choice("7", "action"), new Choice("18", "configured")),
              "Choose a type."),
          new FilterField("children", "Children", Kind.NUMBER, List.of(), "Children count."),
          new FilterField("event_id", "Event ID", Kind.TEXT, List.of(), "Literal text."));

  @Test
  void nestedGroupsAndConditionsCanBeChangedAndRemovedWithRealControls() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          FilterBuilder builder = new FilterBuilder(FIELDS);
          List<FilterExpression> changes = new ArrayList<>();
          builder.onChange(changes::add);
          Condition type = new Condition("type", Operator.IN, List.of("7", "18"));
          Condition children = new Condition("children", Operator.GREATER_THAN, List.of("5"));
          builder.setExpression(
              new Group(Junction.ALL, List.of(type, new Group(Junction.ANY, List.of(children)))));
          assertThat(buttons(builder, "filter.remove")).hasSize(2);
          buttons(builder, "filter.remove").getFirst().doClick();
          assertThat(builder.expression().children())
              .containsExactly(new Group(Junction.ANY, List.of(children)));
          controls(builder, JComboBox.class).getFirst().setSelectedIndex(1);
          assertThat(builder.expression().junction()).isEqualTo(Junction.ANY);
          accessibleButton(builder, "Remove filter group").doClick();
          assertThat(builder.expression().isEmpty()).isTrue();
          accessibleButton(builder, "Add filter group").doClick();
          assertThat(builder.expression().children()).hasSize(1);
          accessibleButton(builder, "Clear all filters").doClick();
          assertThat(builder.expression()).isEqualTo(FilterExpression.ALL);
          assertThat(changes).hasSize(6);
        });
  }

  @Test
  void choiceEditorSupportsSetsWithoutQueryTextAndPreservesEdits() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          FilterBuilder builder = new FilterBuilder(FIELDS);
          Condition selected = new Condition("type", Operator.IN, List.of("7", "18"));
          AtomicReference<Condition> applied = new AtomicReference<>();
          FilterBuilder.ConditionEditor editor =
              builder.new ConditionEditor(selected, applied::set, () -> {});
          assertThat(controls(editor, JCheckBox.class)).allMatch(JCheckBox::isSelected);
          controls(editor, JCheckBox.class).getFirst().doClick();
          accessibleButton(editor, "Apply filter condition").doClick();
          assertThat(applied.get()).isEqualTo(new Condition("type", Operator.IN, List.of("18")));
          controls(editor, JComboBox.class).get(1).setSelectedItem(Operator.EQUALS);
          controls(editor, JCheckBox.class).getFirst().doClick();
          assertThat(editor.readCondition())
              .isEqualTo(new Condition("type", Operator.EQUALS, List.of("7")));
        });
  }

  @Test
  void numericInputsRejectInvalidValuesAndUnknownOperatorNeedsNoValue() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          FilterBuilder builder = new FilterBuilder(FIELDS);
          FilterBuilder.ConditionEditor editor =
              builder
              .new ConditionEditor(
                  new Condition("children", Operator.GREATER_THAN, List.of("5")),
                  ignored -> {},
                  () -> {});
          JTextField value = controls(editor, JTextField.class).getFirst();
          value.setText(" 6 ");
          assertThat(editor.readCondition().values()).containsExactly("6");
          value.setText("5 && type = action");
          assertThatThrownBy(editor::readCondition)
              .isInstanceOf(IllegalArgumentException.class)
              .hasMessageContaining("whole number");
          controls(editor, JComboBox.class).get(1).setSelectedItem(Operator.IS_ABSENT);
          assertThat(editor.readCondition().values()).isEmpty();
          assertThat(controls(editor, JTextField.class)).isEmpty();
        });
  }

  @Test
  void manyFiltersStayInAScrollableCompactArea() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          FilterBuilder builder = new FilterBuilder(FIELDS);
          builder.setSize(600, 180);
          List<FilterExpression> children = new ArrayList<>();
          for (int i = 0; i < 20; i++) {
            children.add(
                new Condition("event_id", Operator.CONTAINS, List.of("//package:target_" + i)));
          }
          builder.setExpression(new Group(Junction.ALL, children));
          JScrollPane scroll = controls(builder, JScrollPane.class).getFirst();
          assertThat(scroll.getPreferredSize().height).isLessThanOrEqualTo(180);
          assertThat(buttons(builder, "filter.remove")).hasSize(20);
        });
  }

  private static JButton accessibleButton(Container root, String name) {
    return controls(root, JButton.class).stream()
        .filter(button -> name.equals(button.getAccessibleContext().getAccessibleName()))
        .findFirst()
        .orElseThrow();
  }

  private static List<JButton> buttons(Container root, String name) {
    return controls(root, JButton.class).stream()
        .filter(button -> name.equals(button.getName()))
        .toList();
  }

  private static <T extends Component> List<T> controls(Container root, Class<T> type) {
    List<T> found = new ArrayList<>();
    for (Component child : root.getComponents()) {
      if (type.isInstance(child)) {
        found.add(type.cast(child));
      }
      if (child instanceof Container container) {
        found.addAll(controls(container, type));
      }
    }
    return found;
  }
}
