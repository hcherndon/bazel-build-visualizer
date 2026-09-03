package com.holtherndon.bazelviz.ui.files;

import java.util.Locale;
import java.util.Objects;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;

/**
 * A filename-only classification shared by file viewers and repository views.
 *
 * <p>This classifier never reads a file. Unknown names deliberately use {@link #TEXT}, which gives
 * repository views a safe, predictable fallback.
 */
public enum FileNameType {
  BAZEL,
  MAKEFILE,
  DOCKER,
  JSON,
  XML,
  JAVA,
  PYTHON,
  SHELL,
  C,
  CPP,
  CSHARP,
  CSS,
  HTML,
  JAVASCRIPT,
  TYPESCRIPT,
  KOTLIN,
  GO,
  RUST,
  RUBY,
  SQL,
  PROTO,
  YAML,
  MARKDOWN,
  PROPERTIES,
  IMAGE,
  AUDIO,
  VIDEO,
  FONT,
  PDF,
  ARCHIVE,
  LOG,
  TEXT;

  /** Classifies one leaf filename without inspecting its contents. */
  public static FileNameType classify(String fileName) {
    String name = Objects.requireNonNull(fileName, "fileName").toLowerCase(Locale.ROOT);
    if (name.equals("build")
        || name.equals("build.bazel")
        || name.equals("module.bazel")
        || name.equals("workspace")
        || name.equals("workspace.bazel")
        || name.equals(".bazelrc")
        || name.endsWith(".bzl")
        || name.endsWith(".bazel")
        || name.endsWith(".bazelrc")) {
      return BAZEL;
    }
    if (name.equals("makefile") || name.startsWith("makefile.") || name.endsWith(".mk")) {
      return MAKEFILE;
    }
    if (name.equals("dockerfile") || name.startsWith("dockerfile.")) {
      return DOCKER;
    }
    if (endsWithAny(name, ".json", ".jsonl")) {
      return JSON;
    }
    if (name.endsWith(".xml")) {
      return XML;
    }
    if (name.endsWith(".java")) {
      return JAVA;
    }
    if (endsWithAny(name, ".py", ".pyi")) {
      return PYTHON;
    }
    if (endsWithAny(name, ".sh", ".bash", ".zsh")) {
      return SHELL;
    }
    if (name.endsWith(".c")) {
      return C;
    }
    if (endsWithAny(name, ".cc", ".cpp", ".cxx", ".h", ".hh", ".hpp", ".hxx")) {
      return CPP;
    }
    if (name.endsWith(".cs")) {
      return CSHARP;
    }
    if (name.endsWith(".css")) {
      return CSS;
    }
    if (endsWithAny(name, ".html", ".htm")) {
      return HTML;
    }
    if (endsWithAny(name, ".js", ".mjs", ".cjs")) {
      return JAVASCRIPT;
    }
    if (endsWithAny(name, ".ts", ".tsx")) {
      return TYPESCRIPT;
    }
    if (endsWithAny(name, ".kt", ".kts")) {
      return KOTLIN;
    }
    if (name.endsWith(".go")) {
      return GO;
    }
    if (name.endsWith(".rs")) {
      return RUST;
    }
    if (name.endsWith(".rb")) {
      return RUBY;
    }
    if (name.endsWith(".sql")) {
      return SQL;
    }
    if (name.endsWith(".proto")) {
      return PROTO;
    }
    if (endsWithAny(name, ".yaml", ".yml")) {
      return YAML;
    }
    if (endsWithAny(name, ".md", ".markdown")) {
      return MARKDOWN;
    }
    if (name.endsWith(".properties")) {
      return PROPERTIES;
    }
    if (endsWithAny(
        name, ".png", ".jpg", ".jpeg", ".gif", ".bmp", ".webp", ".svg", ".ico", ".tif", ".tiff",
        ".avif")) {
      return IMAGE;
    }
    if (endsWithAny(name, ".mp3", ".wav", ".flac", ".ogg", ".m4a", ".aac", ".aiff", ".opus")) {
      return AUDIO;
    }
    if (endsWithAny(name, ".mp4", ".mov", ".mkv", ".webm", ".avi", ".m4v", ".mpeg", ".mpg")) {
      return VIDEO;
    }
    if (endsWithAny(name, ".ttf", ".otf", ".woff", ".woff2", ".eot")) {
      return FONT;
    }
    if (name.endsWith(".pdf")) {
      return PDF;
    }
    if (endsWithAny(
        name, ".zip", ".tar", ".gz", ".tgz", ".bz2", ".tbz2", ".xz", ".txz", ".7z", ".rar", ".jar",
        ".war", ".ear", ".deb", ".rpm", ".dmg")) {
      return ARCHIVE;
    }
    if (name.endsWith(".log")) {
      return LOG;
    }
    return TEXT;
  }

  /** Returns the editor syntax style for this type; non-source types remain plain text. */
  public String syntaxStyle() {
    return switch (this) {
      // Starlark is close enough to Python for its strings, comments,
      // numbers, calls, lists and dictionaries to be useful here.
      case BAZEL, PYTHON -> SyntaxConstants.SYNTAX_STYLE_PYTHON;
      case MAKEFILE -> SyntaxConstants.SYNTAX_STYLE_MAKEFILE;
      case DOCKER -> SyntaxConstants.SYNTAX_STYLE_DOCKERFILE;
      case JSON -> SyntaxConstants.SYNTAX_STYLE_JSON;
      case XML -> SyntaxConstants.SYNTAX_STYLE_XML;
      case JAVA -> SyntaxConstants.SYNTAX_STYLE_JAVA;
      case SHELL -> SyntaxConstants.SYNTAX_STYLE_UNIX_SHELL;
      case C -> SyntaxConstants.SYNTAX_STYLE_C;
      case CPP -> SyntaxConstants.SYNTAX_STYLE_CPLUSPLUS;
      case CSHARP -> SyntaxConstants.SYNTAX_STYLE_CSHARP;
      case CSS -> SyntaxConstants.SYNTAX_STYLE_CSS;
      case HTML -> SyntaxConstants.SYNTAX_STYLE_HTML;
      case JAVASCRIPT -> SyntaxConstants.SYNTAX_STYLE_JAVASCRIPT;
      case TYPESCRIPT -> SyntaxConstants.SYNTAX_STYLE_TYPESCRIPT;
      case KOTLIN -> SyntaxConstants.SYNTAX_STYLE_KOTLIN;
      case GO -> SyntaxConstants.SYNTAX_STYLE_GO;
      case RUST -> SyntaxConstants.SYNTAX_STYLE_RUST;
      case RUBY -> SyntaxConstants.SYNTAX_STYLE_RUBY;
      case SQL -> SyntaxConstants.SYNTAX_STYLE_SQL;
      case PROTO -> SyntaxConstants.SYNTAX_STYLE_PROTO;
      case YAML -> SyntaxConstants.SYNTAX_STYLE_YAML;
      case MARKDOWN -> SyntaxConstants.SYNTAX_STYLE_MARKDOWN;
      case PROPERTIES -> SyntaxConstants.SYNTAX_STYLE_PROPERTIES_FILE;
      case IMAGE, AUDIO, VIDEO, FONT, PDF, ARCHIVE, LOG, TEXT -> SyntaxConstants.SYNTAX_STYLE_NONE;
    };
  }

  private static boolean endsWithAny(String value, String... suffixes) {
    for (String suffix : suffixes) {
      if (value.endsWith(suffix)) {
        return true;
      }
    }
    return false;
  }
}
