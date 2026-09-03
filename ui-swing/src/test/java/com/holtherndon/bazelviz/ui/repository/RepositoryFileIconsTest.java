package com.holtherndon.bazelviz.ui.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.runner.files.FileMetadata;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.Icon;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Packaged SVG integrity and cached filename mapping. */
final class RepositoryFileIconsTest {

  @Test
  @DisplayName("SVG resource loading is refused on the Swing event thread")
  void loadingIsKeptOffTheEdt() throws Exception {
    AtomicReference<Throwable> failure = new AtomicReference<>();
    SwingUtilities.invokeAndWait(
        () -> {
          try {
            RepositoryFileIcons.load();
          } catch (Throwable thrown) {
            failure.set(thrown);
          }
        });

    assertThat(failure.get())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("off the EDT");
  }

  @Test
  @DisplayName("every reviewed SVG loads once at 16 pixels and paints headlessly")
  void allIconsLoadAndPaint() throws Exception {
    RepositoryFileIcons icons = RepositoryFileIcons.load();

    assertThat(icons.isComplete()).isTrue();
    assertThat(icons.allIconsForTest()).hasSize(34);
    for (Icon icon : icons.allIconsForTest()) {
      assertThat(icon.getIconWidth()).isEqualTo(RepositoryFileIcons.ICON_SIZE);
      assertThat(icon.getIconHeight()).isEqualTo(RepositoryFileIcons.ICON_SIZE);
      BufferedImage image =
          new BufferedImage(
              RepositoryFileIcons.ICON_SIZE,
              RepositoryFileIcons.ICON_SIZE,
              BufferedImage.TYPE_INT_ARGB);
      SwingUtilities.invokeAndWait(
          () -> {
            Graphics2D graphics = image.createGraphics();
            try {
              icon.paintIcon(new JLabel(), graphics, 0, 0);
            } finally {
              graphics.dispose();
            }
          });
      int paintedPixels = 0;
      for (int y = 0; y < image.getHeight(); y++) {
        for (int x = 0; x < image.getWidth(); x++) {
          if ((image.getRGB(x, y) >>> 24) != 0) {
            paintedPixels++;
          }
        }
      }
      assertThat(paintedPixels).isGreaterThan(0);
    }
  }

  @Test
  @DisplayName("directories, languages, Bazel files, and unknown text use cached families")
  void mapsMetadataAndNamesToCachedFamilies() {
    RepositoryFileIcons icons = RepositoryFileIcons.load();

    Icon folder = icons.iconFor(FileMetadata.Kind.DIRECTORY, "src");
    Icon java = icons.iconFor(FileMetadata.Kind.REGULAR_FILE, "Thing.java");
    Icon secondJava = icons.iconFor(FileMetadata.Kind.REGULAR_FILE, "Other.JAVA");
    Icon python = icons.iconFor(FileMetadata.Kind.REGULAR_FILE, "tool.py");
    Icon bazel = icons.iconFor(FileMetadata.Kind.REGULAR_FILE, "BUILD.bazel");
    Icon starlark = icons.iconFor(FileMetadata.Kind.REGULAR_FILE, "defs.bzl");
    Icon settings = icons.iconFor(FileMetadata.Kind.REGULAR_FILE, "gradle.properties");
    Icon bazelFolder = icons.iconFor(FileMetadata.Kind.SYMBOLIC_LINK, "bazel-out", true);
    Icon workspaceFolder =
        icons.iconFor(FileMetadata.Kind.SYMBOLIC_LINK, "bazel-my_workspace", true);
    Icon text = icons.iconFor(FileMetadata.Kind.REGULAR_FILE, "notes.unknown");
    Icon link = icons.iconFor(FileMetadata.Kind.SYMBOLIC_LINK, "Thing.java");

    assertThat(folder).isNotNull();
    assertThat(java).isSameAs(secondJava);
    assertThat(java).isNotSameAs(python).isNotSameAs(bazel).isNotSameAs(text);
    assertThat(bazel).isSameAs(starlark).isNotSameAs(settings);
    assertThat(bazelFolder).isSameAs(workspaceFolder).isNotSameAs(folder).isNotSameAs(bazel);
    assertThat(link).isSameAs(text);
  }
}
