package com.holtherndon.bazelviz.bepcodec;

import static com.holtherndon.bazelviz.bepcodec.WireBytes.stringField;
import static org.assertj.core.api.Assertions.assertThat;

import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildEventId;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import org.junit.jupiter.api.Test;

final class EventIdDisplayTest {

    private static final int FUTURE_ID_FIELD = 909;

    private static BuildEventId id(java.util.function.Consumer<BuildEventId.Builder> build) {
        BuildEventId.Builder builder = BuildEventId.newBuilder();
        build.accept(builder);
        return builder.build();
    }

    private static BuildEventId.ConfigurationId config(String id) {
        return BuildEventId.ConfigurationId.newBuilder().setId(id).build();
    }

    @Test
    void rendersProgress() {
        assertThat(EventIdDisplay.of(id(b -> b.setProgress(
                        BuildEventId.ProgressId.newBuilder().setOpaqueCount(12)))))
                .isEqualTo("Progress #12");
    }

    @Test
    void rendersTargetCompleted() {
        assertThat(EventIdDisplay.of(id(b -> b.setTargetCompleted(
                        BuildEventId.TargetCompletedId.newBuilder().setLabel("//foo:bar")))))
                .isEqualTo("TargetCompleted //foo:bar");

        assertThat(EventIdDisplay.of(id(b -> b.setTargetCompleted(
                        BuildEventId.TargetCompletedId.newBuilder()
                                .setLabel("//foo:bar")
                                .setAspect("ObjcProtoAspect")
                                .setConfiguration(config("k8-fastbuild"))))))
                .isEqualTo("TargetCompleted //foo:bar aspect ObjcProtoAspect [cfg k8-fastbuild]");
    }

    @Test
    void rendersTargetConfigured() {
        assertThat(EventIdDisplay.of(id(b -> b.setTargetConfigured(
                        BuildEventId.TargetConfiguredId.newBuilder().setLabel("//foo:bar")))))
                .isEqualTo("TargetConfigured //foo:bar");
    }

    @Test
    void rendersActionCompletedByLabelAndOutput() {
        assertThat(EventIdDisplay.of(id(b -> b.setActionCompleted(
                        BuildEventId.ActionCompletedId.newBuilder()
                                .setPrimaryOutput("bazel-out/k8-fastbuild/bin/foo.o")))))
                .isEqualTo("ActionCompleted bazel-out/k8-fastbuild/bin/foo.o");

        assertThat(EventIdDisplay.of(id(b -> b.setActionCompleted(
                        BuildEventId.ActionCompletedId.newBuilder()
                                .setLabel("//foo:bar")
                                .setPrimaryOutput("bazel-out/k8-fastbuild/bin/foo.o")))))
                .isEqualTo("ActionCompleted //foo:bar (bazel-out/k8-fastbuild/bin/foo.o)");

        assertThat(EventIdDisplay.of(id(b -> b.setActionCompleted(
                        BuildEventId.ActionCompletedId.getDefaultInstance()))))
                .isEqualTo("ActionCompleted <no output>");
    }

    @Test
    void rendersTestIds() {
        assertThat(EventIdDisplay.of(id(b -> b.setTestResult(
                        BuildEventId.TestResultId.newBuilder()
                                .setLabel("//foo:test")
                                .setRun(1)
                                .setShard(2)
                                .setAttempt(3)))))
                .isEqualTo("TestResult //foo:test run 1 shard 2 attempt 3");

        assertThat(EventIdDisplay.of(id(b -> b.setTestProgress(
                        BuildEventId.TestProgressId.newBuilder()
                                .setLabel("//foo:test")
                                .setRun(1)
                                .setShard(2)
                                .setAttempt(3)
                                .setOpaqueCount(4)))))
                .isEqualTo("TestProgress //foo:test run 1 shard 2 attempt 3 #4");

        assertThat(EventIdDisplay.of(id(b -> b.setTestSummary(
                        BuildEventId.TestSummaryId.newBuilder().setLabel("//foo:test")))))
                .isEqualTo("TestSummary //foo:test");

        assertThat(EventIdDisplay.of(id(b -> b.setTargetSummary(
                        BuildEventId.TargetSummaryId.newBuilder()
                                .setLabel("//foo:bar")
                                .setConfiguration(config("k8"))))))
                .isEqualTo("TargetSummary //foo:bar [cfg k8]");
    }

    @Test
    void rendersLabelIds() {
        assertThat(EventIdDisplay.of(id(b -> b.setUnconfiguredLabel(
                        BuildEventId.UnconfiguredLabelId.newBuilder().setLabel("//foo:missing")))))
                .isEqualTo("UnconfiguredLabel //foo:missing");

        assertThat(EventIdDisplay.of(id(b -> b.setConfiguredLabel(
                        BuildEventId.ConfiguredLabelId.newBuilder()
                                .setLabel("//foo:hidden")
                                .setConfiguration(config("k8"))))))
                .isEqualTo("ConfiguredLabel //foo:hidden [cfg k8]");
    }

    @Test
    void rendersPatternsAndSummarisesLongLists() {
        assertThat(EventIdDisplay.of(id(b -> b.setPattern(
                        BuildEventId.PatternExpandedId.newBuilder().addPattern("//foo/...")))))
                .isEqualTo("PatternExpanded //foo/...");

        assertThat(EventIdDisplay.of(id(b -> b.setPatternSkipped(
                        BuildEventId.PatternExpandedId.newBuilder().addPattern("//foo/...")))))
                .isEqualTo("PatternSkipped //foo/...");

        BuildEventId manyPatterns = id(b -> {
            BuildEventId.PatternExpandedId.Builder pattern = BuildEventId.PatternExpandedId.newBuilder();
            for (int i = 0; i < 7; i++) {
                pattern.addPattern("//p" + i + "/...");
            }
            b.setPattern(pattern);
        });
        assertThat(EventIdDisplay.of(manyPatterns))
                .isEqualTo("PatternExpanded //p0/... //p1/... //p2/... (+4 more)");

        assertThat(EventIdDisplay.of(id(b -> b.setPattern(
                        BuildEventId.PatternExpandedId.getDefaultInstance()))))
                .isEqualTo("PatternExpanded <no patterns>");
    }

    @Test
    void rendersOpaqueAndSingletonIds() {
        assertThat(EventIdDisplay.of(id(b -> b.setUnknown(
                        BuildEventId.UnknownBuildEventId.newBuilder().setDetails("scratch")))))
                .isEqualTo("UnknownBuildEvent scratch");
        assertThat(EventIdDisplay.of(id(b -> b.setUnknown(
                        BuildEventId.UnknownBuildEventId.getDefaultInstance()))))
                .isEqualTo("UnknownBuildEvent");
        assertThat(EventIdDisplay.of(id(b -> b.setStarted(
                        BuildEventId.BuildStartedId.getDefaultInstance()))))
                .isEqualTo("BuildStarted");
        assertThat(EventIdDisplay.of(id(b -> b.setUnstructuredCommandLine(
                        BuildEventId.UnstructuredCommandLineId.getDefaultInstance()))))
                .isEqualTo("UnstructuredCommandLine");
        assertThat(EventIdDisplay.of(id(b -> b.setStructuredCommandLine(
                        BuildEventId.StructuredCommandLineId.newBuilder()
                                .setCommandLineLabel("canonical")))))
                .isEqualTo("StructuredCommandLine canonical");
        assertThat(EventIdDisplay.of(id(b -> b.setWorkspaceStatus(
                        BuildEventId.WorkspaceStatusId.getDefaultInstance()))))
                .isEqualTo("WorkspaceStatus");
        assertThat(EventIdDisplay.of(id(b -> b.setOptionsParsed(
                        BuildEventId.OptionsParsedId.getDefaultInstance()))))
                .isEqualTo("OptionsParsed");
        assertThat(EventIdDisplay.of(id(b -> b.setFetch(
                        BuildEventId.FetchId.newBuilder().setUrl("https://example.test/x.tar.gz")))))
                .isEqualTo("Fetch https://example.test/x.tar.gz");
        assertThat(EventIdDisplay.of(id(b -> b.setConfiguration(config("k8-fastbuild")))))
                .isEqualTo("Configuration k8-fastbuild");
        assertThat(EventIdDisplay.of(id(b -> b.setNamedSet(
                        BuildEventId.NamedSetOfFilesId.newBuilder().setId("42")))))
                .isEqualTo("NamedSetOfFiles 42");
        assertThat(EventIdDisplay.of(id(b -> b.setBuildFinished(
                        BuildEventId.BuildFinishedId.getDefaultInstance()))))
                .isEqualTo("BuildFinished");
        assertThat(EventIdDisplay.of(id(b -> b.setBuildToolLogs(
                        BuildEventId.BuildToolLogsId.getDefaultInstance()))))
                .isEqualTo("BuildToolLogs");
        assertThat(EventIdDisplay.of(id(b -> b.setBuildMetrics(
                        BuildEventId.BuildMetricsId.getDefaultInstance()))))
                .isEqualTo("BuildMetrics");
        assertThat(EventIdDisplay.of(id(b -> b.setWorkspace(
                        BuildEventId.WorkspaceConfigId.getDefaultInstance()))))
                .isEqualTo("WorkspaceConfig");
        assertThat(EventIdDisplay.of(id(b -> b.setBuildMetadata(
                        BuildEventId.BuildMetadataId.getDefaultInstance()))))
                .isEqualTo("BuildMetadata");
        assertThat(EventIdDisplay.of(id(b -> b.setConvenienceSymlinksIdentified(
                        BuildEventId.ConvenienceSymlinksIdentifiedId.getDefaultInstance()))))
                .isEqualTo("ConvenienceSymlinksIdentified");
        assertThat(EventIdDisplay.of(id(b -> b.setExecRequest(
                        BuildEventId.ExecRequestId.getDefaultInstance()))))
                .isEqualTo("ExecRequest");
    }

    @Test
    void anAbsentLabelIsMarkedNotBlanked() {
        assertThat(EventIdDisplay.of(id(b -> b.setTargetCompleted(
                        BuildEventId.TargetCompletedId.getDefaultInstance()))))
                .isEqualTo("TargetCompleted " + EventIdDisplay.ABSENT_LABEL);
    }

    @Test
    void anEmptyIdSaysSoRatherThanRenderingBlank() {
        assertThat(EventIdDisplay.of(BuildEventId.getDefaultInstance())).isEqualTo("<empty id>");
    }

    @Test
    void anUnrecognisedVariantNamesItsFieldNumber() throws InvalidProtocolBufferException {
        BuildEventId future = BuildEventId.parseFrom(stringField(FUTURE_ID_FIELD, "//foo:bar"));

        assertThat(EventIdDisplay.of(future))
                .isEqualTo("<unrecognised id kind " + FUTURE_ID_FIELD + ">");
    }

    @Test
    void everyVariantRendersSomethingNonBlank() {
        for (BuildEventId.IdCase idCase : BuildEventId.IdCase.values()) {
            BuildEventId id;
            if (idCase == BuildEventId.IdCase.ID_NOT_SET) {
                id = BuildEventId.getDefaultInstance();
            } else {
                var field = BuildEventId.getDescriptor().findFieldByNumber(idCase.getNumber());
                id = BuildEventId.newBuilder()
                        .setField(field, BuildEventId.newBuilder().newBuilderForField(field).build())
                        .build();
            }

            assertThat(EventIdDisplay.of(id)).as("display of %s", idCase).isNotBlank();
        }
    }

    @Test
    void aRunawayPatternListIsCappedVisibly() {
        String longPattern = "//" + "a".repeat(400) + "/...";
        BuildEventId id = id(b -> b.setPattern(
                BuildEventId.PatternExpandedId.newBuilder().addPattern(longPattern)));

        String display = EventIdDisplay.of(id);

        assertThat(display).startsWith("PatternExpanded //aaa");
        assertThat(display).contains(" chars)");
        assertThat(display.length())
                .isLessThanOrEqualTo(EventIdDisplay.MAX_DISPLAY_CHARS + 24);
    }

    @Test
    void hugeIdsStillProduceAKeyAndADisplayTogether() {
        // The display is capped; the identity is not. They are independent, and
        // capping the one must not disturb the other.
        String longLabel = "//" + "b".repeat(1000) + ":target";
        BuildEventId id = id(b -> b.setTargetCompleted(
                BuildEventId.TargetCompletedId.newBuilder().setLabel(longLabel)));

        ByteString wire = id.toByteString();
        assertThat(EventIdKey.of(id).idBytes()).isEqualTo(wire);
        assertThat(EventIdDisplay.of(id).length())
                .isLessThanOrEqualTo(EventIdDisplay.MAX_DISPLAY_CHARS + 24);
    }
}
