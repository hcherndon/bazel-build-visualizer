package com.holtherndon.bazelviz.ui.query;

import com.holtherndon.bazelviz.ui.theme.PlainText;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;

/**
 * The saved half of the Query card: saved queries and saved views, as lists.
 *
 * <p>Everything here is a thin veneer over {@link QueryView.PanelHost} — the shell owns the {@link
 * QueryLibrary} and the I/O thread, this panel owns only widgets, which is what keeps it out of the
 * EDT-discipline rule's sights: it cannot block because it holds nothing that can.
 *
 * <p>Double-clicking a saved query loads it into the selected tab's editor. Double-clicking a saved
 * view inserts a {@code SELECT * FROM <view>} — the view itself already exists on every tab's
 * connection, replayed there by the shell. "Save view from editor" is deliberate about its input:
 * the editor must hold a {@code CREATE TEMP VIEW} definition, because that is the only honest
 * source for both the name and the body.
 */
public final class QueryLibraryPanel extends JPanel {

  private static final long serialVersionUID = 1L;

  private final QueryView.PanelHost host;

  private final DefaultListModel<String> queryNames = new DefaultListModel<>();
  private final DefaultListModel<String> viewNames = new DefaultListModel<>();
  private final JList<String> queryList = new JList<>(queryNames);
  private final JList<String> viewList = new JList<>(viewNames);
  private final JLabel notice = new JLabel(" ");

  /** The loaded entries, so a double-click needs no file read. */
  private List<QueryLibrary.SavedQuery> queries = List.of();

  private List<QueryLibrary.SavedView> views = List.of();

  QueryLibraryPanel(QueryView.PanelHost host) {
    super(new GridLayout(2, 1, 0, 4));
    this.host = host;

    PlainText.disableHtml(notice);
    queryList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    viewList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    queryList.addMouseListener(
        new MouseAdapter() {
          @Override
          public void mouseClicked(MouseEvent event) {
            if (event.getClickCount() == 2) {
              loadSelectedQuery();
            }
          }
        });
    viewList.addMouseListener(
        new MouseAdapter() {
          @Override
          public void mouseClicked(MouseEvent event) {
            if (event.getClickCount() == 2) {
              insertSelectedView();
            }
          }
        });

    add(
        section(
            "Saved queries",
            queryList,
            button("Save…", this::saveCurrentQuery),
            button("Load", this::loadSelectedQuery),
            button("Rename…", this::renameSelectedQuery),
            button("Delete", this::deleteSelectedQuery)));
    add(
        section(
            "Saved views",
            viewList,
            button("Save from editor", this::saveViewFromEditor),
            button("Insert", this::insertSelectedView),
            button("Rename…", this::renameSelectedView),
            button("Delete", this::deleteSelectedView)));
  }

  private JPanel section(String title, JList<String> list, JButton... buttons) {
    JPanel panel = new JPanel(new BorderLayout(0, 2));
    panel.setBorder(BorderFactory.createTitledBorder(title));
    panel.add(new JScrollPane(list), BorderLayout.CENTER);
    JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
    for (JButton button : buttons) {
      row.add(button);
    }
    JPanel south = new JPanel(new BorderLayout());
    south.add(row, BorderLayout.CENTER);
    if (title.startsWith("Saved views")) {
      south.add(notice, BorderLayout.SOUTH);
    }
    panel.add(south, BorderLayout.SOUTH);
    return panel;
  }

  private static JButton button(String label, Runnable action) {
    JButton button = new JButton(label);
    button.addActionListener(event -> action.run());
    return button;
  }

  // -------------------------------------------------------------- queries

  private void saveCurrentQuery() {
    String sql = host.currentEditorSql();
    if (sql.isBlank()) {
      showProblem("There is nothing in the editor to save.");
      return;
    }
    String name = prompt("Save the editor's SQL as:", "Save query", "");
    if (name == null || name.isBlank()) {
      return;
    }
    host.saveQuery(name.strip(), sql, this::showProblem);
    showNotice("Saved \"" + name.strip() + "\".");
  }

  private void loadSelectedQuery() {
    QueryLibrary.SavedQuery selected = selectedQuery();
    if (selected != null) {
      host.loadIntoEditor(selected.sql());
      showNotice(" ");
    }
  }

  private void renameSelectedQuery() {
    QueryLibrary.SavedQuery selected = selectedQuery();
    if (selected == null) {
      return;
    }
    String name = prompt("New name:", "Rename query", selected.name());
    if (name == null || name.isBlank() || name.strip().equals(selected.name())) {
      return;
    }
    host.renameQuery(selected.name(), name.strip(), this::showProblem);
  }

  private void deleteSelectedQuery() {
    QueryLibrary.SavedQuery selected = selectedQuery();
    if (selected != null) {
      host.deleteQuery(selected.name(), this::showProblem);
    }
  }

  private QueryLibrary.SavedQuery selectedQuery() {
    int index = queryList.getSelectedIndex();
    return index >= 0 && index < queries.size() ? queries.get(index) : null;
  }

  // ---------------------------------------------------------------- views

  private void saveViewFromEditor() {
    host.saveViewFromEditor(this::showProblem);
  }

  private void insertSelectedView() {
    QueryLibrary.SavedView selected = selectedView();
    if (selected != null) {
      host.loadIntoEditor("SELECT *\nFROM " + quoteIfNeeded(selected.name()) + "\nLIMIT 200");
    }
  }

  private void renameSelectedView() {
    QueryLibrary.SavedView selected = selectedView();
    if (selected == null) {
      return;
    }
    String name = prompt("New view name:", "Rename view", selected.name());
    if (name == null || name.isBlank() || name.strip().equals(selected.name())) {
      return;
    }
    host.renameView(selected.name(), name.strip(), this::showProblem);
  }

  private void deleteSelectedView() {
    QueryLibrary.SavedView selected = selectedView();
    if (selected != null) {
      host.deleteView(selected.name(), this::showProblem);
    }
  }

  private QueryLibrary.SavedView selectedView() {
    int index = viewList.getSelectedIndex();
    return index >= 0 && index < views.size() ? views.get(index) : null;
  }

  private static String quoteIfNeeded(String name) {
    return name.matches("[A-Za-z_][A-Za-z0-9_]*") ? name : "\"" + name.replace("\"", "\"\"") + "\"";
  }

  private String prompt(String message, String title, String initial) {
    Object answer =
        JOptionPane.showInputDialog(
            this, message, title, JOptionPane.PLAIN_MESSAGE, null, null, initial);
    return answer instanceof String text ? text : null;
  }

  // ------------------------------------------------------------- data pushes

  /** Replaces the shown queries. EDT only; pushed by the shell after I/O. */
  void showQueries(List<QueryLibrary.SavedQuery> loaded) {
    queries = List.copyOf(loaded);
    queryNames.clear();
    for (QueryLibrary.SavedQuery query : queries) {
      queryNames.addElement(query.name());
    }
  }

  /** Replaces the shown views. EDT only; pushed by the shell after I/O. */
  void showViews(List<QueryLibrary.SavedView> loaded) {
    views = List.copyOf(loaded);
    viewNames.clear();
    for (QueryLibrary.SavedView view : views) {
      viewNames.addElement(view.name());
    }
  }

  /** A problem worth reading; stays until the next action clears it. */
  void showProblem(String text) {
    notice.setEnabled(true);
    notice.setText(text);
  }

  private void showNotice(String text) {
    notice.setEnabled(false);
    notice.setText(text);
  }

  // -------------------------------------------------------- visible for tests

  /** Visible for testing: the listed query names. */
  public List<String> queryNamesForTest() {
    List<String> names = new ArrayList<>();
    for (int i = 0; i < queryNames.size(); i++) {
      names.add(queryNames.get(i));
    }
    return names;
  }

  /** Visible for testing: the listed view names. */
  public List<String> viewNamesForTest() {
    List<String> names = new ArrayList<>();
    for (int i = 0; i < viewNames.size(); i++) {
      names.add(viewNames.get(i));
    }
    return names;
  }

  /** Visible for testing: the notice line. */
  public String noticeForTest() {
    return notice.getText();
  }
}
