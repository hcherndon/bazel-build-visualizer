package com.holtherndon.bazelviz.ui.events;

import com.holtherndon.bazelviz.runner.files.ExecutionPath;
import com.holtherndon.bazelviz.ui.events.EventFileInspection.FileEntry;
import com.holtherndon.bazelviz.ui.format.EventValueFormat;
import com.holtherndon.bazelviz.ui.theme.HyperlinkLabel;
import com.holtherndon.bazelviz.ui.theme.PlainText;
import com.holtherndon.bazelviz.ui.theme.WrappingLabel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import javax.swing.table.AbstractTableModel;

/** Scalable file metadata surface for the selected event. EDT only. */
final class EventFilesPanel extends JPanel {

  private static final long serialVersionUID = 1L;

  private final JTextArea status = WrappingLabel.create(" ");
  private final JTable table = new JTable();
  private final JPanel selectionActions = new JPanel(new BorderLayout(0, 4));
  private FileTableModel model = new FileTableModel(List.of());
  private Consumer<String> copyPath = ignored -> {};
  private Consumer<Path> openLocalFile;
  private Consumer<ExecutionPath> openExecutionFile;
  private Consumer<Path> revealFile = ignored -> {};

  EventFilesPanel() {
    super(new BorderLayout(0, 6));
    PlainText.install(table);
    table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
    table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    table.setFillsViewportHeight(true);
    table.setModel(model);
    table
        .getSelectionModel()
        .addListSelectionListener(
            event -> {
              if (!event.getValueIsAdjusting()) {
                updateSelectionActions();
              }
            });
    status.setBorder(BorderFactory.createEmptyBorder(4, 6, 0, 6));
    selectionActions.setBorder(BorderFactory.createEmptyBorder(2, 6, 5, 6));
    add(status, BorderLayout.NORTH);
    add(new JScrollPane(table), BorderLayout.CENTER);
    add(selectionActions, BorderLayout.SOUTH);
    show(EventFileInspection.none());
  }

  void onCopyPath(Consumer<String> handler) {
    copyPath = Objects.requireNonNull(handler, "handler");
  }

  void onRevealFile(Consumer<Path> handler) {
    revealFile = Objects.requireNonNull(handler, "handler");
  }

  void onOpenFile(Consumer<Path> handler) {
    openLocalFile = Objects.requireNonNull(handler, "handler");
  }

  void onOpenExecutionFile(Consumer<ExecutionPath> handler) {
    openExecutionFile = Objects.requireNonNull(handler, "handler");
  }

  void show(EventFileInspection inspection) {
    Objects.requireNonNull(inspection, "inspection");
    model = new FileTableModel(inspection.files());
    table.setModel(model);
    sizeColumns();
    status.setText(
        switch (inspection.state()) {
          case NONE -> "Visit this tab to read file metadata for the selected event.";
          case LOADING -> "Reading this event's files and local metadata…";
          case FAILED -> "Could not read files: " + inspection.note().orElse("unknown failure");
          case LOADED -> describeLoaded(inspection);
        });
    table.setEnabled(inspection.state() == EventFileInspection.State.LOADED);
    if (!inspection.files().isEmpty()) {
      table.setRowSelectionInterval(0, 0);
    } else {
      selectionActions.removeAll();
      selectionActions.revalidate();
      selectionActions.repaint();
    }
  }

  private static String describeLoaded(EventFileInspection inspection) {
    String count =
        inspection.totalFiles() == 1
            ? "1 file carried directly by this event."
            : inspection.totalFiles() + " files carried directly by this event.";
    return inspection.note().map(note -> count + " " + note).orElse(count);
  }

  private void updateSelectionActions() {
    selectionActions.removeAll();
    int viewRow = table.getSelectedRow();
    if (viewRow >= 0) {
      FileEntry file = model.row(table.convertRowIndexToModel(viewRow));
      JTextArea path = WrappingLabel.create(file.copyText());
      path.setToolTipText(PlainText.tooltip(file.copyText()));
      JPanel links = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
      boolean canOpenExecution = file.executionPath().isPresent() && openExecutionFile != null;
      boolean canOpenLocal = file.localPath().isPresent() && openLocalFile != null;
      if (file.openable() && (canOpenExecution || canOpenLocal)) {
        HyperlinkLabel open =
            new HyperlinkLabel(
                "Open File",
                () -> {
                  if (canOpenLocal) {
                    openLocalFile.accept(file.localPath().orElseThrow());
                  } else {
                    openExecutionFile.accept(file.executionPath().orElseThrow());
                  }
                });
        open.setName("eventFile.open");
        open.getAccessibleContext().setAccessibleName("Open file");
        links.add(open);
      }
      HyperlinkLabel copy = new HyperlinkLabel("Copy path", () -> copyPath.accept(file.copyText()));
      copy.setName("eventFile.copyPath");
      copy.getAccessibleContext().setAccessibleName("Copy file path");
      links.add(copy);
      if (file.localPath().isPresent() && file.exists()) {
        HyperlinkLabel reveal =
            new HyperlinkLabel(
                "Reveal in Finder", () -> revealFile.accept(file.localPath().orElseThrow()));
        reveal.setName("eventFile.reveal");
        reveal.getAccessibleContext().setAccessibleName("Reveal file in Finder");
        links.add(reveal);
      }
      selectionActions.add(path, BorderLayout.CENTER);
      selectionActions.add(links, BorderLayout.SOUTH);
    }
    selectionActions.revalidate();
    selectionActions.repaint();
  }

  private void sizeColumns() {
    int[] widths = {130, 360, 180, 110, 170, 110, 190, 240};
    for (int index = 0; index < Math.min(widths.length, table.getColumnCount()); index++) {
      table.getColumnModel().getColumn(index).setPreferredWidth(widths[index]);
    }
  }

  JTable tableForTest() {
    return table;
  }

  String statusForTest() {
    return status.getText();
  }

  private static final class FileTableModel extends AbstractTableModel {

    private static final long serialVersionUID = 1L;
    private static final String[] COLUMNS = {
      "Role", "Path", "Source / URI", "Kind", "Execution state", "Size", "Modified", "Digest"
    };
    private final List<FileEntry> rows;

    FileTableModel(List<FileEntry> rows) {
      this.rows = List.copyOf(rows);
    }

    FileEntry row(int index) {
      return rows.get(index);
    }

    @Override
    public int getRowCount() {
      return rows.size();
    }

    @Override
    public int getColumnCount() {
      return COLUMNS.length;
    }

    @Override
    public String getColumnName(int column) {
      return COLUMNS[column];
    }

    @Override
    public Object getValueAt(int rowIndex, int columnIndex) {
      FileEntry file = rows.get(rowIndex);
      return switch (columnIndex) {
        case 0 -> file.role();
        case 1 -> file.path();
        case 2 -> file.source();
        case 3 -> file.kind();
        case 4 -> stateText(file);
        case 5 ->
            file.actualBytes().isPresent()
                ? EventValueFormat.bytes(file.actualBytes().getAsLong()) + " actual"
                : file.declaredBytes().isPresent()
                    ? EventValueFormat.bytes(file.declaredBytes().getAsLong()) + " declared"
                    : EventValueFormat.UNKNOWN;
        case 6 -> file.modified().orElse(EventValueFormat.UNKNOWN);
        case 7 -> file.digest().orElse(EventValueFormat.UNKNOWN);
        default -> throw new IndexOutOfBoundsException(columnIndex);
      };
    }

    private static String stateText(FileEntry file) {
      if (file.executionPath().isEmpty() && file.localPath().isEmpty()) {
        return "not inspectable";
      }
      return switch (file.metadataState()) {
        case MISSING -> "missing";
        case INACCESSIBLE -> "inaccessible";
        case UNAVAILABLE -> "unavailable";
        case PRESENT ->
            switch (file.metadataKind()) {
              case DIRECTORY -> "directory";
              case SYMBOLIC_LINK -> "symbolic link";
              case REGULAR_FILE -> "present";
              case OTHER -> "other";
              case UNKNOWN -> "present (unknown type)";
            };
      };
    }
  }
}
