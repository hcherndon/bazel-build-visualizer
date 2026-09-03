package com.holtherndon.bazelviz.ui.files;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Filename-only file-type recognition used by the editor and repository tree. */
final class FileNameTypeTest {

  @Test
  @DisplayName("every syntax-aware filename family is classified without reading a file")
  void syntaxFamiliesAreRecognized() {
    Map<String, FileNameType> cases =
        Map.ofEntries(
            Map.entry("BUILD", FileNameType.BAZEL),
            Map.entry("Makefile.local", FileNameType.MAKEFILE),
            Map.entry("Dockerfile.dev", FileNameType.DOCKER),
            Map.entry("events.jsonl", FileNameType.JSON),
            Map.entry("pom.xml", FileNameType.XML),
            Map.entry("Main.java", FileNameType.JAVA),
            Map.entry("types.pyi", FileNameType.PYTHON),
            Map.entry("release.zsh", FileNameType.SHELL),
            Map.entry("native.c", FileNameType.C),
            Map.entry("native.hpp", FileNameType.CPP),
            Map.entry("Program.cs", FileNameType.CSHARP),
            Map.entry("theme.css", FileNameType.CSS),
            Map.entry("index.htm", FileNameType.HTML),
            Map.entry("worker.mjs", FileNameType.JAVASCRIPT),
            Map.entry("panel.tsx", FileNameType.TYPESCRIPT),
            Map.entry("Build.kts", FileNameType.KOTLIN),
            Map.entry("server.go", FileNameType.GO),
            Map.entry("lib.rs", FileNameType.RUST),
            Map.entry("task.rb", FileNameType.RUBY),
            Map.entry("schema.sql", FileNameType.SQL),
            Map.entry("event.proto", FileNameType.PROTO),
            Map.entry("workflow.yml", FileNameType.YAML),
            Map.entry("README.markdown", FileNameType.MARKDOWN),
            Map.entry("app.properties", FileNameType.PROPERTIES));

    cases.forEach(
        (name, expected) -> assertThat(FileNameType.classify(name)).as(name).isEqualTo(expected));
  }

  @Test
  @DisplayName("Bazel names and extensions are case-insensitive")
  void bazelNamesAreRecognizedCaseInsensitively() {
    assertThat(FileNameType.classify("bUiLd.BaZeL")).isEqualTo(FileNameType.BAZEL);
    assertThat(FileNameType.classify("MODULE.bazel")).isEqualTo(FileNameType.BAZEL);
    assertThat(FileNameType.classify("WORKSPACE")).isEqualTo(FileNameType.BAZEL);
    assertThat(FileNameType.classify("WORKSPACE.bazel")).isEqualTo(FileNameType.BAZEL);
    assertThat(FileNameType.classify(".BAZELRC")).isEqualTo(FileNameType.BAZEL);
    assertThat(FileNameType.classify("rules.BZL")).isEqualTo(FileNameType.BAZEL);
    assertThat(FileNameType.classify("settings.BAZEL")).isEqualTo(FileNameType.BAZEL);
    assertThat(FileNameType.classify("user.BAZELRC")).isEqualTo(FileNameType.BAZEL);
  }

  @Test
  @DisplayName("common non-text repository files have stable categories")
  void nonTextRepositoryFilesAreRecognized() {
    Map<String, FileNameType> cases =
        Map.of(
            "diagram.SVG", FileNameType.IMAGE,
            "recording.FLAC", FileNameType.AUDIO,
            "demo.MKV", FileNameType.VIDEO,
            "interface.WOFF2", FileNameType.FONT,
            "manual.PDF", FileNameType.PDF,
            "sources.TAR.GZ", FileNameType.ARCHIVE,
            "test.LOG", FileNameType.LOG);

    cases.forEach(
        (name, expected) -> assertThat(FileNameType.classify(name)).as(name).isEqualTo(expected));
  }

  @Test
  @DisplayName("unknown names use the text fallback")
  void unknownNamesDefaultToText() {
    assertThat(FileNameType.classify("README")).isEqualTo(FileNameType.TEXT);
    assertThat(FileNameType.classify("artifact.bin")).isEqualTo(FileNameType.TEXT);
  }
}
