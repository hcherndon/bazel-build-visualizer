package com.holtherndon.bazelviz.ui.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import com.holtherndon.bazelviz.core.filter.FilterExpression;
import com.holtherndon.bazelviz.core.filter.FilterExpression.Condition;
import com.holtherndon.bazelviz.core.filter.FilterExpression.Operator;
import com.holtherndon.bazelviz.ui.filter.FilterField.Choice;
import com.holtherndon.bazelviz.ui.filter.FilterField.Kind;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.awt.Window;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Run explicitly on a desktop: exercises real popups without driving the mouse or keyboard. */
class FilterDialogTest {
  @Test
  @Timeout(30)
  void dropdownsStayOpenInTheEditorAndApplyingOrCancellingClosesOnlyTheEditor() throws Exception {
    assumeFalse(GraphicsEnvironment.isHeadless(), "Requires a desktop display");
    SwingUtilities.invokeAndWait(
        () -> {
          JFrame frame = new JFrame("Filter dropdown regression check");
          FilterBuilder builder =
              new FilterBuilder(
                  List.of(
                      new FilterField(
                          "type",
                          "Type",
                          Kind.CHOICE,
                          List.of(new Choice("7", "action")),
                          "Event type"),
                      new FilterField(
                          "children", "Children", Kind.NUMBER, List.of(), "Announced children")));
          frame.setContentPane(builder);
          frame.setSize(700, 260);
          frame.setLocationByPlatform(true);
          try {
            frame.setVisible(true);
            button(builder, "Add filter").doClick();
            JDialog dialog = editor(frame);
            assertThat(dialog.isModal()).isFalse();
            List<JComboBox> dropdowns = controls(dialog, JComboBox.class);
            JComboBox<?> field = dropdowns.getFirst();
            field.showPopup();
            assertThat(field.isPopupVisible()).isTrue();
            assertThat(dialog.isShowing()).isTrue();
            field.setSelectedIndex(1);
            field.hidePopup();
            assertThat(dialog.isShowing()).isTrue();
            JComboBox<?> operator = dropdowns.get(1);
            operator.showPopup();
            assertThat(operator.isPopupVisible()).isTrue();
            assertThat(dialog.isShowing()).isTrue();
            operator.setSelectedItem(Operator.GREATER_THAN);
            operator.hidePopup();
            controls(dialog, JTextField.class).getFirst().setText("5");
            button(dialog, "Apply filter condition").doClick();
            Condition expected = new Condition("children", Operator.GREATER_THAN, List.of("5"));
            assertThat(builder.expression().children()).containsExactly(expected);
            assertThat(dialog.isDisplayable()).isFalse();
            assertThat(frame.isShowing()).isTrue();

            button(builder, "Edit filter: Children > 5").doClick();
            JDialog editing = editor(frame);
            assertThat(controls(editing, JTextField.class).getFirst().getText()).isEqualTo("5");
            controls(editing, JTextField.class).getFirst().setText("99");
            button(editing, "Cancel filter editing").doClick();
            assertThat(builder.expression().children()).containsExactly(expected);
            assertThat(editing.isDisplayable()).isFalse();

            button(builder, "Add filter").doClick();
            JDialog stale = editor(frame);
            builder.setExpression(FilterExpression.ALL);
            assertThat(stale.isDisplayable()).isFalse();
            assertThat(frame.isShowing()).isTrue();
          } finally {
            frame.dispose();
          }
        });
  }

  private static JDialog editor(JFrame frame) {
    return Arrays.stream(frame.getOwnedWindows())
        .filter(Window::isShowing)
        .filter(JDialog.class::isInstance)
        .map(JDialog.class::cast)
        .findFirst()
        .orElseThrow();
  }

  private static JButton button(Container parent, String name) {
    return controls(parent, JButton.class).stream()
        .filter(button -> name.equals(button.getAccessibleContext().getAccessibleName()))
        .findFirst()
        .orElseThrow();
  }

  private static <T extends Component> List<T> controls(Container parent, Class<T> type) {
    List<T> result = new ArrayList<>();
    for (Component component : parent.getComponents()) {
      if (type.isInstance(component)) {
        result.add(type.cast(component));
      }
      if (component instanceof Container container) {
        result.addAll(controls(container, type));
      }
    }
    return result;
  }
}
