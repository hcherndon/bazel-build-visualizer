package com.holtherndon.bazelviz.capture.file.json;

import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildEvent;
import com.holtherndon.bazelviz.core.event.DecodeStatus;
import java.util.Objects;
import java.util.Optional;

/**
 * What {@link JsonBuildEventDecoder} made of one record's bytes.
 *
 * @param status the outcome; {@link DecodeStatus#hasEvent()} says whether {@code event} is present
 * @param event the decoded event, or null when the record could not be decoded
 * @param message the parser's complaint, or null when there was none. Present for {@link
 *     DecodeStatus#UNKNOWN_FIELDS} too, where it names the field this build does not know — which
 *     is the whole point of surfacing it
 */
public record JsonDecodeResult(DecodeStatus status, BuildEvent event, String message) {

  public JsonDecodeResult {
    Objects.requireNonNull(status, "status");
    if (status.hasEvent() == (event == null)) {
      throw new IllegalArgumentException(
          "decode status "
              + status
              + " is inconsistent with "
              + (event == null ? "a missing" : "a present")
              + " event");
    }
  }

  public Optional<BuildEvent> decodedEvent() {
    return Optional.ofNullable(event);
  }

  public Optional<String> detail() {
    return Optional.ofNullable(message);
  }
}
