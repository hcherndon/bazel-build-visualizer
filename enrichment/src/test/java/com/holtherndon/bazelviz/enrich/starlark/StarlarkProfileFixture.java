package com.holtherndon.bazelviz.enrich.starlark;

import com.google.perftools.profiles.ProfileProto;
import com.google.protobuf.CodedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.GZIPOutputStream;

/** Small pprof records whose expected aggregates are easy to inspect by hand. */
final class StarlarkProfileFixture {

  private StarlarkProfileFixture() {}

  static ProfileProto.Profile standard() {
    ProfileProto.ValueType cpu = ProfileProto.ValueType.newBuilder().setType(1).setUnit(2).build();
    ProfileProto.Function root =
        ProfileProto.Function.newBuilder()
            .setId(101)
            .setName(3)
            .setSystemName(3)
            .setFilename(5)
            .setStartLine(1)
            .build();
    ProfileProto.Function leaf =
        ProfileProto.Function.newBuilder()
            .setId(102)
            .setName(4)
            .setSystemName(4)
            .setFilename(6)
            .setStartLine(2)
            .build();
    ProfileProto.Mapping mapping =
        ProfileProto.Mapping.newBuilder()
            .setId(301)
            .setFilename(5)
            .setBuildId(0)
            .setHasFunctions(true)
            .setHasFilenames(true)
            .setHasLineNumbers(true)
            .build();
    ProfileProto.Location rootLocation = location(201, 301, 101, 10);
    ProfileProto.Location leafLocation = location(202, 301, 102, 20);
    ProfileProto.Location recursiveRoot = location(203, 301, 101, 30);
    ProfileProto.Sample first =
        ProfileProto.Sample.newBuilder()
            .addLocationId(202)
            .addLocationId(201)
            .addValue(10)
            .addLabel(ProfileProto.Label.newBuilder().setKey(7).setStr(8))
            .build();
    ProfileProto.Sample recursive =
        ProfileProto.Sample.newBuilder()
            .addLocationId(202)
            .addLocationId(203)
            .addLocationId(201)
            .addValue(20)
            .addLabel(ProfileProto.Label.newBuilder().setKey(7).setNum(2).setNumUnit(9))
            .build();

    return ProfileProto.Profile.newBuilder()
        .addSampleType(cpu)
        .addSample(first)
        .addSample(recursive)
        .addMapping(mapping)
        .addLocation(rootLocation)
        .addLocation(leafLocation)
        .addLocation(recursiveRoot)
        .addFunction(root)
        .addFunction(leaf)
        .addAllStringTable(
            List.of(
                "",
                "CPU",
                "microseconds",
                "root",
                "leaf",
                "root.bzl",
                "leaf.bzl",
                "kind",
                "analysis",
                "count"))
        .setTimeNanos(1_000_000)
        .setDurationNanos(30_000_000)
        .setPeriodType(cpu)
        .setPeriod(10_000)
        .addComment(8)
        .setDefaultSampleType(1)
        .build();
  }

  static ProfileProto.Profile oneSample(long value) {
    ProfileProto.Profile standard = standard();
    return standard.toBuilder()
        .clearSample()
        .addSample(
            ProfileProto.Sample.newBuilder().addLocationId(202).addLocationId(201).addValue(value))
        .build();
  }

  static ProfileProto.Profile manySamples(int count) {
    ProfileProto.Profile.Builder builder = standard().toBuilder().clearSample();
    ProfileProto.Sample sample =
        ProfileProto.Sample.newBuilder().addLocationId(202).addValue(1).build();
    for (int index = 0; index < count; index++) {
      builder.addSample(sample);
    }
    return builder.build();
  }

  static ProfileProto.Profile missingLocation() {
    return standard().toBuilder()
        .clearSample()
        .addSample(ProfileProto.Sample.newBuilder().addLocationId(999).addValue(5))
        .build();
  }

  static ProfileProto.Profile wrongValueCardinality() {
    return standard().toBuilder()
        .clearSample()
        .addSample(ProfileProto.Sample.newBuilder().addLocationId(202))
        .build();
  }

  static ProfileProto.Profile invalidFunctionId() {
    ProfileProto.Profile standard = standard();
    return standard.toBuilder()
        .setFunction(0, standard.getFunction(0).toBuilder().setId(0))
        .build();
  }

  static ProfileProto.Profile expandedChildLists() {
    ProfileProto.Profile standard = standard();
    ProfileProto.Sample sample =
        standard.getSample(0).toBuilder()
            .addValue(11)
            .addLabel(ProfileProto.Label.newBuilder().setKey(7).setStr(8))
            .build();
    ProfileProto.Location location =
        standard.getLocation(0).toBuilder()
            .addLine(ProfileProto.Line.newBuilder().setFunctionId(101).setLine(11))
            .build();
    return standard.toBuilder().setSample(0, sample).setLocation(0, location).build();
  }

  static ProfileProto.Profile periodInNanoseconds() {
    ProfileProto.Profile standard = standard();
    int nanoseconds = standard.getStringTableCount();
    return standard.toBuilder()
        .addStringTable("nanoseconds")
        .setPeriodType(standard.getPeriodType().toBuilder().setUnit(nanoseconds))
        .setPeriod(10_000_000)
        .build();
  }

  static ProfileProto.Profile subMicrosecondPeriod() {
    return periodInNanoseconds().toBuilder().setPeriod(1).build();
  }

  static ProfileProto.Profile mixedSymbolAttribution() {
    ProfileProto.Profile standard = standard();
    int inlineName = standard.getStringTableCount();
    int inlineFile = inlineName + 1;
    int noFileName = inlineName + 2;
    ProfileProto.Function inline =
        ProfileProto.Function.newBuilder()
            .setId(103)
            .setName(inlineName)
            .setSystemName(inlineName)
            .setFilename(inlineFile)
            .setStartLine(3)
            .build();
    ProfileProto.Function noFile =
        ProfileProto.Function.newBuilder()
            .setId(104)
            .setName(noFileName)
            .setSystemName(noFileName)
            .setFilename(0)
            .setStartLine(4)
            .build();
    ProfileProto.Function noName =
        ProfileProto.Function.newBuilder()
            .setId(105)
            .setName(0)
            .setSystemName(0)
            .setFilename(6)
            .setStartLine(5)
            .build();
    ProfileProto.Location inlineLeaf =
        standard.getLocation(1).toBuilder()
            .addLine(ProfileProto.Line.newBuilder().setFunctionId(103).setLine(21))
            .build();
    ProfileProto.Location missing = ProfileProto.Location.newBuilder().setId(204).build();
    ProfileProto.Location noFileLocation = location(205, 301, 104, 40);
    ProfileProto.Location noNameLocation = location(206, 301, 105, 50);

    return standard.toBuilder()
        .clearSample()
        .addSample(sample(10, 202, 201))
        .addSample(sample(20, 204, 201))
        .addSample(sample(30, 201))
        .addSample(sample(40, 205, 201))
        .addSample(sample(50, 206, 201))
        .setLocation(1, inlineLeaf)
        .addLocation(missing)
        .addLocation(noFileLocation)
        .addLocation(noNameLocation)
        .addFunction(inline)
        .addFunction(noFile)
        .addFunction(noName)
        .addStringTable("inline")
        .addStringTable("inline.bzl")
        .addStringTable("no_file")
        .build();
  }

  static Path writePacked(Path path, ProfileProto.Profile profile) throws IOException {
    return gzip(path, profile.toByteArray());
  }

  /** Encodes Sample.location_id/value as repeated varints, matching Bazel's writer. */
  static Path writeUnpacked(Path path, ProfileProto.Profile profile) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    CodedOutputStream output = CodedOutputStream.newInstance(bytes);
    for (ProfileProto.ValueType type : profile.getSampleTypeList()) {
      output.writeMessage(ProfileProto.Profile.SAMPLE_TYPE_FIELD_NUMBER, type);
    }
    for (ProfileProto.Sample sample : profile.getSampleList()) {
      ByteArrayOutputStream sampleBytes = new ByteArrayOutputStream();
      CodedOutputStream sampleOutput = CodedOutputStream.newInstance(sampleBytes);
      for (long locationId : sample.getLocationIdList()) {
        sampleOutput.writeUInt64(ProfileProto.Sample.LOCATION_ID_FIELD_NUMBER, locationId);
      }
      for (long value : sample.getValueList()) {
        sampleOutput.writeInt64(ProfileProto.Sample.VALUE_FIELD_NUMBER, value);
      }
      for (ProfileProto.Label label : sample.getLabelList()) {
        sampleOutput.writeMessage(ProfileProto.Sample.LABEL_FIELD_NUMBER, label);
      }
      sampleOutput.flush();
      output.writeByteArray(ProfileProto.Profile.SAMPLE_FIELD_NUMBER, sampleBytes.toByteArray());
    }
    for (ProfileProto.Mapping mapping : profile.getMappingList()) {
      output.writeMessage(ProfileProto.Profile.MAPPING_FIELD_NUMBER, mapping);
    }
    for (ProfileProto.Location location : profile.getLocationList()) {
      output.writeMessage(ProfileProto.Profile.LOCATION_FIELD_NUMBER, location);
    }
    for (ProfileProto.Function function : profile.getFunctionList()) {
      output.writeMessage(ProfileProto.Profile.FUNCTION_FIELD_NUMBER, function);
    }
    for (String string : profile.getStringTableList()) {
      output.writeString(ProfileProto.Profile.STRING_TABLE_FIELD_NUMBER, string);
    }
    if (profile.getDropFrames() != 0) {
      output.writeInt64(ProfileProto.Profile.DROP_FRAMES_FIELD_NUMBER, profile.getDropFrames());
    }
    if (profile.getKeepFrames() != 0) {
      output.writeInt64(ProfileProto.Profile.KEEP_FRAMES_FIELD_NUMBER, profile.getKeepFrames());
    }
    if (profile.getTimeNanos() != 0) {
      output.writeInt64(ProfileProto.Profile.TIME_NANOS_FIELD_NUMBER, profile.getTimeNanos());
    }
    if (profile.getDurationNanos() != 0) {
      output.writeInt64(
          ProfileProto.Profile.DURATION_NANOS_FIELD_NUMBER, profile.getDurationNanos());
    }
    if (profile.hasPeriodType()) {
      output.writeMessage(ProfileProto.Profile.PERIOD_TYPE_FIELD_NUMBER, profile.getPeriodType());
    }
    if (profile.getPeriod() != 0) {
      output.writeInt64(ProfileProto.Profile.PERIOD_FIELD_NUMBER, profile.getPeriod());
    }
    for (long comment : profile.getCommentList()) {
      output.writeInt64(ProfileProto.Profile.COMMENT_FIELD_NUMBER, comment);
    }
    if (profile.getDefaultSampleType() != 0) {
      output.writeInt64(
          ProfileProto.Profile.DEFAULT_SAMPLE_TYPE_FIELD_NUMBER, profile.getDefaultSampleType());
    }
    if (profile.getDocUrl() != 0) {
      output.writeInt64(ProfileProto.Profile.DOC_URL_FIELD_NUMBER, profile.getDocUrl());
    }
    output.flush();
    return gzip(path, bytes.toByteArray());
  }

  static Path writeUnknownLengthDelimitedFields(
      Path path, int fieldNumber, int payloadBytes, int count) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    CodedOutputStream output = CodedOutputStream.newInstance(bytes);
    byte[] payload = new byte[payloadBytes];
    for (int index = 0; index < count; index++) {
      output.writeByteArray(fieldNumber, payload);
    }
    output.flush();
    return gzip(path, bytes.toByteArray());
  }

  private static ProfileProto.Location location(
      long id, long mappingId, long functionId, long line) {
    return ProfileProto.Location.newBuilder()
        .setId(id)
        .setMappingId(mappingId)
        .addLine(ProfileProto.Line.newBuilder().setFunctionId(functionId).setLine(line))
        .build();
  }

  private static ProfileProto.Sample sample(long value, long... locations) {
    ProfileProto.Sample.Builder sample = ProfileProto.Sample.newBuilder().addValue(value);
    for (long location : locations) {
      sample.addLocationId(location);
    }
    return sample.build();
  }

  private static Path gzip(Path path, byte[] bytes) throws IOException {
    try (GZIPOutputStream output = new GZIPOutputStream(Files.newOutputStream(path))) {
      output.write(bytes);
    }
    return path;
  }
}
