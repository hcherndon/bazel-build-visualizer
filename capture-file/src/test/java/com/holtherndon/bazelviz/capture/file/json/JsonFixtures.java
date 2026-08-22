package com.holtherndon.bazelviz.capture.file.json;

import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildEvent;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildEventId;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildFinished;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildStarted;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.Progress;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.UnstructuredCommandLine;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Timestamp;
import com.google.protobuf.util.JsonFormat;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared fixtures for the JSON parser tests.
 *
 * <p>The events are printed with protobuf's own JSON printer rather than
 * hand-written, so the fixtures are encoded exactly the way Bazel encodes BEP
 * — including how it escapes a command line full of quotes and braces, which is
 * the case the record splitter has to survive.
 */
final class JsonFixtures {

    private JsonFixtures() {}

    /**
     * A command line with the two hazards a naive splitter trips on together:
     * literal braces and escaped quotes inside one string value. Real builds
     * produce this constantly — any {@code --define} of a JSON blob or any
     * genrule command does it.
     */
    static final String HAZARDOUS_COMMAND_ARG =
            "bash -c 'echo \"{\\\"key\\\": \\\"value\\\"}\" > out' # } { \\ end";

    /** Four events covering an id, a payload oneof, repeated children and the hazard string. */
    static List<BuildEvent> sampleEvents() {
        List<BuildEvent> events = new ArrayList<>();
        events.add(BuildEvent.newBuilder()
                .setId(BuildEventId.newBuilder()
                        .setStarted(BuildEventId.BuildStartedId.getDefaultInstance()))
                .addChildren(BuildEventId.newBuilder()
                        .setProgress(BuildEventId.ProgressId.newBuilder().setOpaqueCount(1)))
                .setStarted(BuildStarted.newBuilder()
                        .setUuid("11111111-2222-3333-4444-555555555555")
                        .setCommand("build")
                        .setBuildToolVersion("8.0.0")
                        // A well-known type, so the fixtures also cover the
                        // RFC 3339 rendering protobuf-JSON uses for timestamps.
                        .setStartTime(Timestamp.newBuilder().setSeconds(1_700_000_000L)))
                .build());
        events.add(BuildEvent.newBuilder()
                .setId(BuildEventId.newBuilder()
                        .setUnstructuredCommandLine(
                                BuildEventId.UnstructuredCommandLineId.getDefaultInstance()))
                .setUnstructuredCommandLine(UnstructuredCommandLine.newBuilder()
                        .addArgs("build")
                        .addArgs("//...")
                        .addArgs(HAZARDOUS_COMMAND_ARG))
                .build());
        events.add(BuildEvent.newBuilder()
                .setId(BuildEventId.newBuilder()
                        .setProgress(BuildEventId.ProgressId.newBuilder().setOpaqueCount(1)))
                .setProgress(Progress.newBuilder()
                        .setStderr("ERROR: /w/BUILD:3:1: Executing genrule //p:g failed: "
                                + "bash -c 'printf \"{\\\"a\\\": 1}\"' } { \\")
                        .setStdout("INFO: Analyzed 1 target."))
                .build());
        events.add(BuildEvent.newBuilder()
                .setId(BuildEventId.newBuilder()
                        .setBuildFinished(BuildEventId.BuildFinishedId.getDefaultInstance()))
                .setLastMessage(true)
                .setFinished(BuildFinished.newBuilder()
                        .setExitCode(BuildFinished.ExitCode.newBuilder()
                                .setName("SUCCESS")
                                .setCode(0)))
                .build());
        return events;
    }

    /** One object per line, the layout Bazel's default printer emits. */
    static String oneObjectPerLine(List<BuildEvent> events) throws InvalidProtocolBufferException {
        JsonFormat.Printer printer = JsonFormat.printer().omittingInsignificantWhitespace();
        StringBuilder out = new StringBuilder();
        for (BuildEvent event : events) {
            out.append(printer.print(event)).append('\n');
        }
        return out.toString();
    }

    /** Multi-line objects concatenated with no separator, as pretty-printing produces. */
    static String prettyPrinted(List<BuildEvent> events) throws InvalidProtocolBufferException {
        JsonFormat.Printer printer = JsonFormat.printer();
        StringBuilder out = new StringBuilder();
        for (BuildEvent event : events) {
            out.append(printer.print(event)).append('\n');
        }
        return out.toString();
    }

    /** Pretty-printed with no whitespace at all between objects: {@code }{ } back to back. */
    static String prettyPrintedBackToBack(List<BuildEvent> events)
            throws InvalidProtocolBufferException {
        JsonFormat.Printer printer = JsonFormat.printer();
        StringBuilder out = new StringBuilder();
        for (BuildEvent event : events) {
            out.append(printer.print(event));
        }
        return out.toString();
    }
}
