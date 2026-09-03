package com.holtherndon.bazelviz.capture.file.binary;

import java.util.ArrayList;
import java.util.List;

/**
 * Collects every frame a parse delivers, copying the payload out of the parser's reusable buffer as
 * a real sink must.
 */
final class RecordingSink implements BinaryBepEventSink {

  record Delivered(long frameOffset, long payloadOffset, int payloadLength, byte[] payload) {}

  private final List<Delivered> frames = new ArrayList<>();

  @Override
  public void onEvent(BinaryBepFrame frame) {
    frames.add(
        new Delivered(
            frame.frameOffset(),
            frame.payloadOffset(),
            frame.payloadLength(),
            frame.copyPayload()));
  }

  List<Delivered> frames() {
    return frames;
  }

  int size() {
    return frames.size();
  }

  List<Long> frameOffsets() {
    return frames.stream().map(Delivered::frameOffset).toList();
  }
}
