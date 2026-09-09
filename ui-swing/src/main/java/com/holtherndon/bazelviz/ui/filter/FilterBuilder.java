package com.holtherndon.bazelviz.ui.filter;

import com.holtherndon.bazelviz.core.filter.FilterExpression;
import com.holtherndon.bazelviz.core.filter.FilterExpression.Condition;
import com.holtherndon.bazelviz.core.filter.FilterExpression.Group;
import com.holtherndon.bazelviz.core.filter.FilterExpression.Junction;
import com.holtherndon.bazelviz.core.filter.FilterExpression.Operator;
import com.holtherndon.bazelviz.ui.theme.PlainText;
import com.holtherndon.bazelviz.ui.theme.ScrollableViewport;
import com.holtherndon.bazelviz.ui.theme.WrapLayout;
import com.holtherndon.bazelviz.ui.theme.WrappingLabel;
import java.awt.BorderLayout;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;

/** Reusable visual AND/OR groups of editable, removable condition chips. EDT only. */
public final class FilterBuilder extends JPanel {
  private static final long serialVersionUID = 1L;
  private final List<FilterField> fields;
  private final ScrollableViewport groups = new ScrollableViewport(new BorderLayout());
  private final JTextArea notice = WrappingLabel.create("");
  private Group expression = FilterExpression.ALL;
  private Consumer<FilterExpression> listener = ignored -> {};
  private JDialog conditionDialog;

  public FilterBuilder(List<FilterField> fields) {
    super(new BorderLayout());
    this.fields = List.copyOf(fields);
    if (fields.isEmpty()) {
      throw new IllegalArgumentException("At least one filter field is required.");
    }
    setName("filter.builder");
    getAccessibleContext().setAccessibleName("Filters");
    JScrollPane scroll =
        new JScrollPane(groups) {
          @Override
          public Dimension getPreferredSize() {
            Dimension size = super.getPreferredSize();
            return new Dimension(size.width, Math.min(size.height, 180));
          }
        };
    scroll.setBorder(BorderFactory.createEmptyBorder());
    scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
    scroll.getVerticalScrollBar().setUnitIncrement(16);
    notice.setVisible(false);
    add(scroll, BorderLayout.CENTER);
    add(notice, BorderLayout.SOUTH);
    render();
  }

  public Group expression() {
    return expression;
  }

  public void onChange(Consumer<FilterExpression> listener) {
    this.listener = Objects.requireNonNull(listener, "listener");
  }

  /** Installs a complete filter and notifies the host once, including Clear all. */
  public void setExpression(Group value) {
    Objects.requireNonNull(value, "value");
    validateFields(value);
    closeConditionEditor();
    expression = value;
    notice.setVisible(false);
    render();
    listener.accept(value);
  }

  private void validateFields(FilterExpression value) {
    if (value instanceof Group group) {
      group.children().forEach(this::validateFields);
    } else if (value instanceof Condition condition) {
      FilterField field = field(condition.field());
      if (!field.operators().contains(condition.operator())) {
        throw new IllegalArgumentException("Unsupported operator for " + field.label());
      }
    }
  }

  private FilterField field(String id) {
    return fields.stream()
        .filter(field -> field.id().equals(id))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Unknown filter field: " + id));
  }

  private void render() {
    groups.removeAll();
    groups.add(groupPanel(expression, List.of()), BorderLayout.NORTH);
    groups.revalidate();
    groups.repaint();
    revalidate();
  }

  private JPanel groupPanel(Group group, List<Integer> path) {
    JPanel panel = new JPanel(new BorderLayout());
    panel.setBorder(
        path.isEmpty()
            ? BorderFactory.createEmptyBorder(2, 4, 2, 4)
            : BorderFactory.createCompoundBorder(
                BorderFactory.createEtchedBorder(), BorderFactory.createEmptyBorder(2, 6, 2, 4)));
    JPanel header = new JPanel(new WrapLayout(FlowLayout.LEFT, 5, 2));
    header.add(new JLabel(path.isEmpty() ? "Filters · Match" : "Group · Match"));
    JComboBox<String> join = new JComboBox<>(new String[] {"All (AND)", "Any (OR)"});
    join.setName("filter.join");
    join.getAccessibleContext().setAccessibleName("Match conditions");
    join.setSelectedIndex(group.junction() == Junction.ALL ? 0 : 1);
    join.setToolTipText(
        "All requires every condition; Any requires at least one. Empty groups do not restrict"
            + " results.");
    join.addActionListener(
        event ->
            replace(
                path,
                new Group(
                    join.getSelectedIndex() == 0 ? Junction.ALL : Junction.ANY, group.children())));
    header.add(join);
    JButton add = button("+ Add filter", "Add filter", () -> {});
    add.addActionListener(event -> editCondition(add, path, null));
    header.add(add);
    header.add(
        button(
            "+ Add group",
            "Add filter group",
            () -> append(path, new Group(Junction.ALL, List.of()))));
    if (path.isEmpty()) {
      JButton clear =
          button("Clear all", "Clear all filters", () -> setExpression(FilterExpression.ALL));
      clear.setEnabled(!group.children().isEmpty());
      header.add(clear);
    } else {
      header.add(button("×", "Remove filter group", () -> replace(path, null)));
    }
    panel.add(header, BorderLayout.NORTH);
    JPanel body = new JPanel();
    body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
    JPanel chips = new JPanel(new WrapLayout(FlowLayout.LEFT, 5, 3));
    for (int index = 0; index < group.children().size(); index++) {
      FilterExpression child = group.children().get(index);
      List<Integer> childPath = new ArrayList<>(path);
      childPath.add(index);
      if (child instanceof Group nested) {
        if (chips.getComponentCount() > 0) {
          body.add(chips);
          chips = new JPanel(new WrapLayout(FlowLayout.LEFT, 5, 3));
        }
        body.add(groupPanel(nested, List.copyOf(childPath)));
      } else {
        chips.add(conditionChip((Condition) child, List.copyOf(childPath)));
      }
    }
    if (chips.getComponentCount() > 0) {
      body.add(chips);
    }
    panel.add(body, BorderLayout.CENTER);
    return panel;
  }

  private JPanel conditionChip(Condition condition, List<Integer> path) {
    String label = describe(condition);
    JPanel chip =
        new JPanel(new BorderLayout()) {
          @Override
          public Dimension getPreferredSize() {
            Dimension size = super.getPreferredSize();
            int width = FilterBuilder.this.getWidth();
            // Keep the remove button reachable for long identity strings, including nested groups.
            return width == 0
                ? size
                : new Dimension(
                    Math.min(size.width, Math.max(120, width - 60 - path.size() * 14)),
                    size.height);
          }
        };
    chip.setBorder(BorderFactory.createEtchedBorder());
    JButton edit =
        button(
            label.length() > 85 ? label.substring(0, 82) + "…" : label,
            "Edit filter: " + label,
            () -> {});
    edit.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
    edit.setToolTipText(PlainText.tooltip(label + " — click to edit"));
    edit.addActionListener(event -> editCondition(edit, path, condition));
    chip.add(edit, BorderLayout.CENTER);
    JButton remove = button("×", "Remove filter: " + label, () -> replace(path, null));
    remove.setName("filter.remove");
    remove.setToolTipText("Remove this condition");
    remove.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
    chip.add(remove, BorderLayout.EAST);
    return chip;
  }

  private String describe(Condition condition) {
    FilterField field = field(condition.field());
    return field.label()
        + " "
        + condition.operator()
        + (condition.values().isEmpty()
            ? ""
            : " "
                + String.join(
                    ", ", condition.values().stream().map(field::describeValue).toList()));
  }

  private static JButton button(String label, String accessible, Runnable action) {
    JButton button = PlainText.disableHtml(new JButton(label));
    button.getAccessibleContext().setAccessibleName(accessible);
    button.addActionListener(event -> action.run());
    return button;
  }

  private void append(List<Integer> path, FilterExpression value) {
    Group group = (Group) at(expression, path);
    List<FilterExpression> children = new ArrayList<>(group.children());
    children.add(value);
    try {
      replace(path, new Group(group.junction(), children));
    } catch (IllegalArgumentException invalid) {
      showError(invalid);
    }
  }

  private void replace(List<Integer> path, FilterExpression value) {
    try {
      setExpression((Group) replaced(expression, path, value));
    } catch (IllegalArgumentException invalid) {
      showError(invalid);
    }
  }

  private void showError(IllegalArgumentException invalid) {
    notice.setText(invalid.getMessage());
    notice.setVisible(true);
    revalidate();
  }

  private static FilterExpression at(FilterExpression current, List<Integer> path) {
    for (int index : path) {
      current = ((Group) current).children().get(index);
    }
    return current;
  }

  private static FilterExpression replaced(
      FilterExpression current, List<Integer> path, FilterExpression value) {
    if (path.isEmpty()) {
      return value;
    }
    Group group = (Group) current;
    List<FilterExpression> children = new ArrayList<>(group.children());
    int index = path.getFirst();
    if (path.size() == 1 && value == null) {
      children.remove(index);
    } else {
      children.set(index, replaced(children.get(index), path.subList(1, path.size()), value));
    }
    return new Group(group.junction(), children);
  }

  private void editCondition(JComponent anchor, List<Integer> path, Condition existing) {
    closeConditionEditor();
    // A JComboBox opens its own popup and clears Swing's menu selection path. Hosting this
    // form in JPopupMenu therefore dismisses the entire editor as soon as a dropdown opens.
    // A modeless, owned dialog survives dropdown focus changes without blocking the page.
    JDialog dialog =
        new JDialog(
            SwingUtilities.getWindowAncestor(this),
            existing == null ? "Add filter" : "Edit filter",
            Dialog.ModalityType.MODELESS);
    conditionDialog = dialog;
    dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
    ConditionEditor editor =
        new ConditionEditor(
            existing,
            condition -> {
              if (existing == null) {
                append(path, condition);
              } else {
                replace(path, condition);
              }
            },
            this::closeConditionEditor);
    dialog.setContentPane(editor);
    dialog.getRootPane().setDefaultButton(editor.save);
    dialog
        .getRootPane()
        .registerKeyboardAction(
            event -> closeConditionEditor(),
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
            JComponent.WHEN_IN_FOCUSED_WINDOW);
    dialog.pack();
    dialog.setMinimumSize(dialog.getSize());
    dialog.setLocationRelativeTo(anchor);
    dialog.setVisible(true);
    editor.fieldBox.requestFocusInWindow();
  }

  private void closeConditionEditor() {
    if (conditionDialog != null) {
      conditionDialog.dispose();
      conditionDialog = null;
    }
  }

  @Override
  public void removeNotify() {
    closeConditionEditor();
    super.removeNotify();
  }

  final class ConditionEditor extends JPanel {
    private static final long serialVersionUID = 1L;
    private final JComboBox<FilterField> fieldBox =
        new JComboBox<>(fields.toArray(FilterField[]::new));
    private final JComboBox<Operator> operator = new JComboBox<>();
    private final JTextField text = new JTextField(24);
    private final JPanel values = new JPanel(new BorderLayout());
    private final List<JCheckBox> choices = new ArrayList<>();
    private final JTextArea help = WrappingLabel.create("");
    private final JTextArea error = WrappingLabel.create("");
    private final JButton save;

    ConditionEditor(Condition existing, Consumer<Condition> apply, Runnable cancel) {
      super(new BorderLayout(6, 6));
      setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
      setPreferredSize(new Dimension(420, 330));
      JPanel form = new JPanel(new GridBagLayout());
      addRow(form, 0, "Field", fieldBox);
      addRow(form, 1, "Operator", operator);
      add(form, BorderLayout.NORTH);
      JPanel center = new JPanel(new BorderLayout(0, 6));
      center.add(help, BorderLayout.NORTH);
      center.add(values, BorderLayout.CENTER);
      center.add(error, BorderLayout.SOUTH);
      add(center, BorderLayout.CENTER);
      JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
      buttons.add(button("Cancel", "Cancel filter editing", cancel));
      save =
          button(
              existing == null ? "Add filter" : "Update filter",
              "Apply filter condition",
              () -> {
                try {
                  apply.accept(readCondition());
                } catch (IllegalArgumentException invalid) {
                  error.setText(invalid.getMessage());
                }
              });
      buttons.add(save);
      text.addActionListener(event -> save.doClick());
      add(buttons, BorderLayout.SOUTH);
      fieldBox.getAccessibleContext().setAccessibleName("Filter field");
      operator.getAccessibleContext().setAccessibleName("Filter operator");
      text.getAccessibleContext().setAccessibleName("Filter value");
      fieldBox.addActionListener(event -> fieldChanged());
      operator.addActionListener(event -> showValues());
      if (existing != null) {
        fieldBox.setSelectedItem(field(existing.field()));
      }
      fieldChanged();
      if (existing != null) {
        operator.setSelectedItem(existing.operator());
        text.setText(
            existing.operator().multipleValues()
                ? String.join(", ", existing.values())
                : existing.values().isEmpty() ? "" : existing.values().getFirst());
        for (int i = 0; i < choices.size(); i++) {
          choices
              .get(i)
              .setSelected(existing.values().contains(selectedField().choices().get(i).value()));
        }
      }
    }

    private FilterField selectedField() {
      return (FilterField) fieldBox.getSelectedItem();
    }

    private void fieldChanged() {
      choices.clear();
      operator.removeAllItems();
      selectedField().operators().forEach(operator::addItem);
      help.setText(selectedField().help());
      text.setText("");
      error.setText("");
      showValues();
    }

    private void showValues() {
      Operator op = (Operator) operator.getSelectedItem();
      if (op == null) {
        return;
      }
      List<String> selected = selectedChoices();
      choices.clear();
      values.removeAll();
      if (op.requiresValue()) {
        if (selectedField().choices().isEmpty()) {
          JPanel input = new JPanel(new BorderLayout());
          text.setToolTipText(
              op.multipleValues()
                  ? "Separate values with commas. For a value containing a comma, use an ‘is’"
                        + " condition."
                  : null);
          input.add(text, BorderLayout.NORTH);
          if (op.multipleValues()) {
            input.add(new JLabel("Separate values with commas"), BorderLayout.SOUTH);
          }
          values.add(input, BorderLayout.CENTER);
        } else {
          JPanel list = new JPanel();
          list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
          for (FilterField.Choice choice : selectedField().choices()) {
            JCheckBox check = PlainText.disableHtml(new JCheckBox(choice.label()));
            check.setActionCommand(choice.value());
            check.setSelected(
                selected.contains(choice.value())
                    && (op.multipleValues() || choices.stream().noneMatch(JCheckBox::isSelected)));
            check.addActionListener(
                event -> {
                  if (check.isSelected() && !op.multipleValues()) {
                    choices.stream()
                        .filter(other -> other != check)
                        .forEach(other -> other.setSelected(false));
                  }
                });
            choices.add(check);
            list.add(check);
          }
          values.add(new JScrollPane(list), BorderLayout.CENTER);
        }
      }
      values.revalidate();
      values.repaint();
    }

    private List<String> selectedChoices() {
      return choices.stream()
          .filter(JCheckBox::isSelected)
          .map(JCheckBox::getActionCommand)
          .toList();
    }

    Condition readCondition() {
      FilterField field = selectedField();
      Operator op = (Operator) operator.getSelectedItem();
      List<String> selected = List.of();
      if (op.requiresValue()) {
        if (!field.choices().isEmpty()) {
          selected = selectedChoices();
          if (selected.isEmpty()) {
            throw new IllegalArgumentException("Choose at least one value.");
          }
        } else {
          String input = text.getText();
          if (field.kind() == FilterField.Kind.NUMBER) {
            try {
              input = Long.toString(Long.parseLong(input.strip()));
            } catch (NumberFormatException invalid) {
              throw new IllegalArgumentException(
                  "Enter a whole number within the signed 64-bit range.");
            }
          } else if (input.isEmpty()) {
            throw new IllegalArgumentException("Enter a value.");
          }
          selected =
              op.multipleValues()
                  ? Arrays.stream(input.split(",", -1)).map(String::strip).toList()
                  : List.of(input);
          if (selected.stream().anyMatch(String::isEmpty)) {
            throw new IllegalArgumentException("Enter a value between each comma.");
          }
        }
      }
      return new Condition(field.id(), op, selected);
    }
  }

  private static void addRow(JPanel panel, int row, String name, JComponent value) {
    GridBagConstraints constraints = new GridBagConstraints();
    constraints.gridy = row;
    constraints.gridx = 0;
    constraints.anchor = GridBagConstraints.WEST;
    constraints.insets = new Insets(2, 0, 2, 8);
    JLabel label = new JLabel(name);
    label.setLabelFor(value);
    panel.add(label, constraints);
    constraints.gridx = 1;
    constraints.weightx = 1;
    constraints.fill = GridBagConstraints.HORIZONTAL;
    panel.add(value, constraints);
  }
}
