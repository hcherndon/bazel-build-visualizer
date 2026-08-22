package com.holtherndon.bazelviz.capture.file.binary;

import java.io.IOException;

/**
 * Receives each complete frame a {@link BinaryBepParser} reads, in stream order.
 *
 * <p>Raw-first (ADR-004): the sink is handed undecoded bytes, so the journal
 * writer can persist the source's exact bytes before anything decodes them.
 *
 * <p>Throwing from {@link #onEvent} aborts the parse and propagates. That is a
 * legitimate way to fail fast on a write error, but it is not the way to
 * cancel: cancellation loses the resume offset unless the caller uses the
 * cancellation overload of {@link BinaryBepParser#parse}, which returns the
 * offset to resume from.
 */
@FunctionalInterface
public interface BinaryBepEventSink {

    /**
     * @param frame the frame just read; its payload buffer is only valid until
     *     this call returns (see {@link BinaryBepFrame})
     */
    void onEvent(BinaryBepFrame frame) throws IOException;
}
