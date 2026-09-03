package com.holtherndon.bazelviz.enrich.starlark;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.WireFormat;
import java.io.BufferedInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.zip.GZIPInputStream;

/**
 * Bounded streaming reader for gzip-compressed pprof files emitted by Bazel's {@code
 * --starlark_cpu_profile} flag.
 *
 * <p>The outer {@code Profile} message is deliberately never materialized. One embedded record is
 * decoded at a time and handed to a sink. This both accepts Bazel's unpacked repeated fields and
 * ordinary proto3 packed fields, and keeps memory proportional to the largest bounded record.
 */
public final class StarlarkCpuProfileParser {

  /** Maximum bytes produced by gzip for one profile. */
  public static final long MAX_DECOMPRESSED_BYTES = 1L << 30;

  /** Maximum encoded size of one embedded record or string. */
  public static final int MAX_RECORD_BYTES = 16 << 20;

  /** Maximum number of locations in one sampled stack. */
  public static final int MAX_STACK_DEPTH = 4_096;

  /** Maximum measurement dimensions in one sample. Bazel currently writes one. */
  public static final int MAX_SAMPLE_VALUES = 1_024;

  /** Maximum labels attached to one sample. Bazel currently writes none. */
  public static final int MAX_SAMPLE_LABELS = 4_096;

  /** Maximum inline source frames represented by one pprof location. */
  public static final int MAX_LOCATION_LINES = 4_096;

  /** Maximum repeated records in the outer profile message. */
  public static final long MAX_TOP_LEVEL_RECORDS = 25_000_000L;

  /** Maximum normalized line, frame, value, and label records combined. */
  public static final long MAX_CHILD_RECORDS = 100_000_000L;

  static final Limits DEFAULT_LIMITS =
      new Limits(
          MAX_DECOMPRESSED_BYTES,
          MAX_RECORD_BYTES,
          MAX_STACK_DEPTH,
          MAX_SAMPLE_VALUES,
          MAX_SAMPLE_LABELS,
          MAX_LOCATION_LINES,
          MAX_TOP_LEVEL_RECORDS,
          MAX_CHILD_RECORDS);

  private final Limits limits;
  private long topLevelRecords;
  private long childRecords;

  public StarlarkCpuProfileParser() {
    this(DEFAULT_LIMITS);
  }

  StarlarkCpuProfileParser(Limits limits) {
    this.limits = Objects.requireNonNull(limits, "limits");
  }

  /** Streams {@code file} into {@code sink}, returning exact input counts and scalar metadata. */
  ParseResult parse(Path file, Sink sink) throws IOException {
    Objects.requireNonNull(file, "file");
    Objects.requireNonNull(sink, "sink");
    topLevelRecords = 0;
    childRecords = 0;

    long compressedBytes = Files.size(file);
    MetadataBuilder metadata = new MetadataBuilder();
    try (InputStream raw = new BufferedInputStream(Files.newInputStream(file), 1 << 16);
        GZIPInputStream gzip = new GZIPInputStream(raw, 1 << 16);
        LimitedInputStream bounded = new LimitedInputStream(gzip, limits.maxDecompressedBytes())) {
      CodedInputStream input = CodedInputStream.newInstance(bounded);
      // The counting stream supplies the user-facing bound and error. Leave protobuf's
      // internal int-sized ceiling at its maximum so it cannot fail first with a generic
      // message when tests or future product settings use a smaller bound.
      input.setSizeLimit(Integer.MAX_VALUE);
      parseProfile(input, sink, metadata);
      return new ParseResult(
          compressedBytes, bounded.bytesRead(), topLevelRecords, childRecords, metadata.build());
    }
  }

  private void parseProfile(CodedInputStream input, Sink sink, MetadataBuilder metadata)
      throws IOException {
    while (true) {
      int tag = input.readTag();
      if (tag == 0) {
        return;
      }
      int field = WireFormat.getTagFieldNumber(tag);
      switch (field) {
        case 1 -> {
          requireWire(tag, WireFormat.WIRETYPE_LENGTH_DELIMITED, "sample_type");
          incrementTop("sample types");
          sink.sampleType(readMessage(input, "sample_type", this::readValueType));
        }
        case 2 -> {
          requireWire(tag, WireFormat.WIRETYPE_LENGTH_DELIMITED, "sample");
          incrementTop("samples");
          sink.sample(readMessage(input, "sample", this::readSample));
        }
        case 3 -> {
          requireWire(tag, WireFormat.WIRETYPE_LENGTH_DELIMITED, "mapping");
          incrementTop("mappings");
          sink.mapping(readMessage(input, "mapping", this::readMapping));
        }
        case 4 -> {
          requireWire(tag, WireFormat.WIRETYPE_LENGTH_DELIMITED, "location");
          incrementTop("locations");
          sink.location(readMessage(input, "location", this::readLocation));
        }
        case 5 -> {
          requireWire(tag, WireFormat.WIRETYPE_LENGTH_DELIMITED, "function");
          incrementTop("functions");
          sink.function(readMessage(input, "function", this::readFunction));
        }
        case 6 -> {
          requireWire(tag, WireFormat.WIRETYPE_LENGTH_DELIMITED, "string_table");
          incrementTop("strings");
          sink.string(readUtf8(input, "string_table entry"));
        }
        case 7 -> {
          requireWire(tag, WireFormat.WIRETYPE_VARINT, "drop_frames");
          metadata.dropFrames = input.readInt64();
        }
        case 8 -> {
          requireWire(tag, WireFormat.WIRETYPE_VARINT, "keep_frames");
          metadata.keepFrames = input.readInt64();
        }
        case 9 -> {
          requireWire(tag, WireFormat.WIRETYPE_VARINT, "time_nanos");
          metadata.timeNanos = input.readInt64();
        }
        case 10 -> {
          requireWire(tag, WireFormat.WIRETYPE_VARINT, "duration_nanos");
          metadata.durationNanos = input.readInt64();
        }
        case 11 -> {
          requireWire(tag, WireFormat.WIRETYPE_LENGTH_DELIMITED, "period_type");
          metadata.periodType = readMessage(input, "period_type", this::readValueType);
        }
        case 12 -> {
          requireWire(tag, WireFormat.WIRETYPE_VARINT, "period");
          metadata.period = input.readInt64();
        }
        case 13 ->
            readRepeatedInt64(
                tag,
                input,
                "comment",
                value -> {
                  incrementTop("comments");
                  sink.comment(value);
                });
        case 14 -> {
          requireWire(tag, WireFormat.WIRETYPE_VARINT, "default_sample_type");
          metadata.defaultSampleType = input.readInt64();
        }
        case 15 -> {
          requireWire(tag, WireFormat.WIRETYPE_VARINT, "doc_url");
          metadata.docUrl = input.readInt64();
        }
        default -> skipUnknown(input, tag, 0);
      }
    }
  }

  private ValueTypeRecord readValueType(CodedInputStream input) throws IOException {
    long type = 0;
    long unit = 0;
    while (true) {
      int tag = input.readTag();
      if (tag == 0) {
        return new ValueTypeRecord(type, unit);
      }
      switch (WireFormat.getTagFieldNumber(tag)) {
        case 1 -> {
          requireWire(tag, WireFormat.WIRETYPE_VARINT, "value_type.type");
          type = input.readInt64();
        }
        case 2 -> {
          requireWire(tag, WireFormat.WIRETYPE_VARINT, "value_type.unit");
          unit = input.readInt64();
        }
        default -> skipUnknown(input, tag, 0);
      }
    }
  }

  private SampleRecord readSample(CodedInputStream input) throws IOException {
    LongArrayBuilder locations = new LongArrayBuilder();
    LongArrayBuilder values = new LongArrayBuilder();
    List<LabelRecord> labels = new ArrayList<>();
    while (true) {
      int tag = input.readTag();
      if (tag == 0) {
        if (locations.size() > limits.maxStackDepth()) {
          throw malformed(
              "sample stack depth "
                  + locations.size()
                  + " exceeds limit "
                  + limits.maxStackDepth());
        }
        return new SampleRecord(locations.toArray(), values.toArray(), List.copyOf(labels));
      }
      switch (WireFormat.getTagFieldNumber(tag)) {
        case 1 ->
            readRepeatedUInt64(
                tag,
                input,
                "sample.location_id",
                value -> {
                  incrementChild("sample frames");
                  if (locations.size() >= limits.maxStackDepth()) {
                    throw malformed("sample stack depth exceeds limit " + limits.maxStackDepth());
                  }
                  locations.add(requireSqliteUnsigned(value, "location id"));
                });
        case 2 ->
            readRepeatedInt64(
                tag,
                input,
                "sample.value",
                value -> {
                  incrementChild("sample values");
                  if (values.size() >= limits.maxSampleValues()) {
                    throw malformed("sample value count exceeds limit " + limits.maxSampleValues());
                  }
                  values.add(value);
                });
        case 3 -> {
          requireWire(tag, WireFormat.WIRETYPE_LENGTH_DELIMITED, "sample.label");
          incrementChild("sample labels");
          if (labels.size() >= limits.maxSampleLabels()) {
            throw malformed("sample label count exceeds limit " + limits.maxSampleLabels());
          }
          labels.add(readMessage(input, "sample.label", this::readLabel));
        }
        default -> skipUnknown(input, tag, 0);
      }
    }
  }

  private LabelRecord readLabel(CodedInputStream input) throws IOException {
    long key = 0;
    Long stringValue = null;
    Long numericValue = null;
    Long numericUnit = null;
    while (true) {
      int tag = input.readTag();
      if (tag == 0) {
        if (stringValue != null && numericValue != null) {
          throw malformed("sample label contains both string and numeric values");
        }
        if (numericUnit != null && numericValue == null) {
          throw malformed("sample label has a numeric unit without a numeric value");
        }
        return new LabelRecord(key, stringValue, numericValue, numericUnit);
      }
      switch (WireFormat.getTagFieldNumber(tag)) {
        case 1 -> {
          requireWire(tag, WireFormat.WIRETYPE_VARINT, "label.key");
          key = input.readInt64();
        }
        case 2 -> {
          requireWire(tag, WireFormat.WIRETYPE_VARINT, "label.str");
          stringValue = input.readInt64();
        }
        case 3 -> {
          requireWire(tag, WireFormat.WIRETYPE_VARINT, "label.num");
          numericValue = input.readInt64();
        }
        case 4 -> {
          requireWire(tag, WireFormat.WIRETYPE_VARINT, "label.num_unit");
          numericUnit = input.readInt64();
        }
        default -> skipUnknown(input, tag, 0);
      }
    }
  }

  private MappingRecord readMapping(CodedInputStream input) throws IOException {
    long id = 0;
    Long memoryStart = null;
    Long memoryLimit = null;
    Long fileOffset = null;
    Long filename = null;
    Long buildId = null;
    boolean hasFunctions = false;
    boolean hasFilenames = false;
    boolean hasLineNumbers = false;
    boolean hasInlineFrames = false;
    while (true) {
      int tag = input.readTag();
      if (tag == 0) {
        return new MappingRecord(
            id,
            memoryStart,
            memoryLimit,
            fileOffset,
            filename,
            buildId,
            hasFunctions,
            hasFilenames,
            hasLineNumbers,
            hasInlineFrames);
      }
      switch (WireFormat.getTagFieldNumber(tag)) {
        case 1 -> {
          requireWire(tag, WireFormat.WIRETYPE_VARINT, "mapping.id");
          id = requireSqliteUnsigned(input.readUInt64(), "mapping id");
        }
        case 2 -> {
          requireWire(tag, WireFormat.WIRETYPE_VARINT, "mapping.memory_start");
          memoryStart = requireSqliteUnsigned(input.readUInt64(), "mapping memory_start");
        }
        case 3 -> {
          requireWire(tag, WireFormat.WIRETYPE_VARINT, "mapping.memory_limit");
          memoryLimit = requireSqliteUnsigned(input.readUInt64(), "mapping memory_limit");
        }
        case 4 -> {
          requireWire(tag, WireFormat.WIRETYPE_VARINT, "mapping.file_offset");
          fileOffset = requireSqliteUnsigned(input.readUInt64(), "mapping file_offset");
        }
        case 5 -> {
          requireWire(tag, WireFormat.WIRETYPE_VARINT, "mapping.filename");
          filename = input.readInt64();
        }
        case 6 -> {
          requireWire(tag, WireFormat.WIRETYPE_VARINT, "mapping.build_id");
          buildId = input.readInt64();
        }
        case 7 -> {
          requireWire(tag, WireFormat.WIRETYPE_VARINT, "mapping.has_functions");
          hasFunctions = input.readBool();
        }
        case 8 -> {
          requireWire(tag, WireFormat.WIRETYPE_VARINT, "mapping.has_filenames");
          hasFilenames = input.readBool();
        }
        case 9 -> {
          requireWire(tag, WireFormat.WIRETYPE_VARINT, "mapping.has_line_numbers");
          hasLineNumbers = input.readBool();
        }
        case 10 -> {
          requireWire(tag, WireFormat.WIRETYPE_VARINT, "mapping.has_inline_frames");
          hasInlineFrames = input.readBool();
        }
        default -> skipUnknown(input, tag, 0);
      }
    }
  }

  private LocationRecord readLocation(CodedInputStream input) throws IOException {
    long id = 0;
    Long mappingId = null;
    Long address = null;
    List<LineRecord> lines = new ArrayList<>();
    boolean folded = false;
    while (true) {
      int tag = input.readTag();
      if (tag == 0) {
        return new LocationRecord(id, mappingId, address, List.copyOf(lines), folded);
      }
      switch (WireFormat.getTagFieldNumber(tag)) {
        case 1 -> {
          requireWire(tag, WireFormat.WIRETYPE_VARINT, "location.id");
          id = requireSqliteUnsigned(input.readUInt64(), "location id");
        }
        case 2 -> {
          requireWire(tag, WireFormat.WIRETYPE_VARINT, "location.mapping_id");
          mappingId = requireSqliteUnsigned(input.readUInt64(), "location mapping id");
        }
        case 3 -> {
          requireWire(tag, WireFormat.WIRETYPE_VARINT, "location.address");
          address = requireSqliteUnsigned(input.readUInt64(), "location address");
        }
        case 4 -> {
          requireWire(tag, WireFormat.WIRETYPE_LENGTH_DELIMITED, "location.line");
          incrementChild("location lines");
          if (lines.size() >= limits.maxLocationLines()) {
            throw malformed("location line count exceeds limit " + limits.maxLocationLines());
          }
          lines.add(readMessage(input, "location.line", this::readLine));
        }
        case 5 -> {
          requireWire(tag, WireFormat.WIRETYPE_VARINT, "location.is_folded");
          folded = input.readBool();
        }
        default -> skipUnknown(input, tag, 0);
      }
    }
  }

  private LineRecord readLine(CodedInputStream input) throws IOException {
    long functionId = 0;
    Long line = null;
    Long column = null;
    while (true) {
      int tag = input.readTag();
      if (tag == 0) {
        return new LineRecord(functionId, line, column);
      }
      switch (WireFormat.getTagFieldNumber(tag)) {
        case 1 -> {
          requireWire(tag, WireFormat.WIRETYPE_VARINT, "line.function_id");
          functionId = requireSqliteUnsigned(input.readUInt64(), "line function id");
        }
        case 2 -> {
          requireWire(tag, WireFormat.WIRETYPE_VARINT, "line.line");
          line = input.readInt64();
        }
        case 3 -> {
          requireWire(tag, WireFormat.WIRETYPE_VARINT, "line.column");
          column = input.readInt64();
        }
        default -> skipUnknown(input, tag, 0);
      }
    }
  }

  private FunctionRecord readFunction(CodedInputStream input) throws IOException {
    long id = 0;
    long name = 0;
    long systemName = 0;
    long filename = 0;
    Long startLine = null;
    while (true) {
      int tag = input.readTag();
      if (tag == 0) {
        return new FunctionRecord(id, name, systemName, filename, startLine);
      }
      switch (WireFormat.getTagFieldNumber(tag)) {
        case 1 -> {
          requireWire(tag, WireFormat.WIRETYPE_VARINT, "function.id");
          id = requireSqliteUnsigned(input.readUInt64(), "function id");
        }
        case 2 -> {
          requireWire(tag, WireFormat.WIRETYPE_VARINT, "function.name");
          name = input.readInt64();
        }
        case 3 -> {
          requireWire(tag, WireFormat.WIRETYPE_VARINT, "function.system_name");
          systemName = input.readInt64();
        }
        case 4 -> {
          requireWire(tag, WireFormat.WIRETYPE_VARINT, "function.filename");
          filename = input.readInt64();
        }
        case 5 -> {
          requireWire(tag, WireFormat.WIRETYPE_VARINT, "function.start_line");
          startLine = input.readInt64();
        }
        default -> skipUnknown(input, tag, 0);
      }
    }
  }

  private <T> T readMessage(CodedInputStream input, String name, MessageReader<T> reader)
      throws IOException {
    int length = readLength(input, name);
    int previousLimit = input.pushLimit(length);
    try {
      T value = reader.read(input);
      if (input.getBytesUntilLimit() != 0) {
        throw malformed(name + " was not consumed completely");
      }
      input.checkLastTagWas(0);
      return value;
    } finally {
      input.popLimit(previousLimit);
    }
  }

  private String readUtf8(CodedInputStream input, String name) throws IOException {
    int length = readLength(input, name);
    byte[] bytes = input.readRawBytes(length);
    if (!isValidUtf8(bytes)) {
      throw malformed(name + " is not valid UTF-8");
    }
    return new String(bytes, StandardCharsets.UTF_8);
  }

  /** Strict UTF-8 validation without making a second record-sized byte-array copy. */
  private static boolean isValidUtf8(byte[] bytes) {
    int index = 0;
    while (index < bytes.length) {
      int first = Byte.toUnsignedInt(bytes[index++]);
      if (first <= 0x7f) {
        continue;
      }
      if (first >= 0xc2 && first <= 0xdf) {
        if (index >= bytes.length || !continuation(bytes[index++])) {
          return false;
        }
        continue;
      }
      if (first >= 0xe0 && first <= 0xef) {
        if (index + 1 >= bytes.length) {
          return false;
        }
        int second = Byte.toUnsignedInt(bytes[index++]);
        int third = Byte.toUnsignedInt(bytes[index++]);
        boolean secondValid =
            first == 0xe0
                ? second >= 0xa0 && second <= 0xbf
                : first == 0xed
                    ? second >= 0x80 && second <= 0x9f
                    : second >= 0x80 && second <= 0xbf;
        if (!secondValid || third < 0x80 || third > 0xbf) {
          return false;
        }
        continue;
      }
      if (first >= 0xf0 && first <= 0xf4) {
        if (index + 2 >= bytes.length) {
          return false;
        }
        int second = Byte.toUnsignedInt(bytes[index++]);
        int third = Byte.toUnsignedInt(bytes[index++]);
        int fourth = Byte.toUnsignedInt(bytes[index++]);
        boolean secondValid =
            first == 0xf0
                ? second >= 0x90 && second <= 0xbf
                : first == 0xf4
                    ? second >= 0x80 && second <= 0x8f
                    : second >= 0x80 && second <= 0xbf;
        if (!secondValid || third < 0x80 || third > 0xbf || fourth < 0x80 || fourth > 0xbf) {
          return false;
        }
        continue;
      }
      return false;
    }
    return true;
  }

  private static boolean continuation(byte value) {
    int unsigned = Byte.toUnsignedInt(value);
    return unsigned >= 0x80 && unsigned <= 0xbf;
  }

  private int readLength(CodedInputStream input, String name) throws IOException {
    int length = input.readRawVarint32();
    if (length < 0 || length > limits.maxRecordBytes()) {
      throw malformed(
          name
              + " encoded size "
              + Integer.toUnsignedLong(length)
              + " exceeds per-record limit "
              + limits.maxRecordBytes()
              + " bytes");
    }
    return length;
  }

  private void readRepeatedUInt64(
      int tag, CodedInputStream input, String name, LongConsumer consumer) throws IOException {
    int wire = WireFormat.getTagWireType(tag);
    if (wire == WireFormat.WIRETYPE_VARINT) {
      consumer.accept(input.readUInt64());
      return;
    }
    if (wire != WireFormat.WIRETYPE_LENGTH_DELIMITED) {
      throw wrongWire(name, wire);
    }
    readPacked(input, name, in -> consumer.accept(in.readUInt64()));
  }

  private void readRepeatedInt64(
      int tag, CodedInputStream input, String name, LongConsumer consumer) throws IOException {
    int wire = WireFormat.getTagWireType(tag);
    if (wire == WireFormat.WIRETYPE_VARINT) {
      consumer.accept(input.readInt64());
      return;
    }
    if (wire != WireFormat.WIRETYPE_LENGTH_DELIMITED) {
      throw wrongWire(name, wire);
    }
    readPacked(input, name, in -> consumer.accept(in.readInt64()));
  }

  private void readPacked(CodedInputStream input, String name, PackedValueReader reader)
      throws IOException {
    int length = readLength(input, name + " packed values");
    int previousLimit = input.pushLimit(length);
    try {
      while (input.getBytesUntilLimit() > 0) {
        reader.read(input);
      }
    } finally {
      input.popLimit(previousLimit);
    }
  }

  private void skipUnknown(CodedInputStream input, int tag, int groupDepth) throws IOException {
    int wire = WireFormat.getTagWireType(tag);
    switch (wire) {
      case WireFormat.WIRETYPE_VARINT -> input.readRawVarint64();
      case WireFormat.WIRETYPE_FIXED64 -> input.skipRawBytes(8);
      case WireFormat.WIRETYPE_LENGTH_DELIMITED ->
          input.skipRawBytes(readLength(input, "unknown length-delimited field"));
      case WireFormat.WIRETYPE_START_GROUP -> {
        if (groupDepth >= 100) {
          throw malformed("unknown protobuf group nesting exceeds 100");
        }
        int startField = WireFormat.getTagFieldNumber(tag);
        while (true) {
          int nestedTag = input.readTag();
          if (nestedTag == 0) {
            throw malformed("unterminated unknown protobuf group");
          }
          if (WireFormat.getTagWireType(nestedTag) == WireFormat.WIRETYPE_END_GROUP) {
            if (WireFormat.getTagFieldNumber(nestedTag) != startField) {
              throw malformed("mismatched unknown protobuf group terminator");
            }
            break;
          }
          skipUnknown(input, nestedTag, groupDepth + 1);
        }
      }
      case WireFormat.WIRETYPE_END_GROUP -> throw malformed("unexpected protobuf end-group tag");
      case WireFormat.WIRETYPE_FIXED32 -> input.skipRawBytes(4);
      default -> throw malformed("invalid protobuf wire type " + wire);
    }
  }

  private static void requireWire(int tag, int expected, String name) throws IOException {
    int actual = WireFormat.getTagWireType(tag);
    if (actual != expected) {
      throw wrongWire(name, actual);
    }
  }

  private static IOException wrongWire(String name, int actual) {
    return malformed(name + " has protobuf wire type " + actual + " instead of its declared type");
  }

  private long requireSqliteUnsigned(long value, String name) throws IOException {
    if (value < 0) {
      throw malformed(name + " exceeds SQLite's signed 64-bit integer range");
    }
    return value;
  }

  private void incrementTop(String kind) throws IOException {
    topLevelRecords++;
    if (topLevelRecords > limits.maxTopLevelRecords()) {
      throw malformed(kind + " exceed top-level record limit " + limits.maxTopLevelRecords());
    }
  }

  private void incrementChild(String kind) throws IOException {
    childRecords++;
    if (childRecords > limits.maxChildRecords()) {
      throw malformed(kind + " exceed normalized child-record limit " + limits.maxChildRecords());
    }
  }

  private static IOException malformed(String message) {
    return new IOException("Invalid Starlark CPU pprof: " + message);
  }

  record Limits(
      long maxDecompressedBytes,
      int maxRecordBytes,
      int maxStackDepth,
      int maxSampleValues,
      int maxSampleLabels,
      int maxLocationLines,
      long maxTopLevelRecords,
      long maxChildRecords) {
    Limits {
      if (maxDecompressedBytes <= 0
          || maxRecordBytes <= 0
          || maxStackDepth <= 0
          || maxSampleValues <= 0
          || maxSampleLabels <= 0
          || maxLocationLines <= 0
          || maxTopLevelRecords <= 0
          || maxChildRecords <= 0) {
        throw new IllegalArgumentException("all Starlark profile limits must be positive");
      }
    }
  }

  record ParseResult(
      long compressedBytes,
      long uncompressedBytes,
      long topLevelRecords,
      long childRecords,
      Metadata metadata) {}

  record Metadata(
      Long timeNanos,
      Long durationNanos,
      Long period,
      ValueTypeRecord periodType,
      Long dropFrames,
      Long keepFrames,
      Long defaultSampleType,
      Long docUrl) {}

  record ValueTypeRecord(long typeStringIndex, long unitStringIndex) {}

  record SampleRecord(long[] locationIds, long[] values, List<LabelRecord> labels) {}

  record LabelRecord(
      Long keyStringIndex, Long stringValueIndex, Long numericValue, Long numericUnitStringIndex) {
    String valueKind() {
      if (stringValueIndex != null) {
        return "STRING";
      }
      if (numericValue != null) {
        return "NUMERIC";
      }
      return "NONE";
    }
  }

  record MappingRecord(
      long id,
      Long memoryStart,
      Long memoryLimit,
      Long fileOffset,
      Long filenameStringIndex,
      Long buildIdStringIndex,
      boolean hasFunctions,
      boolean hasFilenames,
      boolean hasLineNumbers,
      boolean hasInlineFrames) {}

  record LocationRecord(
      long id, Long mappingId, Long address, List<LineRecord> lines, boolean folded) {}

  record LineRecord(long functionId, Long line, Long column) {}

  record FunctionRecord(
      long id,
      long nameStringIndex,
      long systemNameStringIndex,
      long filenameStringIndex,
      Long startLine) {}

  interface Sink {
    void sampleType(ValueTypeRecord value) throws IOException;

    void sample(SampleRecord value) throws IOException;

    void mapping(MappingRecord value) throws IOException;

    void location(LocationRecord value) throws IOException;

    void function(FunctionRecord value) throws IOException;

    void string(String value) throws IOException;

    void comment(long stringIndex) throws IOException;
  }

  @FunctionalInterface
  private interface MessageReader<T> {
    T read(CodedInputStream input) throws IOException;
  }

  @FunctionalInterface
  private interface PackedValueReader {
    void read(CodedInputStream input) throws IOException;
  }

  @FunctionalInterface
  private interface LongConsumer {
    void accept(long value) throws IOException;
  }

  private static final class MetadataBuilder {
    private Long timeNanos;
    private Long durationNanos;
    private Long period;
    private ValueTypeRecord periodType;
    private Long dropFrames;
    private Long keepFrames;
    private Long defaultSampleType;
    private Long docUrl;

    Metadata build() {
      return new Metadata(
          timeNanos,
          durationNanos,
          period,
          periodType,
          dropFrames,
          keepFrames,
          defaultSampleType,
          docUrl);
    }
  }

  private static final class LongArrayBuilder {
    private long[] values = new long[8];
    private int size;

    int size() {
      return size;
    }

    void add(long value) {
      if (size == values.length) {
        values = Arrays.copyOf(values, Math.multiplyExact(values.length, 2));
      }
      values[size++] = value;
    }

    long[] toArray() {
      return Arrays.copyOf(values, size);
    }
  }

  private static final class LimitedInputStream extends FilterInputStream {
    private final long limit;
    private final byte[] skipBuffer = new byte[8 * 1_024];
    private long bytesRead;

    LimitedInputStream(InputStream input, long limit) {
      super(input);
      this.limit = limit;
    }

    long bytesRead() {
      return bytesRead;
    }

    @Override
    public int read() throws IOException {
      if (bytesRead >= limit) {
        int extra = super.read();
        if (extra < 0) {
          return -1;
        }
        throw tooLarge();
      }
      int value = super.read();
      if (value >= 0) {
        bytesRead++;
      }
      return value;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
      if (length == 0) {
        return 0;
      }
      long remaining = limit - bytesRead;
      if (remaining <= 0) {
        int extra = super.read();
        if (extra < 0) {
          return -1;
        }
        throw tooLarge();
      }
      int allowed = (int) Math.min(length, remaining);
      int count = super.read(buffer, offset, allowed);
      if (count > 0) {
        bytesRead += count;
      }
      return count;
    }

    @Override
    public long skip(long count) throws IOException {
      if (count <= 0) {
        return 0;
      }
      // CodedInputStream skips unknown fields. Drain them through read() so discarded
      // decompressed bytes cannot bypass the aggregate profile-size limit.
      long skipped = 0;
      while (skipped < count) {
        int requested = (int) Math.min(count - skipped, skipBuffer.length);
        int read = read(skipBuffer, 0, requested);
        if (read < 0) {
          break;
        }
        skipped += read;
      }
      return skipped;
    }

    private IOException tooLarge() {
      return new IOException(
          "Starlark CPU pprof decompressed size exceeds limit " + limit + " bytes");
    }
  }
}
