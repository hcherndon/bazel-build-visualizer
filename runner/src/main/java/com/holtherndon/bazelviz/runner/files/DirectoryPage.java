package com.holtherndon.bazelviz.runner.files;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/** One bounded page from a single directory. */
public record DirectoryPage(
    ExecutionPath directory,
    List<FileMetadata> entries,
    Optional<String> nextToken,
    OptionalLong totalEntries) {

  public DirectoryPage {
    Objects.requireNonNull(directory, "directory");
    entries = List.copyOf(entries);
    Objects.requireNonNull(nextToken, "nextToken");
    Objects.requireNonNull(totalEntries, "totalEntries");
    if (totalEntries.isPresent() && totalEntries.getAsLong() < entries.size()) {
      throw new IllegalArgumentException("directory total is smaller than this page");
    }
  }
}
