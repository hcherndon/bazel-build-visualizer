package com.holtherndon.bazelviz.ui.repository;

import com.formdev.flatlaf.extras.FlatSVGIcon;
import com.holtherndon.bazelviz.runner.files.FileMetadata;
import com.holtherndon.bazelviz.ui.files.FileNameType;
import java.io.IOException;
import java.io.InputStream;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import javax.swing.Icon;
import javax.swing.SwingUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** One preloaded, shared SVG icon per repository file family. */
final class RepositoryFileIcons {

  static final int ICON_SIZE = 16;

  private static final Logger log = LoggerFactory.getLogger(RepositoryFileIcons.class);
  private static final String RESOURCE_ROOT = "/com/holtherndon/bazelviz/ui/repository/icons/";

  private final Map<IconType, Icon> icons;

  private RepositoryFileIcons(Map<IconType, Icon> icons) {
    this.icons = Map.copyOf(icons);
  }

  /**
   * Reads and parses the fixed application resources once.
   *
   * <p>This must stay off the EDT. A missing or malformed packaged asset leaves that family on
   * Swing's normal tree icon and emits a diagnostic instead of breaking repository browsing.
   */
  static RepositoryFileIcons load() {
    if (SwingUtilities.isEventDispatchThread()) {
      throw new IllegalStateException("repository SVG icons must load off the EDT");
    }
    EnumMap<IconType, Icon> loaded = new EnumMap<>(IconType.class);
    for (IconType type : IconType.values()) {
      String resource = RESOURCE_ROOT + type.resourceName;
      try (InputStream input = RepositoryFileIcons.class.getResourceAsStream(resource)) {
        if (input == null) {
          log.warn("repository icon resource is missing: {}", resource);
          continue;
        }
        FlatSVGIcon source = new FlatSVGIcon(input);
        if (!source.hasFound()) {
          log.warn("repository icon resource could not be parsed: {}", resource);
          continue;
        }
        loaded.put(type, source.derive(ICON_SIZE, ICON_SIZE));
      } catch (IOException | RuntimeException failure) {
        log.warn("repository icon resource could not be loaded: {}", resource, failure);
      }
    }
    return new RepositoryFileIcons(loaded);
  }

  /** Returns a cached icon, or null so the renderer can retain its look-and-feel fallback. */
  Icon iconFor(FileMetadata.Kind kind, String fileName) {
    return iconFor(kind, fileName, false);
  }

  /** Uses the Bazel folder artwork only after the browser has recognized a root link. */
  Icon iconFor(FileMetadata.Kind kind, String fileName, boolean bazelDirectoryLink) {
    if (kind == FileMetadata.Kind.DIRECTORY) {
      return icons.get(IconType.FOLDER);
    }
    if (bazelDirectoryLink) {
      return icons.get(IconType.BAZEL_FOLDER);
    }
    if (kind != FileMetadata.Kind.REGULAR_FILE) {
      return icons.get(IconType.DOCUMENT);
    }
    return icons.get(iconType(FileNameType.classify(fileName)));
  }

  boolean isComplete() {
    return icons.size() == IconType.values().length;
  }

  List<Icon> allIconsForTest() {
    return List.copyOf(icons.values());
  }

  private static IconType iconType(FileNameType type) {
    return switch (type) {
      case BAZEL -> IconType.BAZEL;
      case PROPERTIES -> IconType.SETTINGS;
      case MAKEFILE -> IconType.MAKEFILE;
      case DOCKER -> IconType.DOCKER;
      case JSON -> IconType.JSON;
      case XML -> IconType.XML;
      case JAVA -> IconType.JAVA;
      case PYTHON -> IconType.PYTHON;
      case SHELL -> IconType.CONSOLE;
      case C -> IconType.C;
      case CPP -> IconType.CPP;
      case CSHARP -> IconType.CSHARP;
      case CSS -> IconType.CSS;
      case HTML -> IconType.HTML;
      case JAVASCRIPT -> IconType.JAVASCRIPT;
      case TYPESCRIPT -> IconType.TYPESCRIPT;
      case KOTLIN -> IconType.KOTLIN;
      case GO -> IconType.GO;
      case RUST -> IconType.RUST;
      case RUBY -> IconType.RUBY;
      case SQL -> IconType.DATABASE;
      case PROTO -> IconType.PROTO;
      case YAML -> IconType.YAML;
      case MARKDOWN -> IconType.MARKDOWN;
      case IMAGE -> IconType.IMAGE;
      case AUDIO -> IconType.AUDIO;
      case VIDEO -> IconType.VIDEO;
      case FONT -> IconType.FONT;
      case PDF -> IconType.PDF;
      case ARCHIVE -> IconType.ZIP;
      case LOG -> IconType.LOG;
      case TEXT -> IconType.DOCUMENT;
    };
  }

  private enum IconType {
    FOLDER("folder-base.svg"),
    BAZEL_FOLDER("bazel-folder.svg"),
    DOCUMENT("document.svg"),
    BAZEL("bazel.svg"),
    SETTINGS("settings.svg"),
    MAKEFILE("makefile.svg"),
    DOCKER("docker.svg"),
    JSON("json.svg"),
    XML("xml.svg"),
    JAVA("java.svg"),
    PYTHON("python.svg"),
    CONSOLE("console.svg"),
    C("c.svg"),
    CPP("cpp.svg"),
    CSHARP("csharp.svg"),
    CSS("css.svg"),
    HTML("html.svg"),
    JAVASCRIPT("javascript.svg"),
    TYPESCRIPT("typescript.svg"),
    KOTLIN("kotlin.svg"),
    GO("go.svg"),
    RUST("rust.svg"),
    RUBY("ruby.svg"),
    DATABASE("database.svg"),
    PROTO("proto.svg"),
    YAML("yaml.svg"),
    MARKDOWN("markdown.svg"),
    IMAGE("image.svg"),
    AUDIO("audio.svg"),
    VIDEO("video.svg"),
    FONT("font.svg"),
    PDF("pdf.svg"),
    ZIP("zip.svg"),
    LOG("log.svg");

    private final String resourceName;

    IconType(String resourceName) {
      this.resourceName = resourceName;
    }
  }
}
