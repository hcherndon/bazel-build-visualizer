package com.holtherndon.bazelviz.ui.repository;

import com.holtherndon.bazelviz.runner.files.FileMetadata;

/** Recognizes exact root Bazel convenience links without probing their targets on the EDT. */
final class BazelOutputSymlink {

  private BazelOutputSymlink() {}

  static boolean isCandidate(
      FileMetadata.Kind kind,
      String fileName,
      boolean directRepositoryChild,
      String workspaceDirectoryName) {
    return kind == FileMetadata.Kind.SYMBOLIC_LINK
        && directRepositoryChild
        && (fileName.equals("bazel-out")
            || fileName.equals("bazel-bin")
            || fileName.equals("bazel-testlogs")
            || fileName.equals("bazel-genfiles")
            || (!workspaceDirectoryName.isBlank()
                && fileName.equals("bazel-" + workspaceDirectoryName)));
  }
}
