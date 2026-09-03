package com.holtherndon.bazelviz.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Keeps reviewed licenses and notices from disappearing when dependency jars are merged. */
final class ThirdPartyNoticesTest {

  private static final String PREFIX = "META-INF/third-party/";
  private static final String ICON_PREFIX = "com/holtherndon/bazelviz/ui/repository/icons/";

  private static final List<String> MATERIAL_REPOSITORY_ICONS =
      List.of(
          "audio.svg",
          "c.svg",
          "console.svg",
          "cpp.svg",
          "csharp.svg",
          "css.svg",
          "database.svg",
          "docker.svg",
          "document.svg",
          "folder-base.svg",
          "font.svg",
          "go.svg",
          "html.svg",
          "image.svg",
          "java.svg",
          "javascript.svg",
          "json.svg",
          "kotlin.svg",
          "log.svg",
          "makefile.svg",
          "markdown.svg",
          "pdf.svg",
          "proto.svg",
          "python.svg",
          "ruby.svg",
          "rust.svg",
          "settings.svg",
          "typescript.svg",
          "video.svg",
          "xml.svg",
          "yaml.svg",
          "zip.svg");

  private static final String MATERIAL_REPOSITORY_ICONS_SHA256 =
      "4647f53dbe8c59d9f81793dcc66f7f318d760bc0119700f0858af8a819a4007a";

  private static final Map<String, String> PROJECT_REPOSITORY_ICONS =
      Map.of(
          "bazel.svg",
          "d09a2de7f32654ef79ab238df45df78678c45ef6693731da7ff49e3a3c134876",
          "bazel-folder.svg",
          "97998954b78fcca45a8ad0389fc0b876f15a62c7782c0dc94070e4ede7f4e4ce");

  private static final Map<String, String> UPSTREAM_LEGAL_FILES =
      Map.ofEntries(
          Map.entry(
              "MATERIAL-ICON-THEME-MIT.txt",
              "cdab3014d4f69b49dde2b85e81792208c72de613aa6aed7f7a9b5c6609b89670"),
          Map.entry(
              "FLATLAF-APACHE-2.0.txt",
              "c71d239df91726fc519c6eb72d318ec65820627232b2f796219e87dcf35d0ab4"),
          Map.entry(
              "JSVG-MIT.txt", "4ef80d54216cb7a7063cd8b068b6abc1236d99a416841a81766234846e32f3c5"),
          Map.entry(
              "JEDITERM-APACHE-2.0.txt",
              "770af8291f708538d8ff885a0bbc4e045cd700531741c4f99528d435c14d7f55"),
          Map.entry(
              "PTY4J-EPL-1.0.txt",
              "44277b2bec6093e4ac313afec251a4de599d24c4e768f8574d95b13a9d2d97b5"),
          Map.entry(
              "PTY4J-NOTICE.txt",
              "81c2565e0cf16cd6d3a0e4601197ff0e0a7ec8bab04d4ed85d74f48977f83015"),
          Map.entry(
              "JNA-LICENSE.txt",
              "521bb271ac56e0e29a1b1b688b94af17d00d378fc8e63478d8c8b2a7c4a229d0"),
          Map.entry(
              "JNA-APACHE-2.0.txt",
              "0d542e0c8804e39aa7f37eb00da5a762149dc682d7829451287e11b938e94594"),
          Map.entry(
              "KOTLIN-LICENSE.txt",
              "cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30"),
          Map.entry(
              "KOTLIN-NOTICE.txt",
              "0b09a83d3ef7795c7dec65e815866597c066c53898f2852a76599b8f5552941a"),
          Map.entry(
              "JETBRAINS-ANNOTATIONS-LICENSE.txt",
              "8c1e966c7855fb54027bcaf6ebe7a43abe4785791e8cf9148c363761d493d097"),
          Map.entry(
              "SLF4J-LICENSE.txt",
              "6fbe2eaf44b193b8a40eed9208f52848572224ad8d7672dd09418aa174847e73"),
          Map.entry(
              "WINPTY-LICENSE.txt",
              "c39e428064b4f3e4fe81a975bf0fd3b845922b431bc4d9a7ffc8bfb091981836"),
          Map.entry(
              "WINDOWS-TERMINAL-LICENSE.txt",
              "ad0cf28f3381ca9bb0bf101d127402d44c17bfa0991e1a00bff7ae6679e9dada"),
          Map.entry(
              "WINDOWS-TERMINAL-NOTICE.md",
              "158036acf1095ff84839831f4bbaa8d116015e4ae14ab3d944664e05ed12136e"));

  @Test
  @DisplayName("the deploy jar retains every reviewed license and notice")
  void deployJarCarriesReviewedLegalPayload() throws Exception {
    try (ZipFile jar = new ZipFile(deployJar().toFile())) {
      for (Map.Entry<String, String> expected : UPSTREAM_LEGAL_FILES.entrySet()) {
        ZipEntry entry = jar.getEntry(PREFIX + expected.getKey());
        assertThat(entry).as(expected.getKey() + " entry").isNotNull();
        byte[] bytes = jar.getInputStream(entry).readAllBytes();
        assertThat(sha256(bytes))
            .as(expected.getKey() + " exact text")
            .isEqualTo(expected.getValue());
      }

      ZipEntry summary = jar.getEntry(PREFIX + "THIRD-PARTY-NOTICES.txt");
      assertThat(summary).as("third-party attribution index").isNotNull();
      String text = new String(jar.getInputStream(summary).readAllBytes(), StandardCharsets.UTF_8);
      assertThat(text)
          .contains("Material Icon Theme 5.38.1")
          .contains("448ab3977ef83b817c2c722ce7cd5034d195b39f")
          .contains("bazel.svg and bazel-folder.svg are original project artwork")
          .contains("not part of Material Icon Theme")
          .contains("do not contain the official Bazel logo")
          .contains("FlatLaf and FlatLaf Extras 3.7.2")
          .contains("JSVG 2.1.0")
          .contains("google/pprof profile schema")
          .contains("ca85771921e4d23ebb56030bf1e488f215f26d36")
          .contains("do not imply endorsement or affiliation")
          .contains("JediTerm core and UI 3.74")
          .contains("Pty4J 0.13.8")
          .contains("Eclipse Public License 1.0")
          .contains("Windows Terminal 1.22.11141.0")
          .contains("These notices apply only to their named components");
    }
  }

  @Test
  @DisplayName("the deploy jar retains reviewed third-party and project icons")
  void deployJarCarriesRepositoryIconsAndRenderer() throws Exception {
    try (ZipFile jar = new ZipFile(deployJar().toFile())) {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      for (String name : MATERIAL_REPOSITORY_ICONS) {
        ZipEntry entry = jar.getEntry(ICON_PREFIX + name);
        assertThat(entry).as(name + " entry").isNotNull();
        byte[] bytes = jar.getInputStream(entry).readAllBytes();
        digest.update(name.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        digest.update(bytes);

        String svg = new String(bytes, StandardCharsets.UTF_8);
        assertThat(svg)
            .as(name + " has no active or external content")
            .doesNotContainPattern("(?i)<script\\b")
            .doesNotContainPattern("(?i)\\b(?:xlink:)?href\\s*=")
            .doesNotContainPattern("(?i)url\\(\\s*['\"]?(?:https?:|//)");
      }
      assertThat(HexFormat.of().formatHex(digest.digest()))
          .as("exact reviewed Material Icon Theme SVG subset")
          .isEqualTo(MATERIAL_REPOSITORY_ICONS_SHA256);

      for (Map.Entry<String, String> expected : PROJECT_REPOSITORY_ICONS.entrySet()) {
        ZipEntry projectIcon = jar.getEntry(ICON_PREFIX + expected.getKey());
        assertThat(projectIcon).as(expected.getKey() + " entry").isNotNull();
        byte[] projectBytes = jar.getInputStream(projectIcon).readAllBytes();
        assertThat(sha256(projectBytes))
            .as("exact project SVG " + expected.getKey())
            .isEqualTo(expected.getValue());
        assertThat(new String(projectBytes, StandardCharsets.UTF_8))
            .as(expected.getKey() + " has no active or external content")
            .doesNotContainPattern("(?i)<script\\b")
            .doesNotContainPattern("(?i)\\b(?:xlink:)?href\\s*=")
            .doesNotContainPattern("(?i)url\\(\\s*['\"]?(?:https?:|//)");
      }

      assertThat(jar.getEntry("com/formdev/flatlaf/extras/FlatSVGIcon.class"))
          .as("FlatLaf SVG adapter")
          .isNotNull();
      assertThat(jar.getEntry("com/github/weisj/jsvg/SVGDocument.class"))
          .as("JSVG renderer")
          .isNotNull();
    }
  }

  private static Path deployJar() {
    return Path.of(System.getenv("TEST_SRCDIR"), "_main", "app", "app_deploy.jar");
  }

  private static String sha256(byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }
}
