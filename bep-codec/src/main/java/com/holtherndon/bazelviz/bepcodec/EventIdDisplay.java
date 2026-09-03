package com.holtherndon.bazelviz.bepcodec;

import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildEventId;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Short, human-readable renderings of a {@code BuildEventId} for the events table, the inspector,
 * and the {@code bep_event_ids.display} column.
 *
 * <p>Two rules govern the output, both from plan section 11.4:
 *
 * <ul>
 *   <li>It is never blank. A row in the events view with an empty identity column is
 *       indistinguishable from a rendering bug.
 *   <li>It never invents a value. Where a field is absent, the output says so in a form no real
 *       Bazel value can take — {@code <no label>}, not an empty string and not the word "unknown"
 *       sitting where a label belongs. The one genuine {@code UnknownBuildEventId} variant is
 *       rendered by its own name, because there it <em>is</em> the value.
 * </ul>
 *
 * <p>Variants this build does not model still render honestly, naming the wire field number, so a
 * session captured from a newer Bazel shows "an id kind we do not know, number 31" rather than a
 * blank or a wrong label.
 */
public final class EventIdDisplay {

  private EventIdDisplay() {}

  /**
   * Ceiling on a rendered string. Only pattern lists realistically approach it. Reaching it is
   * marked in the output rather than passing silently, per the project rule that no limit is
   * applied invisibly.
   */
  public static final int MAX_DISPLAY_CHARS = 240;

  /** Stand-in for a string field that carries no value. */
  public static final String ABSENT_LABEL = "<no label>";

  /** How many patterns a {@code PatternExpandedId} lists before summarising. */
  private static final int MAX_LISTED_PATTERNS = 3;

  /** Renders {@code id}. Never returns null, empty, or blank. */
  public static String of(BuildEventId id) {
    return cap(render(id));
  }

  /**
   * The target label an id carries, when it carries one.
   *
   * <p>This is the structured accessor the cross-view navigation actions are built on: given a
   * decoded {@code BuildEventId}, it answers "which target is this event about" from the proto's
   * own fields rather than from any rendered sentence. Nine of the id kinds carry a label; every
   * other kind — and a label-carrying kind whose label field is empty — answers empty, never a
   * placeholder.
   */
  public static Optional<String> label(BuildEventId id) {
    String label =
        switch (id.getIdCase()) {
          case TARGET_CONFIGURED -> id.getTargetConfigured().getLabel();
          case TARGET_COMPLETED -> id.getTargetCompleted().getLabel();
          case ACTION_COMPLETED -> id.getActionCompleted().getLabel();
          case UNCONFIGURED_LABEL -> id.getUnconfiguredLabel().getLabel();
          case CONFIGURED_LABEL -> id.getConfiguredLabel().getLabel();
          case TEST_RESULT -> id.getTestResult().getLabel();
          case TEST_PROGRESS -> id.getTestProgress().getLabel();
          case TEST_SUMMARY -> id.getTestSummary().getLabel();
          case TARGET_SUMMARY -> id.getTargetSummary().getLabel();
          default -> "";
        };
    return label.isEmpty() ? Optional.empty() : Optional.of(label);
  }

  /**
   * The kind words whose display puts a target label in the first slot after the kind, mapped to
   * whether that slot can also hold something that is <em>not</em> a label. Only {@code
   * ActionCompleted} renders a primary output path there when the id declares no label, which is
   * why the parse below additionally requires the label shape.
   */
  private static final Map<String, Boolean> LABEL_KINDS =
      Map.of(
          "TargetConfigured", false,
          "TargetCompleted", false,
          "ActionCompleted", true,
          "UnconfiguredLabel", false,
          "ConfiguredLabel", false,
          "TestResult", false,
          "TestProgress", false,
          "TestSummary", false,
          "TargetSummary", false);

  /**
   * Recovers the target label from a display string {@link #of} produced.
   *
   * <p>This exists for the one place that has the sentence and not the proto: a stored event row
   * carries {@code bep_event_ids.display} and nothing else, and the navigation actions parse the
   * label out of it at render time (an explicit decision — no schema or capture-path change). The
   * grammar lives here, next to the renderer that defines it, so the two cannot drift apart
   * unnoticed; wherever a decoded id or payload is in hand, {@link #label(BuildEventId)} is the
   * accessor to prefer.
   *
   * <p>It never guesses. A token that does not look like a label (Bazel labels start with {@code
   * //} or {@code @}), an absent-value marker such as {@value #ABSENT_LABEL}, and a label that may
   * have been cut by {@link #MAX_DISPLAY_CHARS} all answer empty rather than a fragment.
   */
  public static Optional<String> labelOfDisplay(String display) {
    int space = display.indexOf(' ');
    if (space <= 0) {
      return Optional.empty();
    }
    if (!LABEL_KINDS.containsKey(display.substring(0, space))) {
      return Optional.empty();
    }
    int start = space + 1;
    int end = display.indexOf(' ', start);
    if (end < 0) {
      end = display.length();
    }
    String token = display.substring(start, end);
    // A label never begins with '<' (the absent markers do) and always
    // begins with "//" or "@"; anything else in the slot is not a label —
    // for ActionCompleted it is a primary output path, elsewhere it would
    // mean the renderer changed and this parser must not pretend otherwise.
    if (!(token.startsWith("//") || token.startsWith("@"))) {
      return Optional.empty();
    }
    // The display cap can cut a very long label and append its marker
    // right where the label stopped. A token that runs into the cap and is
    // followed by the marker is a fragment, and a fragment is not a label.
    if (end >= MAX_DISPLAY_CHARS - 1 && display.substring(end).matches(" \\(\\+\\d+ chars\\)")) {
      return Optional.empty();
    }
    return Optional.of(token);
  }

  private static String render(BuildEventId id) {
    return switch (id.getIdCase()) {
      case UNKNOWN -> withDetail("UnknownBuildEvent", id.getUnknown().getDetails());
      case PROGRESS -> "Progress #" + id.getProgress().getOpaqueCount();
      case STARTED -> "BuildStarted";
      case UNSTRUCTURED_COMMAND_LINE -> "UnstructuredCommandLine";
      case STRUCTURED_COMMAND_LINE ->
          withDetail("StructuredCommandLine", id.getStructuredCommandLine().getCommandLineLabel());
      case WORKSPACE_STATUS -> "WorkspaceStatus";
      case OPTIONS_PARSED -> "OptionsParsed";
      case FETCH -> "Fetch " + orAbsent(id.getFetch().getUrl(), "<no url>");
      case CONFIGURATION -> "Configuration " + orAbsent(id.getConfiguration().getId(), "<no id>");
      case TARGET_CONFIGURED ->
          "TargetConfigured "
              + orAbsent(id.getTargetConfigured().getLabel(), ABSENT_LABEL)
              + aspect(id.getTargetConfigured().getAspect());
      case PATTERN -> "PatternExpanded " + patterns(id.getPattern().getPatternList());
      case PATTERN_SKIPPED -> "PatternSkipped " + patterns(id.getPatternSkipped().getPatternList());
      case NAMED_SET -> "NamedSetOfFiles " + orAbsent(id.getNamedSet().getId(), "<no id>");
      case TARGET_COMPLETED ->
          "TargetCompleted "
              + orAbsent(id.getTargetCompleted().getLabel(), ABSENT_LABEL)
              + aspect(id.getTargetCompleted().getAspect())
              + configuration(id.getTargetCompleted().getConfiguration().getId());
      case ACTION_COMPLETED -> actionCompleted(id.getActionCompleted());
      case UNCONFIGURED_LABEL ->
          "UnconfiguredLabel " + orAbsent(id.getUnconfiguredLabel().getLabel(), ABSENT_LABEL);
      case CONFIGURED_LABEL ->
          "ConfiguredLabel "
              + orAbsent(id.getConfiguredLabel().getLabel(), ABSENT_LABEL)
              + configuration(id.getConfiguredLabel().getConfiguration().getId());
      case TEST_RESULT ->
          "TestResult "
              + orAbsent(id.getTestResult().getLabel(), ABSENT_LABEL)
              + " run "
              + id.getTestResult().getRun()
              + " shard "
              + id.getTestResult().getShard()
              + " attempt "
              + id.getTestResult().getAttempt()
              + configuration(id.getTestResult().getConfiguration().getId());
      case TEST_PROGRESS ->
          "TestProgress "
              + orAbsent(id.getTestProgress().getLabel(), ABSENT_LABEL)
              + " run "
              + id.getTestProgress().getRun()
              + " shard "
              + id.getTestProgress().getShard()
              + " attempt "
              + id.getTestProgress().getAttempt()
              + " #"
              + id.getTestProgress().getOpaqueCount()
              + configuration(id.getTestProgress().getConfiguration().getId());
      case TEST_SUMMARY ->
          "TestSummary "
              + orAbsent(id.getTestSummary().getLabel(), ABSENT_LABEL)
              + configuration(id.getTestSummary().getConfiguration().getId());
      case TARGET_SUMMARY ->
          "TargetSummary "
              + orAbsent(id.getTargetSummary().getLabel(), ABSENT_LABEL)
              + configuration(id.getTargetSummary().getConfiguration().getId());
      case BUILD_FINISHED -> "BuildFinished";
      case BUILD_TOOL_LOGS -> "BuildToolLogs";
      case BUILD_METRICS -> "BuildMetrics";
      case WORKSPACE -> "WorkspaceConfig";
      case BUILD_METADATA -> "BuildMetadata";
      case CONVENIENCE_SYMLINKS_IDENTIFIED -> "ConvenienceSymlinksIdentified";
      case EXEC_REQUEST -> "ExecRequest";
      case ID_NOT_SET -> unrecognised(id);
    };
  }

  /**
   * Renders an id whose oneof is not set. Either the message is genuinely empty, or it holds a
   * variant from a newer Bazel that this build retained as unknown fields — in which case the field
   * number is the most specific true thing we can say about it.
   */
  private static String unrecognised(BuildEventId id) {
    var fieldNumbers = id.getUnknownFields().asMap().keySet();
    if (fieldNumbers.isEmpty()) {
      return "<empty id>";
    }
    // Lowest rather than first: the iteration order of an unknown-field set
    // is not part of protobuf's contract, and this string is persisted.
    return "<unrecognised id kind " + Collections.min(fieldNumbers) + ">";
  }

  private static String actionCompleted(BuildEventId.ActionCompletedId action) {
    String primaryOutput = action.getPrimaryOutput();
    String label = action.getLabel();
    StringBuilder text = new StringBuilder("ActionCompleted ");
    if (!label.isEmpty()) {
      text.append(label);
      if (!primaryOutput.isEmpty()) {
        text.append(' ').append('(').append(primaryOutput).append(')');
      }
    } else if (!primaryOutput.isEmpty()) {
      text.append(primaryOutput);
    } else {
      text.append("<no output>");
    }
    return text + configuration(action.getConfiguration().getId());
  }

  private static String patterns(List<String> patterns) {
    if (patterns.isEmpty()) {
      return "<no patterns>";
    }
    int listed = Math.min(patterns.size(), MAX_LISTED_PATTERNS);
    String joined = String.join(" ", patterns.subList(0, listed));
    return listed == patterns.size()
        ? joined
        : joined + " (+" + (patterns.size() - listed) + " more)";
  }

  private static String aspect(String aspect) {
    return aspect.isEmpty() ? "" : " aspect " + aspect;
  }

  private static String configuration(String configurationId) {
    return configurationId.isEmpty() ? "" : " [cfg " + configurationId + "]";
  }

  private static String withDetail(String name, String detail) {
    return detail.isEmpty() ? name : name + " " + detail;
  }

  private static String orAbsent(String value, String absentMarker) {
    return value.isEmpty() ? absentMarker : value;
  }

  private static String cap(String text) {
    if (text.length() <= MAX_DISPLAY_CHARS) {
      return text;
    }
    // Do not cut a surrogate pair in half; a lone surrogate is not text.
    int end =
        Character.isHighSurrogate(text.charAt(MAX_DISPLAY_CHARS - 1))
            ? MAX_DISPLAY_CHARS - 1
            : MAX_DISPLAY_CHARS;
    return text.substring(0, end) + " (+" + (text.length() - end) + " chars)";
  }
}
