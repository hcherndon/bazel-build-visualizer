package com.holtherndon.bazelviz.ui.starlark;

import com.holtherndon.bazelviz.ui.files.FileEditorManager;
import com.holtherndon.bazelviz.ui.session.PprofSource;
import com.holtherndon.bazelviz.ui.theme.PageToolbar;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.Path;
import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.KeyStroke;

/** Independent, non-modal profile window with no build-session or workspace dependencies. */
public final class PprofWindow {
  private PprofWindow() {}

  public static void open(Component parent, Path file) {
    ProfileView view = new ProfileView();
    JFrame frame = new JFrame("pprof · " + file.getFileName());
    FileEditorManager editors = new FileEditorManager(frame);
    view.onOpenSource(
        source -> {
          JFileChooser chooser = new JFileChooser();
          chooser.setDialogTitle("Locate source: " + source.path());
          if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
            editors.openLocal(chooser.getSelectedFile().toPath());
          }
        });
    frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
    JPanel content = new JPanel(new BorderLayout());
    PageToolbar toolbar = new PageToolbar("pprof");
    toolbar.setWorkspaceName(file.getFileName().toString());
    view.installPageToolbar(toolbar);
    content.add(toolbar, BorderLayout.NORTH);
    content.add(view, BorderLayout.CENTER);
    frame.setContentPane(content);
    frame.setSize(1100, 760);
    frame.setLocationRelativeTo(parent);
    frame.addWindowListener(
        new WindowAdapter() {
          @Override
          public void windowClosed(WindowEvent event) {
            view.closeSessionAsync();
            editors.close();
          }
        });
    frame
        .getRootPane()
        .getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
        .put(
            KeyStroke.getKeyStroke(
                KeyEvent.VK_W, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()),
            "closeProfile");
    frame
        .getRootPane()
        .getActionMap()
        .put(
            "closeProfile",
            new AbstractAction() {
              @Override
              public void actionPerformed(ActionEvent event) {
                frame.dispose();
              }
            });
    view.openProfile(() -> PprofSource.open(file));
    frame.setVisible(true);
  }
}
