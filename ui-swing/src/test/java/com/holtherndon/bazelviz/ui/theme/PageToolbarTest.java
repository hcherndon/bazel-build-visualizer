package com.holtherndon.bazelviz.ui.theme;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Component;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.plaf.basic.BasicHTML;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Plain-text, accessibility, and narrow-window contracts for shared page chrome. */
final class PageToolbarTest {

  @BeforeAll
  static void requireHeadless() {
    assertThat(GraphicsEnvironment.isHeadless()).isTrue();
  }

  @Test
  @DisplayName("blank identity segments disappear without leaving separators")
  void optionalSegmentsCollapse() throws Exception {
    PageToolbar toolbar = onEdt(() -> new PageToolbar("Overview"));

    assertThat(onEdt(() -> visibleSeparatorCount(toolbar))).isZero();
    onEdt(
        () -> {
          toolbar.setWorkspaceName("Workspace One");
          assertThat(visibleSeparatorCount(toolbar)).isOne();
          toolbar.setMetadata("151 targets");
          assertThat(visibleSeparatorCount(toolbar)).isEqualTo(2);
          toolbar.setWorkspaceName(" ");
          assertThat(visibleSeparatorCount(toolbar)).isOne();
          toolbar.setMetadata("");
          assertThat(visibleSeparatorCount(toolbar)).isZero();
          return null;
        });
  }

  @Test
  @DisplayName("workspace and metadata stay literal, selectable, and fully accessible")
  void dynamicTextIsSelectableAndAccessible() throws Exception {
    String hostile = "<html><img src='https://example.invalid/pixel'>workspace";
    String fullMetadata = "151 targets; imported coverage is incomplete";
    PageToolbar toolbar =
        onEdt(
            () -> {
              PageToolbar created = new PageToolbar("Targets");
              created.setWorkspaceName(hostile);
              created.setMetadata("151 targets", fullMetadata);
              return created;
            });

    onEdt(
        () -> {
          assertThat(toolbar.workspaceForTest().getText()).isEqualTo(hostile);
          toolbar.workspaceForTest().selectAll();
          assertThat(toolbar.workspaceForTest().getSelectedText()).isEqualTo(hostile);
          assertThat(toolbar.workspaceForTest().isEditable()).isFalse();
          assertThat(toolbar.workspaceForTest().isFocusable()).isTrue();
          assertThat(BasicHTML.isHTMLString(toolbar.workspaceForTest().getToolTipText())).isFalse();
          assertThat(toolbar.workspaceForTest().getAccessibleContext().getAccessibleName())
              .isEqualTo("Workspace");
          assertThat(toolbar.workspaceForTest().getAccessibleContext().getAccessibleDescription())
              .isEqualTo(hostile);
          assertThat(toolbar.metadataForTest().getAccessibleContext().getAccessibleDescription())
              .isEqualTo(fullMetadata);
          assertThat(toolbar.metadataForTest().getToolTipText()).isEqualTo(fullMetadata);
          JLabel title = (JLabel) toolbar.headingForTest().getComponent(0);
          assertThat(toolbar.workspaceForTest().getFont()).isEqualTo(title.getFont());
          assertThat(toolbar.workspaceForTest().getForeground()).isEqualTo(title.getForeground());
          assertThat(toolbar.metadataForTest().getForeground()).isEqualTo(title.getForeground());
          return null;
        });
  }

  @Test
  @DisplayName("title is bold plain text and page actions wrap within a narrow window")
  void titleAndActionsAreResponsive() throws Exception {
    PageToolbar toolbar =
        onEdt(
            () -> {
              PageToolbar created = new PageToolbar("<html>Literal page");
              created.setWorkspaceName("A workspace with a deliberately long display name");
              created.setMetadata("A concise but nontrivial piece of page metadata");
              for (int index = 0; index < 6; index++) {
                created.addAction(new JButton("Page action " + index));
              }
              created.headingForTest().setSize(720, 200);
              created.headingForTest().doLayout();
              return created;
            });

    onEdt(
        () -> {
          JLabel title = (JLabel) toolbar.headingForTest().getComponent(0);
          BasicHTML.updateRenderer(title, title.getText());
          assertThat(title.getClientProperty(BasicHTML.propertyKey)).isNull();
          assertThat(title.getFont().getStyle() & Font.BOLD).isNotZero();
          assertThat(toolbar.headingForTest().getPreferredSize().width).isLessThanOrEqualTo(720);
          assertThat(
                  Arrays.stream(toolbar.headingForTest().getComponents())
                      .filter(Component::isVisible)
                      .mapToInt(Component::getY)
                      .distinct()
                      .count())
              .isGreaterThan(1);
          return null;
        });
  }

  @Test
  @DisplayName("the secondary control slot occupies no space until a page uses it")
  void controlSlotCollapses() throws Exception {
    PageToolbar toolbar = onEdt(() -> new PageToolbar("Console"));
    JPanel controls = new JPanel();

    onEdt(
        () -> {
          assertThat(toolbar.controlsForTest().isVisible()).isFalse();
          toolbar.setControls(controls);
          assertThat(toolbar.controlsForTest().isVisible()).isTrue();
          assertThat(controls.getParent()).isSameAs(toolbar.controlsForTest());
          toolbar.setControls(null);
          assertThat(toolbar.controlsForTest().isVisible()).isFalse();
          return null;
        });
  }

  private static int visibleSeparatorCount(PageToolbar toolbar) {
    return (int)
        Arrays.stream(toolbar.headingForTest().getComponents())
            .filter(JLabel.class::isInstance)
            .map(JLabel.class::cast)
            .filter(label -> label.getText().equals("·"))
            .filter(Component::isVisible)
            .count();
  }

  private static <T> T onEdt(Callable<T> work) throws Exception {
    AtomicReference<T> value = new AtomicReference<>();
    AtomicReference<Exception> failure = new AtomicReference<>();
    SwingUtilities.invokeAndWait(
        () -> {
          try {
            value.set(work.call());
          } catch (Exception e) {
            failure.set(e);
          }
        });
    if (failure.get() != null) {
      throw failure.get();
    }
    return value.get();
  }
}
