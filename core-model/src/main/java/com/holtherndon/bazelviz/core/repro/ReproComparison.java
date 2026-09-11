package com.holtherndon.bazelviz.core.repro;

import com.holtherndon.bazelviz.core.filter.FilterExpression;
import java.io.IOException;
import java.util.List;

/** Worker-thread service for comparing two trusted execution logs; never proves hermeticity. */
public interface ReproComparison extends AutoCloseable {
  enum Finding {
    OUTPUT_DIVERGENCE,
    OUTPUT_CHANGED,
    RECIPE_DRIFT,
    INPUT_DRIFT,
    CACHE_IDENTITY_DRIFT,
    DOWNSTREAM,
    ADDED,
    REMOVED,
    INCONCLUSIVE,
    UNCHANGED
  }

  record Summary(
      long actionsA,
      long actionsB,
      long matched,
      long unchanged,
      long outputDivergences,
      long drift,
      long downstream,
      long inconclusive,
      List<String> coverageNotes) {
    public Summary {
      coverageNotes = List.copyOf(coverageNotes);
    }
  }

  record Row(
      long id,
      String target,
      String mnemonic,
      String output,
      Finding finding,
      boolean recipeChanged,
      boolean inputsChanged,
      boolean outputsChanged,
      String reason) {}

  record Page(List<Row> rows, long total) {
    public Page {
      rows = List.copyOf(rows);
    }
  }

  record FieldDifference(String section, String field, String before, String after) {}

  record Details(Row row, List<FieldDifference> differences, long total, boolean truncated) {
    public Details {
      differences = List.copyOf(differences);
    }
  }

  Summary summary() throws IOException;

  /** Keyset page. Filter fields: target, mnemonic, output, finding (all strings). */
  Page page(FilterExpression filter, long afterId, int limit) throws IOException;

  default Details details(long id, int limit) throws IOException {
    return details(id, 0, limit);
  }

  /**
   * Field differences are independently paged. Sensitive values are masked, never hashed publicly.
   */
  Details details(long id, long offset, int limit) throws IOException;

  @Override
  void close() throws IOException;
}
