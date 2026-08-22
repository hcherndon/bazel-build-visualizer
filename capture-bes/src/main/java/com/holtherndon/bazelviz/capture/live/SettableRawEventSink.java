package com.holtherndon.bazelviz.capture.live;

import com.holtherndon.bazelviz.capture.bes.BesStreamKey;
import com.holtherndon.bazelviz.capture.bes.BesStreamState;
import com.holtherndon.bazelviz.capture.bes.RawBesEvent;
import com.holtherndon.bazelviz.capture.bes.RawEventSink;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A sink whose destination is attached after the server is already listening.
 *
 * <h2>Why the server starts before the session exists</h2>
 *
 * <p>The instrumentation plan contains {@code --bes_backend=grpc://127.0.0.1:N},
 * and the user has to see that plan — the real one, with the real port — before
 * deciding to launch (ADR-007). But the port is only known once the listener is
 * up, and the pipeline behind it needs a session that should not be created for
 * a build the user may still cancel.
 *
 * <p>So the server starts first with this in front of it, the plan is built and
 * shown, and the pipeline is attached at launch. Nothing can arrive in between:
 * Bazel has not been started, and this endpoint is on loopback with a port
 * nobody else has been told about. If something does arrive anyway, it is
 * refused rather than dropped, which is the same promise every other path
 * makes.
 */
public final class SettableRawEventSink implements RawEventSink {

    private final AtomicReference<RawEventSink> delegate = new AtomicReference<>();

    /**
     * Attaches the real pipeline.
     *
     * @throws IllegalStateException if one is already attached; two pipelines
     *     behind one server would each see half the stream
     */
    public void attach(RawEventSink pipeline) {
        Objects.requireNonNull(pipeline, "pipeline");
        if (!delegate.compareAndSet(null, pipeline)) {
            throw new IllegalStateException("a capture pipeline is already attached to this server");
        }
    }

    /** Detaches, so a finished capture's pipeline is not held alive by the server. */
    public void detach() {
        delegate.set(null);
    }

    public boolean isAttached() {
        return delegate.get() != null;
    }

    @Override
    public void submit(RawBesEvent event, Runnable onJournaled)
            throws InterruptedException, CaptureRejectedException {
        require().submit(event, onJournaled);
    }

    @Override
    public void streamOpened(BesStreamKey key) {
        RawEventSink pipeline = delegate.get();
        if (pipeline != null) {
            pipeline.streamOpened(key);
        }
    }

    @Override
    public void streamEnded(BesStreamState finalState) {
        RawEventSink pipeline = delegate.get();
        if (pipeline != null) {
            pipeline.streamEnded(finalState);
        }
    }

    private RawEventSink require() throws CaptureRejectedException {
        RawEventSink pipeline = delegate.get();
        if (pipeline == null) {
            throw new CaptureRejectedException(
                    "no capture is running: this build event arrived before a session was started,"
                            + " or after it finished");
        }
        return pipeline;
    }
}
