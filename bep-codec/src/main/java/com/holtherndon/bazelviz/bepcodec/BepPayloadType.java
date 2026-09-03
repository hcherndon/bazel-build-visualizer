package com.holtherndon.bazelviz.bepcodec;

import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildEvent;

/**
 * The value for {@code bep_events.event_type}: the wire field number of a {@code BuildEvent}'s
 * payload oneof.
 *
 * <p>The field number, not the enum ordinal, is what gets stored. Ordinals shift whenever a case is
 * inserted into the generated enum; field numbers are fixed by the {@code .proto} and are the one
 * identifier Bazel guarantees across versions. Since {@code event_type} is indexed and queried for
 * the lifetime of a session database, storing anything less stable would make old sessions mean
 * different things under a newer build.
 *
 * <p>{@link #NONE} is reserved for an event with no payload set. That is a real state — the {@code
 * last_message} sentinel carries none — not a missing value, which is why {@code event_type} is
 * {@code NOT NULL} while other unavailable columns are nullable.
 */
public final class BepPayloadType {

  private BepPayloadType() {}

  /** No payload was set on the event. */
  public static final int NONE = BuildEvent.PayloadCase.PAYLOAD_NOT_SET.getNumber();

  /** The payload field number of {@code event}, or {@link #NONE}. */
  public static int of(BuildEvent event) {
    return event.getPayloadCase().getNumber();
  }

  /** The payload field number of {@code payloadCase}, or {@link #NONE}. */
  public static int of(BuildEvent.PayloadCase payloadCase) {
    return payloadCase.getNumber();
  }

  /**
   * The payload case for a stored {@code event_type}, or {@code null} when the number belongs to a
   * payload this build does not model — a session indexed by a newer version, or reindexed from a
   * journal a newer Bazel wrote.
   */
  public static BuildEvent.PayloadCase caseOf(int eventType) {
    return BuildEvent.PayloadCase.forNumber(eventType);
  }

  /** True when {@code eventType} names a payload this build models. */
  public static boolean isKnown(int eventType) {
    return eventType != NONE && caseOf(eventType) != null;
  }

  /**
   * A label for a stored {@code event_type}, for filters and column headers.
   *
   * <p>An unmodelled number renders as {@code payload#31} rather than as a guess or a blank, so a
   * stream from a newer Bazel is visibly a payload kind this build does not name yet.
   */
  public static String name(int eventType) {
    if (eventType == NONE) {
      return "none";
    }
    BuildEvent.PayloadCase payloadCase = caseOf(eventType);
    return payloadCase == null ? "payload#" + eventType : camelCase(payloadCase.name());
  }

  /** {@code TEST_RESULT} to {@code testResult}. */
  private static String camelCase(String screamingSnake) {
    StringBuilder text = new StringBuilder(screamingSnake.length());
    boolean upperNext = false;
    for (int i = 0; i < screamingSnake.length(); i++) {
      char c = screamingSnake.charAt(i);
      if (c == '_') {
        upperNext = true;
      } else if (upperNext) {
        text.append(c); // enum names are already upper case here
        upperNext = false;
      } else {
        text.append(Character.toLowerCase(c));
      }
    }
    return text.toString();
  }
}
