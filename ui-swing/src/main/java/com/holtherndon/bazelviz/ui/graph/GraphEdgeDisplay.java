package com.holtherndon.bazelviz.ui.graph;

/** How much dependency detail the graph canvas draws. */
public enum GraphEdgeDisplay {
  DECLUTTERED(
      "Decluttered (recommended)",
      "In Dependency hierarchy, draw the primary branches and links touching one"
          + " selected node. Other layouts continue to draw every dependency."),
  ALL(
      "All dependencies",
      "Draw every dependency in the extract. Shared hierarchy links are subdued so the"
          + " primary branches remain visible.");

  private final String displayName;
  private final String description;

  GraphEdgeDisplay(String displayName, String description) {
    this.displayName = displayName;
    this.description = description;
  }

  public String displayName() {
    return displayName;
  }

  public String description() {
    return description;
  }
}
