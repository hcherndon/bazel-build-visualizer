package com.holtherndon.bazelviz.ui.events;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.core.event.DecodeStatus;
import com.holtherndon.bazelviz.core.journal.JournalFormat.SourceKind;
import com.holtherndon.bazelviz.runner.files.ExecutionPath;
import com.holtherndon.bazelviz.runner.files.FileMetadata;
import com.holtherndon.bazelviz.storage.events.RawLocation;
import com.holtherndon.bazelviz.ui.session.RawPayload;
import com.holtherndon.bazelviz.ui.theme.HyperlinkLabel;
import com.holtherndon.bazelviz.ui.theme.Themes;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rsyntaxtextarea.Token;
import org.fife.ui.rsyntaxtextarea.TokenTypes;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The selected Event's decoded Protocol Buffer text surface. */
class EventInspectorPanelTest {

  @BeforeAll
  static void requireHeadless() {
    assertThat(GraphicsEnvironment.isHeadless()).isTrue();
  }

  @Test
  @DisplayName("decoded event text is read-only and tokenized as Protocol Buffer text")
  void decodedTextIsHighlighted() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          EventInspectorPanel panel = new EventInspectorPanel();
          RSyntaxTextArea decoded = panel.decodedForTest();

          assertThat(decoded.isEditable()).isFalse();
          String protobufText =
              "id {\n"
                  + "  target_completed {\n"
                  + "    label: \"//pkg:test\"\n"
                  + "  }\n"
                  + "}\n"
                  + "sequence: 7\n";
          panel.show(loaded(SourceKind.BEP_BINARY, protobufText));
          assertThat(decoded.getSyntaxEditingStyle()).isEqualTo(SyntaxConstants.SYNTAX_STYLE_PROTO);
          assertThat(decoded.getText()).isEqualTo(protobufText);
          assertThat(tokenTypes(decoded.getTokenListForLine(2)))
              .contains(TokenTypes.LITERAL_STRING_DOUBLE_QUOTE);
          assertThat(tokenTypes(decoded.getTokenListForLine(5)))
              .contains(TokenTypes.LITERAL_NUMBER_DECIMAL_INT);

          String json = "{\"sequence\": 7}";
          panel.show(loaded(SourceKind.BEP_JSON_RECORD, json));
          assertThat(decoded.getSyntaxEditingStyle()).isEqualTo(SyntaxConstants.SYNTAX_STYLE_JSON);
          assertThat(decoded.getText()).isEqualTo(json);

          String failure = "<html><img src='https://example.invalid/event.png'>not decoded";
          panel.show(EventInspection.failed(9, failure));
          assertThat(decoded.getSyntaxEditingStyle()).isEqualTo(SyntaxConstants.SYNTAX_STYLE_NONE);
          assertThat(decoded.getText()).isEqualTo(failure);
        });
  }

  @Test
  @DisplayName("event metadata values are read-only text that can be selected and copied")
  void metadataIsSelectable() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          EventInspectorPanel panel = new EventInspectorPanel();
          panel.show(loaded(SourceKind.BEP_BINARY, "id {}"));

          List<JTextField> values = componentsOf(panel, JTextField.class);
          assertThat(values).hasSize(6);
          assertThat(values)
              .allSatisfy(
                  value -> {
                    assertThat(value.isEditable()).isFalse();
                    assertThat(value.isFocusable()).isTrue();
                    value.selectAll();
                    assertThat(value.getSelectedText()).isEqualTo(value.getText());
                  });
        });
  }

  @Test
  @DisplayName("decoded syntax and line numbers remain readable in the dark theme")
  void decodedTextMatchesTheDarkTheme() throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          Themes.installDark();
          try {
            EventInspectorPanel panel = new EventInspectorPanel();
            RSyntaxTextArea decoded = panel.decodedForTest();
            Color background = UIManager.getColor("TextArea.background");

            assertThat(decoded.getBackground()).isEqualTo(background);
            assertThat(panel.decodedScrollForTest().getLineNumbersEnabled()).isTrue();
            assertThat(panel.decodedScrollForTest().getGutter().getBackground())
                .isEqualTo(background);
            assertThat(
                    contrast(
                        decoded
                            .getSyntaxScheme()
                            .getStyle(TokenTypes.LITERAL_STRING_DOUBLE_QUOTE)
                            .foreground,
                        background))
                .isGreaterThanOrEqualTo(4.5);
            assertThat(
                    contrast(
                        decoded
                            .getSyntaxScheme()
                            .getStyle(TokenTypes.LITERAL_NUMBER_DECIMAL_INT)
                            .foreground,
                        background))
                .isGreaterThanOrEqualTo(4.5);
          } finally {
            Themes.installDefault();
          }
        });
  }

  @Test
  @DisplayName("the Files tab requests metadata only when it is visited")
  void filesTabIsLazy() throws Exception {
    AtomicInteger requests = new AtomicInteger();
    SwingUtilities.invokeAndWait(
        () -> {
          EventInspectorPanel panel = new EventInspectorPanel();
          panel.onFilesRequested(requests::incrementAndGet);
          panel.show(loaded(SourceKind.BEP_BINARY, "named_set_of_files {}"));

          assertThat(requests).hasValue(0);
          assertThat(panel.panesForTest().getTitleAt(2)).isEqualTo("Files");
          panel.panesForTest().setSelectedIndex(2);
          assertThat(requests).hasValue(1);
        });
  }

  @Test
  @DisplayName("a local event file offers open, copy-path and reveal links")
  void fileActionsAreLinks() throws Exception {
    AtomicReference<Path> opened = new AtomicReference<>();
    AtomicReference<String> copied = new AtomicReference<>();
    AtomicReference<Path> revealed = new AtomicReference<>();
    Path local = Path.of("/tmp/bbv-event-file.log");
    SwingUtilities.invokeAndWait(
        () -> {
          EventInspectorPanel panel = new EventInspectorPanel();
          panel.onOpenFile(opened::set);
          panel.onCopyFilePath(copied::set);
          panel.onRevealFile(revealed::set);
          panel.showFiles(
              EventFileInspection.loaded(
                  1,
                  List.of(
                      new EventFileInspection.FileEntry(
                          "Test output",
                          "test.log",
                          "file:///tmp/bbv-event-file.log",
                          "URI",
                          OptionalLong.of(8),
                          Optional.of("abc"),
                          Optional.of(local),
                          true,
                          false,
                          OptionalLong.of(8),
                          Optional.of("2026-08-26 10:00:00 CDT"))),
                  1,
                  Optional.empty()));

          List<HyperlinkLabel> links = componentsOf(panel.filesForTest(), HyperlinkLabel.class);
          assertThat(links)
              .extracting(JTextArea::getText)
              .containsExactly("Open File", "Copy path", "Reveal in Finder");
          links.forEach(HyperlinkLabel::activate);
        });
    assertThat(opened).hasValue(local);
    assertThat(copied).hasValue(local.toString());
    assertThat(revealed).hasValue(local);
  }

  @Test
  @DisplayName("a remote event file opens by execution path without offering Finder")
  void remoteFileActionUsesLogicalPath() throws Exception {
    ExecutionPath remote = new ExecutionPath("ssh:builder", "/work/repo/test.log");
    AtomicReference<ExecutionPath> opened = new AtomicReference<>();
    SwingUtilities.invokeAndWait(
        () -> {
          EventInspectorPanel panel = new EventInspectorPanel();
          panel.onOpenExecutionFile(opened::set);
          panel.showFiles(
              EventFileInspection.loaded(
                  1,
                  List.of(
                      new EventFileInspection.FileEntry(
                          "Test output",
                          "test.log",
                          "file:///work/repo/test.log",
                          "URI",
                          OptionalLong.of(8),
                          Optional.of("abc"),
                          Optional.empty(),
                          true,
                          false,
                          OptionalLong.of(8),
                          Optional.empty(),
                          Optional.of(remote),
                          FileMetadata.State.PRESENT,
                          FileMetadata.Kind.REGULAR_FILE,
                          Optional.empty())),
                  1,
                  Optional.empty()));

          List<HyperlinkLabel> links = componentsOf(panel.filesForTest(), HyperlinkLabel.class);
          assertThat(links)
              .extracting(JTextArea::getText)
              .containsExactly("Open File", "Copy path");
          links.getFirst().activate();
        });
    assertThat(opened).hasValue(remote);
  }

  private static EventInspection loaded(SourceKind sourceKind, String text) {
    EventRow row =
        new EventRow(
            1,
            1,
            1,
            DecodeStatus.OK,
            false,
            OptionalLong.empty(),
            Optional.empty(),
            0,
            false,
            new RawLocation(0, 0, 1),
            OptionalLong.empty(),
            1);
    return EventInspection.loaded(
        row,
        new RawPayload(new byte[] {1}, sourceKind),
        new RawPayloadRenderer.Rendered(text, Optional.empty(), List.of()),
        "01");
  }

  private static List<Integer> tokenTypes(Token token) {
    List<Integer> types = new ArrayList<>();
    for (Token current = token;
        current != null && current.isPaintable();
        current = current.getNextToken()) {
      types.add(current.getType());
    }
    return types;
  }

  private static <T extends Component> List<T> componentsOf(Container root, Class<T> type) {
    List<T> found = new ArrayList<>();
    for (Component child : root.getComponents()) {
      if (type.isInstance(child)) {
        found.add(type.cast(child));
      }
      if (child instanceof Container nested) {
        found.addAll(componentsOf(nested, type));
      }
    }
    return found;
  }

  /** WCAG contrast ratio, used here only to prevent dark-on-dark token palettes. */
  private static double contrast(Color first, Color second) {
    double lighter = Math.max(luminance(first), luminance(second));
    double darker = Math.min(luminance(first), luminance(second));
    return (lighter + 0.05) / (darker + 0.05);
  }

  private static double luminance(Color colour) {
    double red = linear(colour.getRed() / 255.0);
    double green = linear(colour.getGreen() / 255.0);
    double blue = linear(colour.getBlue() / 255.0);
    return 0.2126 * red + 0.7152 * green + 0.0722 * blue;
  }

  private static double linear(double channel) {
    return channel <= 0.04045 ? channel / 12.92 : Math.pow((channel + 0.055) / 1.055, 2.4);
  }
}
