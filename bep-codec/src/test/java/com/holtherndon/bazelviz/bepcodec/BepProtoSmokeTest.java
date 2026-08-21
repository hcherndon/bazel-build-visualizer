package com.holtherndon.bazelviz.bepcodec;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos;
import com.google.devtools.build.v1.PublishBuildEventGrpc;
import com.google.protobuf.InvalidProtocolBufferException;
import io.grpc.ServiceDescriptor;
import org.junit.jupiter.api.Test;

/**
 * Proves the vendored proto definitions in :proto actually produce usable
 * generated code on this module's classpath (java codegen for the BEP stream,
 * grpc codegen for the BES service) before any real codec work builds on them.
 */
final class BepProtoSmokeTest {

    @Test
    void buildEventRoundTripsThroughWireFormat() throws InvalidProtocolBufferException {
        BuildEventStreamProtos.BuildEvent original = BuildEventStreamProtos.BuildEvent.newBuilder()
                .setId(BuildEventStreamProtos.BuildEventId.newBuilder()
                        .setProgress(BuildEventStreamProtos.BuildEventId.ProgressId.newBuilder()
                                .setOpaqueCount(42)))
                .setProgress(BuildEventStreamProtos.Progress.newBuilder()
                        .setStderr("smoke test stderr")
                        .setStdout("smoke test stdout"))
                .build();

        byte[] wire = original.toByteArray();
        BuildEventStreamProtos.BuildEvent parsed = BuildEventStreamProtos.BuildEvent.parseFrom(wire);

        assertThat(parsed).isEqualTo(original);
        assertThat(parsed.getId().getProgress().getOpaqueCount()).isEqualTo(42);
        assertThat(parsed.getPayloadCase())
                .isEqualTo(BuildEventStreamProtos.BuildEvent.PayloadCase.PROGRESS);
        assertThat(parsed.getProgress().getStderr()).isEqualTo("smoke test stderr");
    }

    @Test
    void besGrpcServiceDescriptorLoads() {
        ServiceDescriptor descriptor = PublishBuildEventGrpc.getServiceDescriptor();

        assertThat(descriptor.getName()).isEqualTo("google.devtools.build.v1.PublishBuildEvent");
        assertThat(descriptor.getMethods())
                .extracting(io.grpc.MethodDescriptor::getBareMethodName)
                .contains("PublishLifecycleEvent", "PublishBuildToolEventStream");
    }
}
