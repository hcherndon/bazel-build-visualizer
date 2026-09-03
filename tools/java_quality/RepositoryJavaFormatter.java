package com.holtherndon.bazelviz.tools.quality;

import com.google.googlejavaformat.java.Main;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** Repository and Bazel-action entry point for Google Java Format. */
public final class RepositoryJavaFormatter {
  private static final String CHECK = "--check";
  private static final String CHECK_ACTION = "--check-action";
  private static final String WORKSPACE_ENV = "BUILD_WORKSPACE_DIRECTORY";

  private RepositoryJavaFormatter() {}

  public static void main(String[] args) throws Exception {
    if (args.length > 0 && args[0].equals(CHECK_ACTION)) {
      checkAction(args);
      return;
    }
    formatRepository(args);
  }

  private static void checkAction(String[] args) throws Exception {
    if (args.length < 3) {
      throw new IllegalArgumentException("--check-action requires an output and Java sources");
    }
    Path output = Path.of(args[1]);
    List<String> formatterArgs = new ArrayList<>();
    formatterArgs.add("--dry-run");
    formatterArgs.add("--set-exit-if-changed");
    formatterArgs.addAll(Arrays.asList(args).subList(2, args.length));
    int result = runFormatter(formatterArgs);
    if (result != 0) {
      throw new IllegalStateException(
          "Java sources are not formatted; run bazel run //tools:format_java");
    }
    Files.writeString(output, "google-java-format: clean\n", StandardCharsets.UTF_8);
  }

  private static void formatRepository(String[] args) throws Exception {
    boolean check = args.length == 1 && args[0].equals(CHECK);
    if (args.length > (check ? 1 : 0)) {
      throw new IllegalArgumentException("usage: format_java [--check]");
    }
    String workspace = System.getenv(WORKSPACE_ENV);
    if (workspace == null || workspace.isBlank()) {
      throw new IllegalStateException("run this formatter with bazel run //tools:format_java");
    }

    List<String> formatterArgs = new ArrayList<>();
    if (check) {
      formatterArgs.add("--dry-run");
      formatterArgs.add("--set-exit-if-changed");
    } else {
      formatterArgs.add("--replace");
    }
    javaSources(Path.of(workspace)).stream().map(Path::toString).forEach(formatterArgs::add);
    if (formatterArgs.size() == 1) {
      throw new IllegalStateException("no Java sources found below " + workspace);
    }

    int result = runFormatter(formatterArgs);
    if (result != 0) {
      throw new IllegalStateException(
          check
              ? "Java sources are not formatted; run bazel run //tools:format_java"
              : "Google Java Format failed");
    }
    System.out.println(check ? "All Java sources are formatted." : "Formatted all Java sources.");
  }

  private static List<Path> javaSources(Path workspace) throws Exception {
    List<Path> sources = new ArrayList<>();
    Files.walkFileTree(
        workspace,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
            if (!directory.equals(workspace)
                && (directory.getFileName().toString().equals(".git")
                    || Files.exists(directory.resolve(".git"), LinkOption.NOFOLLOW_LINKS))) {
              return FileVisitResult.SKIP_SUBTREE;
            }
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
            if (attributes.isRegularFile() && file.getFileName().toString().endsWith(".java")) {
              sources.add(file);
            }
            return FileVisitResult.CONTINUE;
          }
        });
    sources.sort(Comparator.comparing(Path::toString));
    return sources;
  }

  private static int runFormatter(List<String> args) throws Exception {
    PrintWriter out = new PrintWriter(System.out, true, StandardCharsets.UTF_8);
    PrintWriter err = new PrintWriter(System.err, true, StandardCharsets.UTF_8);
    return new Main(out, err, System.in).format(args.toArray(String[]::new));
  }
}
