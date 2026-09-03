package com.holtherndon.bazelviz.ui.files;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.GraphicsEnvironment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The editor's visible editing, highlighting, and read-only states. */
final class FileEditorPanelTest {

  @BeforeAll
  static void requireHeadless() {
    assertThat(GraphicsEnvironment.isHeadless()).isTrue();
  }

  @Test
  @DisplayName("a BUILD file loads editable with Python highlighting and saves off the EDT")
  void buildFileEditsAndSaves(@TempDir Path directory) throws Exception {
    Path file = Files.writeString(directory.resolve("BUILD.bazel"), "java_library(name='a')\n");
    FileEditorPanel panel =
        onEdt(
            () ->
                new FileEditorPanel(
                    file, true, command -> new Thread(command, "test-file-worker").start()));
    await(() -> onEdt(() -> panel.editorForTest().isEditable()));

    assertThat(onEdt(() -> panel.editorForTest().getSyntaxEditingStyle()))
        .isEqualTo(SyntaxConstants.SYNTAX_STYLE_PYTHON);
    assertThat(onEdt(() -> panel.editorForTest().getText())).isEqualTo("java_library(name='a')\n");
    onEdt(
        () -> {
          panel.editorForTest().setText("java_library(name='b')\n");
          panel.saveForTest().doClick();
          return null;
        });
    await(() -> Files.readString(file).contains("name='b'"));
    await(() -> onEdt(() -> panel.statusForTest().contains("saved")));
    assertThat(onEdt(panel::isDirty)).isFalse();
    panel.close();
  }

  @Test
  @DisplayName("test logs load read-only and do not offer a Save control")
  void logsAreReadOnly(@TempDir Path directory) throws Exception {
    Path file = Files.writeString(directory.resolve("test.log"), "failure details\n");
    FileEditorPanel panel =
        onEdt(
            () ->
                new FileEditorPanel(
                    file, false, command -> new Thread(command, "test-log-worker").start()));
    await(() -> onEdt(() -> panel.statusForTest().contains("read-only")));

    assertThat(onEdt(() -> panel.editorForTest().isEditable())).isFalse();
    assertThat(onEdt(() -> panel.saveForTest().isVisible())).isFalse();
    assertThat(onEdt(() -> panel.editorForTest().getText())).contains("failure details");
    panel.close();
  }

  @Test
  @DisplayName("a source hint selects its one-based line after asynchronous loading")
  void sourceLineIsRevealedAfterLoad(@TempDir Path directory) throws Exception {
    Path file =
        Files.writeString(directory.resolve("rules.bzl"), "first = 1\nsecond = 2\nthird = 3\n");
    FileEditorPanel panel =
        onEdt(
            () -> {
              FileEditorPanel created =
                  new FileEditorPanel(
                      file, false, command -> new Thread(command, "test-line-worker").start());
              created.revealLine(3);
              return created;
            });
    await(() -> onEdt(() -> panel.statusForTest().contains("read-only")));

    assertThat(onEdt(() -> panel.editorForTest().getCaretLineNumber())).isEqualTo(2);

    onEdt(
        () -> {
          panel.revealLine(2);
          return null;
        });
    assertThat(onEdt(() -> panel.editorForTest().getCaretLineNumber())).isEqualTo(1);
    panel.close();
  }

  @Test
  @DisplayName("the shared file types preserve every editor syntax family")
  void sourceLanguagesAreRecognized() {
    Map<String, String> cases =
        Map.ofEntries(
            Map.entry("BUILD.bazel", SyntaxConstants.SYNTAX_STYLE_PYTHON),
            Map.entry("settings.bazelrc", SyntaxConstants.SYNTAX_STYLE_PYTHON),
            Map.entry("Makefile.dev", SyntaxConstants.SYNTAX_STYLE_MAKEFILE),
            Map.entry("Dockerfile.dev", SyntaxConstants.SYNTAX_STYLE_DOCKERFILE),
            Map.entry("data.jsonl", SyntaxConstants.SYNTAX_STYLE_JSON),
            Map.entry("pom.xml", SyntaxConstants.SYNTAX_STYLE_XML),
            Map.entry("Main.java", SyntaxConstants.SYNTAX_STYLE_JAVA),
            Map.entry("tool.py", SyntaxConstants.SYNTAX_STYLE_PYTHON),
            Map.entry("release.sh", SyntaxConstants.SYNTAX_STYLE_UNIX_SHELL),
            Map.entry("native.c", SyntaxConstants.SYNTAX_STYLE_C),
            Map.entry("worker.cc", SyntaxConstants.SYNTAX_STYLE_CPLUSPLUS),
            Map.entry("Program.cs", SyntaxConstants.SYNTAX_STYLE_CSHARP),
            Map.entry("theme.css", SyntaxConstants.SYNTAX_STYLE_CSS),
            Map.entry("index.html", SyntaxConstants.SYNTAX_STYLE_HTML),
            Map.entry("worker.js", SyntaxConstants.SYNTAX_STYLE_JAVASCRIPT),
            Map.entry("client.ts", SyntaxConstants.SYNTAX_STYLE_TYPESCRIPT),
            Map.entry("Main.kt", SyntaxConstants.SYNTAX_STYLE_KOTLIN),
            Map.entry("server.go", SyntaxConstants.SYNTAX_STYLE_GO),
            Map.entry("lib.rs", SyntaxConstants.SYNTAX_STYLE_RUST),
            Map.entry("task.rb", SyntaxConstants.SYNTAX_STYLE_RUBY),
            Map.entry("schema.sql", SyntaxConstants.SYNTAX_STYLE_SQL),
            Map.entry("service.proto", SyntaxConstants.SYNTAX_STYLE_PROTO),
            Map.entry("workflow.yaml", SyntaxConstants.SYNTAX_STYLE_YAML),
            Map.entry("README.md", SyntaxConstants.SYNTAX_STYLE_MARKDOWN),
            Map.entry("app.properties", SyntaxConstants.SYNTAX_STYLE_PROPERTIES_FILE));

    cases.forEach(
        (name, expected) ->
            assertThat(FileEditorPanel.syntaxFor(Path.of(name))).as(name).isEqualTo(expected));
    assertThat(FileEditorPanel.syntaxFor(Path.of("artifact.bin")))
        .isEqualTo(SyntaxConstants.SYNTAX_STYLE_NONE);
    assertThat(FileEditorPanel.syntaxFor(Path.of("image.png")))
        .isEqualTo(SyntaxConstants.SYNTAX_STYLE_NONE);
  }

  @Test
  @DisplayName("a binary file leaves a visible explanation instead of rendering bytes")
  void binaryFailureIsVisible(@TempDir Path directory) throws Exception {
    Path file = Files.write(directory.resolve("artifact.bin"), new byte[] {1, 0, 2});
    FileEditorPanel panel =
        onEdt(
            () ->
                new FileEditorPanel(
                    file, false, command -> new Thread(command, "test-binary-worker").start()));

    await(() -> onEdt(() -> panel.statusForTest().contains("binary")));

    assertThat(onEdt(() -> panel.editorForTest().getText())).isEmpty();
    assertThat(onEdt(() -> panel.statusForTest())).contains("Could not read file");
    panel.close();
  }

  private static void await(Checked condition) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (!condition.get()) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("condition never became true");
      }
      TimeUnit.MILLISECONDS.sleep(10);
    }
  }

  private static <T> T onEdt(Callable<T> work) throws Exception {
    if (SwingUtilities.isEventDispatchThread()) {
      return work.call();
    }
    AtomicReference<T> value = new AtomicReference<>();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    SwingUtilities.invokeAndWait(
        () -> {
          try {
            value.set(work.call());
          } catch (Throwable caught) {
            failure.set(caught);
          }
        });
    if (failure.get() != null) {
      throw new AssertionError(failure.get());
    }
    return value.get();
  }

  @FunctionalInterface
  private interface Checked {
    boolean get() throws Exception;
  }
}
