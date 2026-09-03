package com.holtherndon.bazelviz.enrich.graph;

import com.google.devtools.build.lib.analysis.AnalysisProtosV2.Action;
import com.google.devtools.build.lib.analysis.AnalysisProtosV2.Artifact;
import com.google.devtools.build.lib.analysis.AnalysisProtosV2.Configuration;
import com.google.devtools.build.lib.analysis.AnalysisProtosV2.DepSetOfFiles;
import com.google.devtools.build.lib.analysis.AnalysisProtosV2.PathFragment;
import com.google.devtools.build.lib.analysis.AnalysisProtosV2.RuleClass;
import com.google.devtools.build.lib.analysis.AnalysisProtosV2.Target;

/**
 * Receives the entities of an action graph in the order the file happens to carry them.
 *
 * <p>Deliberately offers no way to resolve a reference. The file references entities before
 * declaring them on three of the four supported Bazel versions (finding Q4), so anything resolved
 * during the read would be resolved against an incomplete picture — quietly, since the result is a
 * graph slightly smaller than the truth. Resolution happens after the last entity, and the type
 * says so by having no method for it.
 */
public interface ActionGraphVisitor {

  void artifact(Artifact artifact);

  void action(Action action);

  void target(Target target);

  void depSet(DepSetOfFiles depSet);

  void configuration(Configuration configuration);

  void ruleClass(RuleClass ruleClass);

  void pathFragment(PathFragment fragment);
}
