package com.holtherndon.bazelviz.enrich.starlark;

import com.holtherndon.bazelviz.enrich.starlark.StarlarkCpuProfileParser.FunctionRecord;
import com.holtherndon.bazelviz.enrich.starlark.StarlarkCpuProfileParser.LabelRecord;
import com.holtherndon.bazelviz.enrich.starlark.StarlarkCpuProfileParser.LineRecord;
import com.holtherndon.bazelviz.enrich.starlark.StarlarkCpuProfileParser.LocationRecord;
import com.holtherndon.bazelviz.enrich.starlark.StarlarkCpuProfileParser.MappingRecord;
import com.holtherndon.bazelviz.enrich.starlark.StarlarkCpuProfileParser.Metadata;
import com.holtherndon.bazelviz.enrich.starlark.StarlarkCpuProfileParser.ParseResult;
import com.holtherndon.bazelviz.enrich.starlark.StarlarkCpuProfileParser.SampleRecord;
import com.holtherndon.bazelviz.enrich.starlark.StarlarkCpuProfileParser.ValueTypeRecord;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Writes raw pprof records and rebuildable Starlark CPU indexes with explicit attribution. */
final class StarlarkProfileWriter implements StarlarkCpuProfileParser.Sink, AutoCloseable {

  /** Rows accumulated in JDBC statements before a bounded flush. */
  public static final int JDBC_BATCH_SIZE = 5_000;

  /** Maximum pprof line/symbol records expanded from one sampled stack. */
  public static final int MAX_EXPANDED_SYMBOLS_PER_SAMPLE = 65_536;

  private static final String TEMP_COMMENTS = "temp.starlark_profile_import_comments";
  static final String MATERIALIZE_FUNCTION_CONTEXT_COUNTS_SQL =
      "UPDATE starlark_function_metrics AS metric"
          + " SET context_count=counts.context_count FROM ("
          + " SELECT line.function_id,COUNT(DISTINCT node.node_id) AS context_count"
          + " FROM starlark_call_nodes node"
          + " JOIN starlark_profile_location_lines line"
          + " ON line.location_id=node.location_id"
          + " GROUP BY line.function_id) AS counts"
          + " WHERE counts.function_id=metric.function_id";
  static final String MATERIALIZE_FILE_FUNCTION_COUNTS_SQL =
      "UPDATE starlark_file_metrics AS metric"
          + " SET function_count=counts.function_count FROM ("
          + " SELECT filename_string_index,COUNT(*) AS function_count"
          + " FROM starlark_profile_functions GROUP BY filename_string_index) AS counts"
          + " WHERE counts.filename_string_index=metric.filename_string_index";

  private final Connection connection;
  private final List<PreparedStatement> statements = new ArrayList<>();
  private final PreparedStatement insertString;
  private final PreparedStatement insertSampleType;
  private final PreparedStatement insertMapping;
  private final PreparedStatement insertFunction;
  private final PreparedStatement insertLocation;
  private final PreparedStatement insertLine;
  private final PreparedStatement insertSample;
  private final PreparedStatement insertValue;
  private final PreparedStatement insertFrame;
  private final PreparedStatement insertLabel;
  private final PreparedStatement insertComment;

  private long stringCount;
  private long sampleTypeCount;
  private long mappingCount;
  private long functionCount;
  private long locationCount;
  private long sampleCount;
  private long commentCount;
  private int maxStackDepth;
  private int pendingRows;

  StarlarkProfileWriter(Connection connection) throws SQLException {
    this.connection = connection;
    try (Statement statement = connection.createStatement()) {
      // pprof normally serializes samples before the locations and functions they name.
      statement.execute("PRAGMA defer_foreign_keys=ON");
      statement.execute(
          "CREATE TEMP TABLE IF NOT EXISTS "
              + TEMP_COMMENTS
              + " (ordinal INTEGER PRIMARY KEY, string_index INTEGER NOT NULL)");
      statement.execute("DELETE FROM " + TEMP_COMMENTS);
    }
    insertString =
        prepare("INSERT INTO starlark_profile_strings" + " (string_index, value) VALUES (?, ?)");
    insertSampleType =
        prepare(
            "INSERT INTO starlark_profile_sample_types"
                + " (ordinal, type_string_index, unit_string_index) VALUES (?, ?, ?)");
    insertMapping =
        prepare(
            "INSERT INTO starlark_profile_mappings"
                + " (mapping_id, memory_start, memory_limit, file_offset,"
                + " filename_string_index, build_id_string_index, has_functions, has_filenames,"
                + " has_line_numbers, has_inline_frames) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
    insertFunction =
        prepare(
            "INSERT INTO starlark_profile_functions"
                + " (function_id, name_string_index, system_name_string_index,"
                + " filename_string_index, start_line) VALUES (?, ?, ?, ?, ?)");
    insertLocation =
        prepare(
            "INSERT INTO starlark_profile_locations"
                + " (location_id, mapping_id, address, is_folded) VALUES (?, ?, ?, ?)");
    insertLine =
        prepare(
            "INSERT INTO starlark_profile_location_lines"
                + " (location_id, ordinal, function_id, line, column) VALUES (?, ?, ?, ?, ?)");
    insertSample =
        prepare("INSERT INTO starlark_profile_samples" + " (sample_id, stack_depth) VALUES (?, ?)");
    insertValue =
        prepare(
            "INSERT INTO starlark_profile_sample_values"
                + " (sample_id, ordinal, value) VALUES (?, ?, ?)");
    insertFrame =
        prepare(
            "INSERT INTO starlark_profile_sample_frames"
                + " (sample_id, ordinal, location_id) VALUES (?, ?, ?)");
    insertLabel =
        prepare(
            "INSERT INTO starlark_profile_sample_labels"
                + " (sample_id, ordinal, key_string_index, string_value_index, numeric_value,"
                + " numeric_unit_string_index, value_kind) VALUES (?, ?, ?, ?, ?, ?, ?)");
    insertComment =
        prepare("INSERT INTO " + TEMP_COMMENTS + " (ordinal, string_index) VALUES (?, ?)");
  }

  @Override
  public void string(String value) throws IOException {
    try {
      insertString.setLong(1, stringCount++);
      insertString.setString(2, value);
      add(insertString);
    } catch (SQLException failure) {
      throw writeFailure("string", failure);
    }
  }

  @Override
  public void sampleType(ValueTypeRecord value) throws IOException {
    try {
      insertSampleType.setLong(1, sampleTypeCount++);
      insertSampleType.setLong(2, value.typeStringIndex());
      insertSampleType.setLong(3, value.unitStringIndex());
      add(insertSampleType);
    } catch (SQLException failure) {
      throw writeFailure("sample type", failure);
    }
  }

  @Override
  public void mapping(MappingRecord value) throws IOException {
    requirePositiveId(value.id(), "mapping id");
    try {
      insertMapping.setLong(1, value.id());
      setNullableLong(insertMapping, 2, value.memoryStart());
      setNullableLong(insertMapping, 3, value.memoryLimit());
      setNullableLong(insertMapping, 4, value.fileOffset());
      setNullableLong(insertMapping, 5, value.filenameStringIndex());
      setNullableLong(insertMapping, 6, value.buildIdStringIndex());
      insertMapping.setInt(7, value.hasFunctions() ? 1 : 0);
      insertMapping.setInt(8, value.hasFilenames() ? 1 : 0);
      insertMapping.setInt(9, value.hasLineNumbers() ? 1 : 0);
      insertMapping.setInt(10, value.hasInlineFrames() ? 1 : 0);
      add(insertMapping);
      mappingCount++;
    } catch (SQLException failure) {
      throw writeFailure("mapping", failure);
    }
  }

  @Override
  public void function(FunctionRecord value) throws IOException {
    requirePositiveId(value.id(), "function id");
    try {
      insertFunction.setLong(1, value.id());
      insertFunction.setLong(2, value.nameStringIndex());
      insertFunction.setLong(3, value.systemNameStringIndex());
      insertFunction.setLong(4, value.filenameStringIndex());
      setNullableLong(insertFunction, 5, value.startLine());
      add(insertFunction);
      functionCount++;
    } catch (SQLException failure) {
      throw writeFailure("function", failure);
    }
  }

  @Override
  public void location(LocationRecord value) throws IOException {
    requirePositiveId(value.id(), "location id");
    try {
      insertLocation.setLong(1, value.id());
      setNullableLong(insertLocation, 2, value.mappingId());
      setNullableLong(insertLocation, 3, value.address());
      insertLocation.setInt(4, value.folded() ? 1 : 0);
      add(insertLocation);
      for (int ordinal = 0; ordinal < value.lines().size(); ordinal++) {
        LineRecord line = value.lines().get(ordinal);
        requirePositiveId(line.functionId(), "location line function id");
        insertLine.setLong(1, value.id());
        insertLine.setInt(2, ordinal);
        insertLine.setLong(3, line.functionId());
        setNullableLong(insertLine, 4, line.line());
        setNullableLong(insertLine, 5, line.column());
        add(insertLine);
      }
      locationCount++;
    } catch (SQLException failure) {
      throw writeFailure("location", failure);
    }
  }

  @Override
  public void sample(SampleRecord value) throws IOException {
    for (long locationId : value.locationIds()) {
      requirePositiveId(locationId, "sample frame location id");
    }
    long sampleId = ++sampleCount;
    try {
      insertSample.setLong(1, sampleId);
      insertSample.setInt(2, value.locationIds().length);
      add(insertSample);
      for (int ordinal = 0; ordinal < value.values().length; ordinal++) {
        insertValue.setLong(1, sampleId);
        insertValue.setInt(2, ordinal);
        insertValue.setLong(3, value.values()[ordinal]);
        add(insertValue);
      }
      for (int ordinal = 0; ordinal < value.locationIds().length; ordinal++) {
        insertFrame.setLong(1, sampleId);
        insertFrame.setInt(2, ordinal);
        insertFrame.setLong(3, value.locationIds()[ordinal]);
        add(insertFrame);
      }
      for (int ordinal = 0; ordinal < value.labels().size(); ordinal++) {
        LabelRecord label = value.labels().get(ordinal);
        insertLabel.setLong(1, sampleId);
        insertLabel.setInt(2, ordinal);
        insertLabel.setLong(3, label.keyStringIndex());
        setNullableLong(insertLabel, 4, label.stringValueIndex());
        setNullableLong(insertLabel, 5, label.numericValue());
        setNullableLong(insertLabel, 6, label.numericUnitStringIndex());
        insertLabel.setString(7, label.valueKind());
        add(insertLabel);
      }
      maxStackDepth = Math.max(maxStackDepth, value.locationIds().length);
    } catch (SQLException failure) {
      throw writeFailure("sample", failure);
    }
  }

  @Override
  public void comment(long stringIndex) throws IOException {
    try {
      insertComment.setLong(1, commentCount++);
      insertComment.setLong(2, stringIndex);
      add(insertComment);
    } catch (SQLException failure) {
      throw writeFailure("comment", failure);
    }
  }

  ImportSummary finish(long taskId, ParseResult parsed) throws IOException {
    return finish(taskId, parsed, false);
  }

  ImportSummary finish(long taskId, ParseResult parsed, boolean generic) throws IOException {
    flushRaw();
    Metadata metadata = parsed.metadata();
    validateRaw(metadata);
    int selectedOrdinal = generic ? selectDefaultSampleType(metadata) : selectCpuSampleType();
    PeriodNormalization normalizedPeriod = normalizePeriod(metadata);
    Total total = selectedTotal(selectedOrdinal);
    DerivationSummary attribution = derive(selectedOrdinal, total);
    materializePageCounts();
    validateDerivedIntegers();
    insertMetadata(taskId, parsed, metadata, normalizedPeriod, selectedOrdinal, total, attribution);
    if (generic) {
      try (Statement statement = connection.createStatement()) {
        statement.executeUpdate(
            "UPDATE starlark_profile_metadata SET validation_detail="
                + "'Standalone pprof. Values use the selected sample type and its original unit.'");
      } catch (SQLException failure) {
        throw writeFailure("profile description", failure);
      }
    }
    dropComments();
    return new ImportSummary(sampleCount, total.value(), selectedOrdinal, attribution);
  }

  private int selectDefaultSampleType(Metadata metadata) throws IOException {
    // pprof's default is the named default_sample_type, or the last sample type.
    boolean named = metadata.defaultSampleType() != null && metadata.defaultSampleType() != 0;
    String sql =
        "SELECT s.ordinal FROM starlark_profile_sample_types s"
            + " JOIN starlark_profile_strings t ON t.string_index=s.type_string_index"
            + (named ? " WHERE t.value=?" : "")
            + " ORDER BY s.ordinal DESC LIMIT 1";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      if (named) {
        statement.setString(1, stringAt(metadata.defaultSampleType()));
      }
      try (ResultSet rows = statement.executeQuery()) {
        if (!rows.next()) {
          throw invalid("profile has no sample type matching its declared default");
        }
        return rows.getInt(1);
      }
    } catch (SQLException failure) {
      throw writeFailure("selecting default sample type", failure);
    }
  }

  private void validateRaw(Metadata metadata) throws IOException {
    if (stringCount == 0 || !"".equals(stringAt(0))) {
      throw invalid("string_table[0] must exist and be empty");
    }
    if (sampleTypeCount == 0) {
      throw invalid("profile has no sample types");
    }
    validateStringReference(
        "sample type", "starlark_profile_sample_types", "type_string_index", false);
    validateStringReference(
        "sample type unit", "starlark_profile_sample_types", "unit_string_index", false);
    validateStringReference(
        "mapping filename", "starlark_profile_mappings", "filename_string_index", true);
    validateStringReference(
        "mapping build id", "starlark_profile_mappings", "build_id_string_index", true);
    validateStringReference(
        "function name", "starlark_profile_functions", "name_string_index", false);
    validateStringReference(
        "function system name", "starlark_profile_functions", "system_name_string_index", false);
    validateStringReference(
        "function filename", "starlark_profile_functions", "filename_string_index", false);
    validateStringReference(
        "sample label key", "starlark_profile_sample_labels", "key_string_index", false);
    validateStringReference(
        "sample label string value", "starlark_profile_sample_labels", "string_value_index", true);
    validateStringReference(
        "sample label numeric unit",
        "starlark_profile_sample_labels",
        "numeric_unit_string_index",
        true);
    validateStringReference("comment", TEMP_COMMENTS, "string_index", false);

    validateMetadataString("drop_frames", metadata.dropFrames());
    validateMetadataString("keep_frames", metadata.keepFrames());
    validateMetadataString("default_sample_type", metadata.defaultSampleType());
    validateMetadataString("doc_url", metadata.docUrl());
    if (metadata.periodType() != null) {
      validateStringIndex("period_type.type", metadata.periodType().typeStringIndex());
      validateStringIndex("period_type.unit", metadata.periodType().unitStringIndex());
    }
    if (metadata.durationNanos() != null && metadata.durationNanos() < 0) {
      throw invalid("duration_nanos must not be negative");
    }
    if (metadata.period() != null && metadata.period() <= 0) {
      throw invalid("period must be positive when present");
    }

    failIfRow(
        "location references missing mapping",
        "SELECT l.location_id FROM starlark_profile_locations l"
            + " LEFT JOIN starlark_profile_mappings m ON m.mapping_id=l.mapping_id"
            + " WHERE l.mapping_id IS NOT NULL AND l.mapping_id<>0"
            + " AND m.mapping_id IS NULL LIMIT 1");
    failIfRow(
        "location line references missing function",
        "SELECT l.location_id FROM starlark_profile_location_lines l"
            + " LEFT JOIN starlark_profile_functions f ON f.function_id=l.function_id"
            + " WHERE f.function_id IS NULL LIMIT 1");
    failIfRow(
        "sample frame references missing location",
        "SELECT f.sample_id FROM starlark_profile_sample_frames f"
            + " LEFT JOIN starlark_profile_locations l ON l.location_id=f.location_id"
            + " WHERE l.location_id IS NULL LIMIT 1");
    failIfRow(
        "sample value cardinality does not match " + sampleTypeCount + " sample types",
        "SELECT s.sample_id FROM starlark_profile_samples s"
            + " LEFT JOIN starlark_profile_sample_values v ON v.sample_id=s.sample_id"
            + " GROUP BY s.sample_id HAVING count(v.ordinal)<>"
            + sampleTypeCount
            + " LIMIT 1");
  }

  private int selectCpuSampleType() throws IOException {
    String sql =
        "SELECT st.ordinal FROM starlark_profile_sample_types st"
            + " JOIN starlark_profile_strings t ON t.string_index=st.type_string_index"
            + " JOIN starlark_profile_strings u ON u.string_index=st.unit_string_index"
            + " WHERE t.value='CPU' AND u.value='microseconds' ORDER BY st.ordinal";
    try (Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery(sql)) {
      if (!rows.next()) {
        throw invalid("profile has no CPU/microseconds sample type");
      }
      int ordinal = rows.getInt(1);
      if (rows.next()) {
        throw invalid("profile has more than one CPU/microseconds sample type");
      }
      return ordinal;
    } catch (SQLException failure) {
      throw writeFailure("selecting CPU sample type", failure);
    }
  }

  private PeriodNormalization normalizePeriod(Metadata metadata) throws IOException {
    if (metadata.period() == null) {
      return new PeriodNormalization(null, "sampling period was not recorded");
    }
    if (metadata.periodType() == null) {
      return new PeriodNormalization(
          null, "sampling period is unavailable because period_type was not recorded");
    }
    String type = stringAt(metadata.periodType().typeStringIndex());
    String unit = stringAt(metadata.periodType().unitStringIndex());
    if (!"CPU".equals(type)) {
      return new PeriodNormalization(
          null,
          "sampling period is unavailable because period_type is " + printable(type) + ", not CPU");
    }
    long raw = metadata.period();
    try {
      Long micros =
          switch (unit.toLowerCase(Locale.ROOT)) {
            case "nanosecond", "nanoseconds", "ns" -> raw % 1_000L == 0 ? raw / 1_000L : null;
            case "microsecond", "microseconds", "us", "µs" -> raw;
            case "millisecond", "milliseconds", "ms" -> Math.multiplyExact(raw, 1_000L);
            case "second", "seconds", "s" -> Math.multiplyExact(raw, 1_000_000L);
            default -> null;
          };
      if (micros == null) {
        String reason =
            switch (unit.toLowerCase(Locale.ROOT)) {
              case "nanosecond", "nanoseconds", "ns" ->
                  "the nanosecond value is not a whole microsecond";
              default -> "unit " + printable(unit) + " is not recognized";
            };
        return new PeriodNormalization(null, "sampling period is unavailable because " + reason);
      }
      return new PeriodNormalization(micros, "sampling period normalized from " + raw + " " + unit);
    } catch (ArithmeticException overflow) {
      return new PeriodNormalization(
          null,
          "sampling period is unavailable because converting "
              + raw
              + " "
              + unit
              + " exceeds signed 64-bit microseconds");
    }
  }

  private static String printable(String value) {
    return value == null || value.isBlank() ? "an empty string" : "'" + value + "'";
  }

  private static boolean useful(String value) {
    return value != null && !value.isBlank();
  }

  private Total selectedTotal(int selectedOrdinal) throws IOException {
    String sql =
        "SELECT s.sample_id, v.value FROM starlark_profile_samples s"
            + " JOIN starlark_profile_sample_values v ON v.sample_id=s.sample_id"
            + " AND v.ordinal=? ORDER BY s.sample_id";
    long total = 0;
    long records = 0;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setInt(1, selectedOrdinal);
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          checkInterrupted();
          long value = rows.getLong(2);
          if (value < 0) {
            throw invalid(
                "Selected sample "
                    + rows.getLong(1)
                    + " has negative value "
                    + value
                    + "; signed/difference profiles are not supported");
          }
          try {
            total = Math.addExact(total, value);
          } catch (ArithmeticException overflow) {
            throw invalid("CPU sample total exceeds signed 64-bit range");
          }
          records++;
        }
      }
    } catch (SQLException failure) {
      throw writeFailure("reading selected CPU values", failure);
    }
    if (records != sampleCount) {
      throw invalid("selected CPU sample type is absent from one or more samples");
    }
    return new Total(total, records);
  }

  private DerivationSummary derive(int selectedOrdinal, Total total) throws IOException {
    clearDerived();
    long emptySelf =
        scalarBound(
            "SELECT coalesce(sum(v.value),0)"
                + " FROM starlark_profile_samples s"
                + " JOIN starlark_profile_sample_values v ON v.sample_id=s.sample_id"
                + " WHERE v.ordinal=? AND s.stack_depth=0",
            selectedOrdinal);
    long emptySamples =
        scalar("SELECT count(*) FROM starlark_profile_samples" + " WHERE stack_depth=0");
    try (PreparedStatement root =
        connection.prepareStatement(
            "INSERT INTO starlark_call_nodes"
                + " (node_id,parent_node_id,location_id,depth,inclusive_value,self_value,"
                + " sample_count,self_sample_count) VALUES (1,NULL,NULL,0,?,?,?,?)")) {
      root.setLong(1, total.value());
      root.setLong(2, emptySelf);
      root.setLong(3, total.records());
      root.setLong(4, emptySamples);
      root.executeUpdate();
    } catch (SQLException failure) {
      throw writeFailure("creating call-tree root", failure);
    }

    String sql =
        "SELECT s.sample_id,v.value,f.ordinal,f.location_id,"
            + " line.ordinal,line.function_id,fn.filename_string_index,"
            + " function_name.value,filename.value"
            + " FROM starlark_profile_samples s"
            + " JOIN starlark_profile_sample_values v ON v.sample_id=s.sample_id"
            + " AND v.ordinal=?"
            + " LEFT JOIN starlark_profile_sample_frames f ON f.sample_id=s.sample_id"
            + " LEFT JOIN starlark_profile_location_lines line"
            + " ON line.location_id=f.location_id"
            + " LEFT JOIN starlark_profile_functions fn ON fn.function_id=line.function_id"
            + " LEFT JOIN starlark_profile_strings function_name"
            + " ON function_name.string_index=fn.name_string_index"
            + " LEFT JOIN starlark_profile_strings filename"
            + " ON filename.string_index=fn.filename_string_index"
            + " ORDER BY s.sample_id,f.ordinal,line.ordinal";
    try (Deriver deriver = new Deriver(connection);
        PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setInt(1, selectedOrdinal);
      try (ResultSet rows = statement.executeQuery()) {
        long currentSample = -1;
        long currentValue = 0;
        List<Frame> frames = new ArrayList<>();
        int expandedSymbols = 0;
        while (rows.next()) {
          checkInterrupted();
          long sampleId = rows.getLong(1);
          if (currentSample != -1 && sampleId != currentSample) {
            deriver.accept(currentValue, frames);
            frames.clear();
            expandedSymbols = 0;
          }
          currentSample = sampleId;
          currentValue = rows.getLong(2);
          long locationId = rows.getLong(4);
          if (!rows.wasNull()) {
            int frameOrdinal = rows.getInt(3);
            Frame frame;
            if (frames.isEmpty() || frames.getLast().ordinal() != frameOrdinal) {
              frame = new Frame(frameOrdinal, locationId, new ArrayList<>());
              frames.add(frame);
            } else {
              frame = frames.getLast();
            }
            long functionId = rows.getLong(6);
            if (!rows.wasNull()) {
              expandedSymbols++;
              if (expandedSymbols > MAX_EXPANDED_SYMBOLS_PER_SAMPLE) {
                throw invalid(
                    "sample "
                        + sampleId
                        + " expands to more than "
                        + MAX_EXPANDED_SYMBOLS_PER_SAMPLE
                        + " location-line symbols");
              }
              frame
                  .symbols()
                  .add(
                      new Symbol(
                          rows.getInt(5),
                          functionId,
                          rows.getLong(7),
                          useful(rows.getString(8)),
                          useful(rows.getString(9))));
            }
          }
        }
        if (currentSample != -1) {
          deriver.accept(currentValue, frames);
        }
      }
      deriver.flush();
      return deriver.summary();
    } catch (SQLException failure) {
      throw writeFailure("deriving Starlark call indexes", failure);
    }
  }

  private void validateDerivedIntegers() throws IOException {
    failIfRow(
        "call-tree aggregation overflowed signed 64-bit range",
        "SELECT node_id FROM starlark_call_nodes"
            + " WHERE typeof(inclusive_value)<>'integer'"
            + " OR typeof(self_value)<>'integer' LIMIT 1");
    failIfRow(
        "function aggregation overflowed signed 64-bit range",
        "SELECT function_id FROM starlark_function_metrics"
            + " WHERE typeof(self_value)<>'integer'"
            + " OR typeof(cumulative_value)<>'integer' LIMIT 1");
    failIfRow(
        "file aggregation overflowed signed 64-bit range",
        "SELECT filename_string_index FROM starlark_file_metrics"
            + " WHERE typeof(self_value)<>'integer'"
            + " OR typeof(cumulative_value)<>'integer' LIMIT 1");
    failIfRow(
        "call-edge aggregation overflowed signed 64-bit range",
        "SELECT caller_location_id FROM starlark_call_edges"
            + " WHERE typeof(value)<>'integer' LIMIT 1");
  }

  /**
   * Stores display counts once after derivation instead of regrouping the complete profile for
   * every paged UI read. Both statements are single grouped passes over normalized input.
   */
  private void materializePageCounts() throws IOException {
    try (Statement statement = connection.createStatement()) {
      statement.executeUpdate(MATERIALIZE_FUNCTION_CONTEXT_COUNTS_SQL);
      statement.executeUpdate(MATERIALIZE_FILE_FUNCTION_COUNTS_SQL);
    } catch (SQLException failure) {
      throw writeFailure("materialized display counts", failure);
    }
  }

  private void insertMetadata(
      long taskId,
      ParseResult parsed,
      Metadata metadata,
      PeriodNormalization normalizedPeriod,
      int selectedOrdinal,
      Total total,
      DerivationSummary attribution)
      throws IOException {
    String sql =
        "INSERT INTO starlark_profile_metadata"
            + " (id,task_id,format,compressed_bytes,uncompressed_bytes,profile_time_nanos,"
            + " duration_nanos,period,period_type_string_index,period_unit_string_index,"
            + " normalized_period_micros,"
            + " drop_frames_string_index,keep_frames_string_index,"
            + " default_sample_type_string_index,selected_sample_type_ordinal,"
            + " doc_url_string_index,sample_count,mapping_count,location_count,function_count,"
            + " string_count,max_stack_depth,total_value,"
            + " function_attributed_value,function_attributed_samples,"
            + " file_attributed_value,file_attributed_samples,"
            + " context_attributed_value,context_attributed_samples,"
            + " validation_state,validation_detail)"
            + " VALUES (1,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, taskId);
      statement.setString(2, parsed.gzip() ? "pprof-gzip" : "pprof");
      statement.setLong(3, parsed.compressedBytes());
      statement.setLong(4, parsed.uncompressedBytes());
      setNullableLong(statement, 5, metadata.timeNanos());
      setNullableLong(statement, 6, metadata.durationNanos());
      setNullableLong(statement, 7, metadata.period());
      setNullableLong(
          statement,
          8,
          metadata.periodType() == null ? null : metadata.periodType().typeStringIndex());
      setNullableLong(
          statement,
          9,
          metadata.periodType() == null ? null : metadata.periodType().unitStringIndex());
      setNullableLong(statement, 10, normalizedPeriod.micros());
      setNullableLong(statement, 11, metadata.dropFrames());
      setNullableLong(statement, 12, metadata.keepFrames());
      setNullableLong(statement, 13, metadata.defaultSampleType());
      statement.setInt(14, selectedOrdinal);
      setNullableLong(statement, 15, metadata.docUrl());
      statement.setLong(16, sampleCount);
      statement.setLong(17, mappingCount);
      statement.setLong(18, locationCount);
      statement.setLong(19, functionCount);
      statement.setLong(20, stringCount);
      statement.setInt(21, maxStackDepth);
      statement.setLong(22, total.value());
      statement.setLong(23, attribution.function().value());
      statement.setLong(24, attribution.function().samples());
      statement.setLong(25, attribution.file().value());
      statement.setLong(26, attribution.file().samples());
      statement.setLong(27, attribution.context().value());
      statement.setLong(28, attribution.context().samples());
      statement.setString(29, "VALID");
      statement.setString(30, validationDetail(total, attribution, normalizedPeriod));
      statement.executeUpdate();
    } catch (SQLException failure) {
      throw writeFailure("profile metadata", failure);
    }
  }

  private static String validationDetail(
      Total total, DerivationSummary attribution, PeriodNormalization period) {
    return "Validated CPU/microseconds pprof; "
        + period.detail()
        + ". "
        + coverageDetail("function self", attribution.function(), total)
        + "; "
        + coverageDetail("source-file self", attribution.file(), total)
        + "; "
        + coverageDetail("symbolized physical call context", attribution.context(), total)
        + ". Raw inline lines are preserved; flat cumulative metrics include every inline"
        + " function and file, while the call tree keeps one physical location per node.";
  }

  private static String coverageDetail(String name, Attribution attribution, Total total) {
    return name
        + " attribution "
        + attribution.value()
        + " attributed / "
        + (total.value() - attribution.value())
        + " unattributed CPU microseconds and "
        + attribution.samples()
        + " attributed / "
        + (total.records() - attribution.samples())
        + " unattributed sample records";
  }

  private void validateStringReference(
      String description, String table, String column, boolean nullable) throws IOException {
    String predicate =
        (nullable ? column + " IS NOT NULL AND " : "")
            + "("
            + column
            + "<0 OR "
            + column
            + ">="
            + stringCount
            + ")";
    failIfRow(
        description + " references a missing string",
        "SELECT " + column + " FROM " + table + " WHERE " + predicate + " LIMIT 1");
  }

  private void validateMetadataString(String description, Long index) throws IOException {
    if (index != null) {
      validateStringIndex(description, index);
    }
  }

  private void validateStringIndex(String description, long index) throws IOException {
    if (index < 0 || index >= stringCount) {
      throw invalid(description + " references missing string index " + index);
    }
  }

  private String stringAt(long index) throws IOException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT value FROM starlark_profile_strings WHERE string_index=?")) {
      statement.setLong(1, index);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next() ? rows.getString(1) : null;
      }
    } catch (SQLException failure) {
      throw writeFailure("reading string table", failure);
    }
  }

  private void failIfRow(String message, String sql) throws IOException {
    try (Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery(sql)) {
      if (rows.next()) {
        throw invalid(message + " (record " + rows.getLong(1) + ")");
      }
    } catch (SQLException failure) {
      throw writeFailure("validating pprof references", failure);
    }
  }

  private long scalar(String sql) throws IOException {
    try (Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery(sql)) {
      if (!rows.next()) {
        throw invalid("aggregate query returned no row");
      }
      return rows.getLong(1);
    } catch (SQLException failure) {
      throw writeFailure("reading profile aggregate", failure);
    }
  }

  private long scalarBound(String sql, int value) throws IOException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setInt(1, value);
      try (ResultSet rows = statement.executeQuery()) {
        if (!rows.next()) {
          throw invalid("aggregate query returned no row");
        }
        long result = rows.getLong(1);
        if (!"integer".equals(sqliteType(rows, 1))) {
          throw invalid("CPU aggregate exceeds signed 64-bit range");
        }
        return result;
      }
    } catch (SQLException failure) {
      throw writeFailure("aggregating CPU samples", failure);
    }
  }

  private static String sqliteType(ResultSet rows, int column) throws SQLException {
    Object value = rows.getObject(column);
    return value instanceof Byte
            || value instanceof Short
            || value instanceof Integer
            || value instanceof Long
        ? "integer"
        : "other";
  }

  private void clearDerived() throws IOException {
    try (Statement statement = connection.createStatement()) {
      statement.executeUpdate("DELETE FROM starlark_call_edges");
      statement.executeUpdate("DELETE FROM starlark_file_metrics");
      statement.executeUpdate("DELETE FROM starlark_function_metrics");
      statement.executeUpdate("DELETE FROM starlark_call_nodes");
    } catch (SQLException failure) {
      throw writeFailure("clearing Starlark derived indexes", failure);
    }
  }

  private PreparedStatement prepare(String sql) throws SQLException {
    PreparedStatement statement = connection.prepareStatement(sql);
    statements.add(statement);
    return statement;
  }

  private void add(PreparedStatement statement) throws SQLException, IOException {
    checkInterrupted();
    statement.addBatch();
    pendingRows++;
    if (pendingRows >= JDBC_BATCH_SIZE) {
      flushRaw();
    }
  }

  private static void checkInterrupted() throws InterruptedIOException {
    if (Thread.currentThread().isInterrupted()) {
      throw new InterruptedIOException("Profile import cancelled");
    }
  }

  private void flushRaw() throws IOException {
    if (pendingRows == 0) {
      return;
    }
    try {
      for (PreparedStatement statement : statements) {
        statement.executeBatch();
        statement.clearBatch();
      }
      pendingRows = 0;
    } catch (SQLException failure) {
      throw writeFailure("bounded pprof JDBC batch", failure);
    }
  }

  private void dropComments() throws IOException {
    try (Statement statement = connection.createStatement()) {
      statement.execute("DROP TABLE IF EXISTS " + TEMP_COMMENTS);
    } catch (SQLException failure) {
      throw writeFailure("discarding validated temporary comments", failure);
    }
  }

  private static void setNullableLong(PreparedStatement statement, int index, Long value)
      throws SQLException {
    if (value == null) {
      statement.setNull(index, Types.INTEGER);
    } else {
      statement.setLong(index, value);
    }
  }

  private static IOException invalid(String message) {
    return new IOException("Invalid Starlark CPU pprof: " + message);
  }

  private static void requirePositiveId(long value, String description) throws IOException {
    if (value <= 0) {
      throw invalid(description + " must be positive, got " + value);
    }
  }

  private static IOException writeFailure(String record, SQLException failure) {
    return new IOException(
        "Could not write Starlark CPU pprof " + record + ": " + failure.getMessage(), failure);
  }

  @Override
  public void close() {
    for (PreparedStatement statement : statements) {
      try {
        statement.close();
      } catch (SQLException ignored) {
        // The import result carries the useful parse/write failure.
      }
    }
  }

  record ImportSummary(
      long sampleCount,
      long totalValue,
      int selectedSampleTypeOrdinal,
      DerivationSummary attribution) {}

  private record Total(long value, long records) {}

  private record PeriodNormalization(Long micros, String detail) {}

  record Attribution(long value, long samples) {}

  record DerivationSummary(Attribution function, Attribution file, Attribution context) {}

  private record Frame(int ordinal, long locationId, List<Symbol> symbols) {}

  private record Symbol(
      int ordinal,
      long functionId,
      long filenameStringIndex,
      boolean namedFunction,
      boolean namedFile) {}

  /** Bounded derivation that reports any symbol-attribution gap explicitly. */
  private static final class Deriver implements AutoCloseable {
    private final PreparedStatement upsertNode;
    private final PreparedStatement upsertFunction;
    private final PreparedStatement upsertFile;
    private final PreparedStatement upsertEdge;
    private int pending;
    private long functionAttributedValue;
    private long functionAttributedSamples;
    private long fileAttributedValue;
    private long fileAttributedSamples;
    private long contextAttributedValue;
    private long contextAttributedSamples;

    Deriver(Connection connection) throws SQLException {
      upsertNode =
          connection.prepareStatement(
              "INSERT INTO starlark_call_nodes"
                  + " (parent_node_id,location_id,depth,inclusive_value,self_value,"
                  + " sample_count,self_sample_count) VALUES (?,?,?,?,?,1,?)"
                  + " ON CONFLICT(parent_node_id,location_id) DO UPDATE SET"
                  + " inclusive_value=inclusive_value+excluded.inclusive_value,"
                  + " self_value=self_value+excluded.self_value,"
                  + " sample_count=sample_count+1,"
                  + " self_sample_count=self_sample_count+excluded.self_sample_count"
                  + " RETURNING node_id");
      upsertFunction =
          connection.prepareStatement(
              "INSERT INTO starlark_function_metrics"
                  + " (function_id,self_value,cumulative_value,self_samples,"
                  + " cumulative_samples) VALUES (?,?,?,?,1)"
                  + " ON CONFLICT(function_id) DO UPDATE SET"
                  + " self_value=self_value+excluded.self_value,"
                  + " cumulative_value=cumulative_value+excluded.cumulative_value,"
                  + " self_samples=self_samples+excluded.self_samples,"
                  + " cumulative_samples=cumulative_samples+1");
      upsertFile =
          connection.prepareStatement(
              "INSERT INTO starlark_file_metrics"
                  + " (filename_string_index,self_value,cumulative_value,self_samples,"
                  + " cumulative_samples) VALUES (?,?,?,?,1)"
                  + " ON CONFLICT(filename_string_index) DO UPDATE SET"
                  + " self_value=self_value+excluded.self_value,"
                  + " cumulative_value=cumulative_value+excluded.cumulative_value,"
                  + " self_samples=self_samples+excluded.self_samples,"
                  + " cumulative_samples=cumulative_samples+1");
      upsertEdge =
          connection.prepareStatement(
              "INSERT INTO starlark_call_edges"
                  + " (caller_location_id,callee_location_id,value,sample_count)"
                  + " VALUES (?,?,?,1)"
                  + " ON CONFLICT(caller_location_id,callee_location_id) DO UPDATE SET"
                  + " value=value+excluded.value,sample_count=sample_count+1");
    }

    void accept(long value, List<Frame> frames) throws SQLException, IOException {
      long parent = 1;
      int depth = 1;
      for (int ordinal = frames.size() - 1; ordinal >= 0; ordinal--) {
        Frame frame = frames.get(ordinal);
        boolean leaf = ordinal == 0;
        upsertNode.setLong(1, parent);
        upsertNode.setLong(2, frame.locationId());
        upsertNode.setInt(3, depth++);
        upsertNode.setLong(4, value);
        upsertNode.setLong(5, leaf ? value : 0);
        upsertNode.setInt(6, leaf ? 1 : 0);
        try (ResultSet row = upsertNode.executeQuery()) {
          if (!row.next()) {
            throw new SQLException("call-node upsert returned no node id");
          }
          parent = row.getLong(1);
        }
      }

      Set<Long> functions = new HashSet<>();
      Set<Long> files = new HashSet<>();
      boolean functionAttributed = false;
      boolean fileAttributed = false;
      boolean contextAttributed = !frames.isEmpty();
      for (int frameOrdinal = 0; frameOrdinal < frames.size(); frameOrdinal++) {
        Frame frame = frames.get(frameOrdinal);
        boolean leaf = frameOrdinal == 0;
        contextAttributed &=
            frame.symbols().size() == 1 && frame.symbols().getFirst().namedFunction();
        for (Symbol symbol : frame.symbols()) {
          boolean self = leaf && symbol.ordinal() == 0;
          if (self && symbol.namedFunction()) {
            functionAttributed = true;
          }
          if (self && symbol.namedFile()) {
            fileAttributed = true;
          }
          if (functions.add(symbol.functionId())) {
            upsertFunction.setLong(1, symbol.functionId());
            upsertFunction.setLong(2, self ? value : 0);
            upsertFunction.setLong(3, value);
            upsertFunction.setInt(4, self ? 1 : 0);
            add(upsertFunction);
          }
          if (symbol.namedFile() && files.add(symbol.filenameStringIndex())) {
            upsertFile.setLong(1, symbol.filenameStringIndex());
            upsertFile.setLong(2, self ? value : 0);
            upsertFile.setLong(3, value);
            upsertFile.setInt(4, self ? 1 : 0);
            add(upsertFile);
          }
        }
      }
      if (functionAttributed) {
        functionAttributedValue =
            addExact(functionAttributedValue, value, "function-attributed CPU");
        functionAttributedSamples++;
      }
      if (fileAttributed) {
        fileAttributedValue = addExact(fileAttributedValue, value, "file-attributed CPU");
        fileAttributedSamples++;
      }
      if (contextAttributed) {
        contextAttributedValue = addExact(contextAttributedValue, value, "context-attributed CPU");
        contextAttributedSamples++;
      }
      for (int ordinal = 0; ordinal + 1 < frames.size(); ordinal++) {
        Frame callee = frames.get(ordinal);
        Frame caller = frames.get(ordinal + 1);
        upsertEdge.setLong(1, caller.locationId());
        upsertEdge.setLong(2, callee.locationId());
        upsertEdge.setLong(3, value);
        add(upsertEdge);
      }
    }

    DerivationSummary summary() {
      return new DerivationSummary(
          new Attribution(functionAttributedValue, functionAttributedSamples),
          new Attribution(fileAttributedValue, fileAttributedSamples),
          new Attribution(contextAttributedValue, contextAttributedSamples));
    }

    private static long addExact(long left, long right, String description) throws IOException {
      try {
        return Math.addExact(left, right);
      } catch (ArithmeticException overflow) {
        throw invalid(description + " exceeds signed 64-bit range");
      }
    }

    private void add(PreparedStatement statement) throws SQLException {
      statement.addBatch();
      pending++;
      if (pending >= JDBC_BATCH_SIZE) {
        flush();
      }
    }

    void flush() throws SQLException {
      if (pending == 0) {
        return;
      }
      upsertFunction.executeBatch();
      upsertFunction.clearBatch();
      upsertFile.executeBatch();
      upsertFile.clearBatch();
      upsertEdge.executeBatch();
      upsertEdge.clearBatch();
      pending = 0;
    }

    @Override
    public void close() {
      for (PreparedStatement statement :
          List.of(upsertNode, upsertFunction, upsertFile, upsertEdge)) {
        try {
          statement.close();
        } catch (SQLException ignored) {
          // A preceding derivation failure is the useful error.
        }
      }
    }
  }
}
