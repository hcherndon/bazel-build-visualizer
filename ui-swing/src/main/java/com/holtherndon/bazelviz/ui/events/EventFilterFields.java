package com.holtherndon.bazelviz.ui.events;

import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildEvent;
import com.holtherndon.bazelviz.bepcodec.BepPayloadType;
import com.holtherndon.bazelviz.ui.filter.FilterField;
import com.holtherndon.bazelviz.ui.filter.FilterField.Choice;
import com.holtherndon.bazelviz.ui.filter.FilterField.Kind;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** Event-specific labels and value choices for the shared visual filter builder. */
final class EventFilterFields {
  private EventFilterFields() {}

  static List<FilterField> fields() {
    List<Choice> types =
        Arrays.stream(BuildEvent.PayloadCase.values())
            .map(
                type ->
                    new Choice(
                        Integer.toString(type.getNumber()), BepPayloadType.name(type.getNumber())))
            .sorted(Comparator.comparing(Choice::label))
            .toList();
    List<Choice> booleans = List.of(new Choice("true", "Yes"), new Choice("false", "No"));
    return List.of(
        new FilterField(
            "type",
            "Type",
            Kind.CHOICE,
            types,
            "The BEP payload type. Choose ‘is one of’ to select several types."),
        number(
            "children",
            "Children",
            "Number of children announced by this event. Failed decodes are unknown, not zero."),
        new FilterField(
            "decode",
            "Decode status",
            Kind.CHOICE,
            List.of(
                new Choice("OK", "OK"),
                new Choice("UNKNOWN_FIELDS", "Unknown fields"),
                new Choice("FAILED", "Failed")),
            "Whether the preserved event decoded successfully."),
        new FilterField(
            "event_id",
            "Event ID",
            Kind.TEXT,
            List.of(),
            "The canonical event identity text, including target labels when present. Contains"
                + " ignores case; is matches exactly."),
        number("raw_bytes", "Raw bytes", "Size of the original event payload, in bytes."),
        number("id", "Row ID", "Database row ID in arrival order."),
        number("sequence", "Sequence", "Recorded sequence number within its stream."),
        number("stream", "Stream ID", "The source stream's database ID."),
        number(
            "event_time",
            "Event time (µs)",
            "Microseconds since the Unix epoch. Use ‘is unknown’ for events with no timestamp."),
        number(
            "received", "Received time (µs)", "Receive time in microseconds since the Unix epoch."),
        new FilterField(
            "unknown_fields",
            "Has unknown fields",
            Kind.BOOLEAN,
            booleans,
            "Whether a decoded event contains unrecognized fields."),
        new FilterField(
            "last_message",
            "Last message",
            Kind.BOOLEAN,
            booleans,
            "Whether the decoded event is marked as the last message."));
  }

  private static FilterField number(String id, String label, String help) {
    return new FilterField(id, label, Kind.NUMBER, List.of(), help);
  }
}
