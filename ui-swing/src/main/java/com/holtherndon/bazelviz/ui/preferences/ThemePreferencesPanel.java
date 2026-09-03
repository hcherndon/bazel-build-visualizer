package com.holtherndon.bazelviz.ui.preferences;

import com.holtherndon.bazelviz.ui.theme.AppTheme;
import com.holtherndon.bazelviz.ui.theme.SectionPane;
import com.holtherndon.bazelviz.ui.theme.WrappingLabel;
import java.awt.BorderLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JTextArea;

/** Callback-only Preferences content for the application's colour theme. */
public final class ThemePreferencesPanel extends JPanel {

  private static final long serialVersionUID = 1L;

  private static final String CHANGE_NOTE =
      "Theme changes apply immediately and save automatically.";

  /**
   * Creates reusable Preferences content without installing or persisting a theme itself. Selection
   * callbacks run on the Swing event thread.
   *
   * @param active theme that is currently applied
   * @param onSelected applies and saves a selected theme, returning whether it succeeded
   */
  public ThemePreferencesPanel(AppTheme active, Predicate<AppTheme> onSelected) {
    super(new BorderLayout());
    Objects.requireNonNull(active, "active");
    Objects.requireNonNull(onSelected, "onSelected");

    JTextArea changeNote = WrappingLabel.create(CHANGE_NOTE);
    changeNote.getAccessibleContext().setAccessibleName("Theme change behavior");

    JPanel choices = new JPanel(new GridBagLayout());
    choices.getAccessibleContext().setAccessibleName("Available themes");
    ButtonGroup group = new ButtonGroup();
    Map<AppTheme, JRadioButton> items = new EnumMap<>(AppTheme.class);
    AppTheme[] accepted = {active};

    int row = 0;
    for (AppTheme theme : AppTheme.values()) {
      JRadioButton choice = new JRadioButton(theme.displayName());
      choice.setActionCommand(theme.id());
      choice.setSelected(theme == active);
      choice.setToolTipText(theme.description());
      choice.getAccessibleContext().setAccessibleDescription(theme.description());
      choice.addActionListener(
          event -> {
            if (onSelected.test(theme)) {
              accepted[0] = theme;
            } else {
              // Swing selects a radio button before notifying its listener.
              // Restore the last applied choice when installation fails.
              items.get(accepted[0]).setSelected(true);
            }
          });
      group.add(choice);
      items.put(theme, choice);

      JTextArea description = WrappingLabel.create(theme.description());
      description.setToolTipText(theme.description());

      GridBagConstraints choiceConstraints = new GridBagConstraints();
      choiceConstraints.gridx = 0;
      choiceConstraints.gridy = row;
      choiceConstraints.anchor = GridBagConstraints.NORTHWEST;
      choiceConstraints.insets = new Insets(2, 0, 7, 10);
      choices.add(choice, choiceConstraints);

      GridBagConstraints descriptionConstraints = new GridBagConstraints();
      descriptionConstraints.gridx = 1;
      descriptionConstraints.gridy = row;
      descriptionConstraints.weightx = 1.0;
      descriptionConstraints.fill = GridBagConstraints.HORIZONTAL;
      descriptionConstraints.anchor = GridBagConstraints.NORTHWEST;
      descriptionConstraints.insets = new Insets(5, 0, 7, 0);
      choices.add(description, descriptionConstraints);
      row++;
    }

    GridBagConstraints verticalFiller = new GridBagConstraints();
    verticalFiller.gridx = 0;
    verticalFiller.gridy = row;
    verticalFiller.gridwidth = 2;
    verticalFiller.weighty = 1.0;
    verticalFiller.fill = GridBagConstraints.VERTICAL;
    choices.add(new JPanel(), verticalFiller);

    JPanel content = new JPanel(new BorderLayout(0, 10));
    content.setBorder(BorderFactory.createEmptyBorder(8, 10, 10, 10));
    content.add(changeNote, BorderLayout.NORTH);
    content.add(choices, BorderLayout.CENTER);

    SectionPane themeSection = new SectionPane("Theme", content);
    themeSection.getAccessibleContext().setAccessibleDescription(CHANGE_NOTE);
    add(themeSection, BorderLayout.CENTER);
  }
}
