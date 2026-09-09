package com.holtherndon.bazelviz.storage.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.holtherndon.bazelviz.core.event.DecodeStatus;
import com.holtherndon.bazelviz.core.filter.FilterExpression;
import com.holtherndon.bazelviz.core.filter.FilterExpression.Condition;
import com.holtherndon.bazelviz.core.filter.FilterExpression.Group;
import com.holtherndon.bazelviz.core.filter.FilterExpression.Junction;
import com.holtherndon.bazelviz.core.filter.FilterExpression.Operator;
import com.holtherndon.bazelviz.storage.SessionDatabase;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EventFilterTest {
  @TempDir Path temporary;

  @Test
  void prefixAndSuffixFiltersAreLiteralCaseInsensitiveAndKeepUnknownsUnknown() throws Exception {
    try (SessionDatabase db = seeded();
        Connection read = db.newReadConnection();
        EventQueries queries = new EventQueries(read)) {
      assertThat(queries.eventCount(condition("event_id", Operator.STARTS_WITH, "TARGET://x/1")))
          .isEqualTo(111);
      assertThat(queries.eventCount(condition("event_id", Operator.ENDS_WITH, "/1"))).isEqualTo(1);
      for (Operator op : List.of(Operator.STARTS_WITH, Operator.ENDS_WITH)) {
        assertThat(queries.eventCount(condition("event_id", op, ""))).isEqualTo(300);
        assertThat(queries.eventCount(condition("event_id", op, "%"))).isZero();
        assertThat(queries.eventCount(condition("event_id", op, "' OR 1=1 --"))).isZero();
      }
      for (Operator op : List.of(Operator.NOT_STARTS_WITH, Operator.NOT_ENDS_WITH)) {
        assertThat(queries.eventCount(condition("event_id", op, ""))).isZero();
        assertThat(queries.eventCount(condition("event_id", op, "%"))).isEqualTo(300);
      }
    }
  }

  @Test
  void composedFiltersCountAndPageAcrossTheWholeStoreInBothDirections() throws Exception {
    try (SessionDatabase db = seeded();
        Connection read = db.newReadConnection();
        EventQueries queries = new EventQueries(read)) {
      Group filter =
          new Group(
              Junction.ALL,
              List.of(
                  condition("type", Operator.IN, "7", "18"),
                  condition("children", Operator.GREATER_THAN, "5"),
                  new Group(
                      Junction.ANY,
                      List.of(
                          condition("id", Operator.LESS_THAN, "12"),
                          condition("id", Operator.AT_LEAST, "240")))));
      List<Long> expected =
          LongStream.rangeClosed(1, 300)
              .filter(id -> id % 3 != 0 && id % 10 > 5 && (id < 12 || id >= 240))
              .boxed()
              .toList();
      assertThat(queries.eventCount(filter)).isEqualTo(expected.size());
      for (boolean forward : new boolean[] {true, false}) {
        List<Long> found = new ArrayList<>();
        OptionalLong anchor = OptionalLong.empty();
        do {
          EventPage page =
              forward
                  ? queries.pageForward(anchor, 3, filter)
                  : queries.pageBackward(anchor, 3, filter);
          found.addAll(
              forward ? found.size() : 0, page.events().stream().map(EventSummary::id).toList());
          anchor = page.nextAnchor();
        } while (anchor.isPresent());
        assertThat(found).containsExactlyElementsOf(expected);
      }
      assertThat(queries.eventCount(FilterExpression.ALL)).isEqualTo(301);
      assertThat(queries.eventCount(new Group(Junction.ANY, List.of(FilterExpression.ALL))))
          .isEqualTo(301);
    }
  }

  @Test
  void failedDecodesAreUnknownNotZeroAndUnknownsDoNotMatchNegativeComparisons() throws Exception {
    try (SessionDatabase db = seeded();
        Connection read = db.newReadConnection();
        EventQueries queries = new EventQueries(read)) {
      assertThat(queries.eventCount(condition("children", Operator.EQUALS, "0"))).isEqualTo(30);
      for (String field :
          List.of("children", "type", "event_id", "event_time", "unknown_fields", "last_message")) {
        assertThat(
                queries
                    .pageForward(OptionalLong.empty(), 5, condition(field, Operator.IS_ABSENT))
                    .events())
            .extracting(EventSummary::id)
            .containsExactly(301L);
        assertThat(queries.eventCount(condition(field, Operator.IS_PRESENT))).isEqualTo(300);
      }
      assertThat(queries.eventCount(condition("type", Operator.NOT_IN, "3", "18"))).isEqualTo(100);
      assertThat(queries.eventCount(condition("children", Operator.NOT_EQUALS, "0")))
          .isEqualTo(270);
      assertThat(queries.eventCount(condition("decode", Operator.EQUALS, "FAILED"))).isEqualTo(1);
      assertThat(queries.eventCount(condition("last_message", Operator.EQUALS, "false")))
          .isEqualTo(300);
    }
  }

  @Test
  void textIsBoundAndContainsIsLiteralRatherThanSqlOrWildcards() throws Exception {
    try (SessionDatabase db = seeded();
        Connection read = db.newReadConnection();
        EventQueries queries = new EventQueries(read)) {
      assertThat(queries.eventCount(condition("event_id", Operator.CONTAINS, "TARGET://x/1")))
          .isEqualTo(111);
      for (String value : List.of("%", "_", "' OR 1 = 1 --", "<html>")) {
        assertThat(queries.eventCount(condition("event_id", Operator.CONTAINS, value))).isZero();
        assertThat(queries.eventCount(condition("event_id", Operator.NOT_CONTAINS, value)))
            .isEqualTo(300);
      }
      assertThat(queries.eventCount(condition("event_id", Operator.EQUALS, "TARGET://x/1")))
          .isZero();
      assertThat(queries.eventCount(condition("raw_bytes", Operator.AT_MOST, "16"))).isEqualTo(1);
      assertThat(queries.eventCount(condition("received", Operator.GREATER_THAN, "0")))
          .isEqualTo(301);
    }
  }

  @Test
  void unsafeFieldsMalformedValuesAndUnsupportedOperatorsAreRejected() {
    for (Condition invalid :
        List.of(
            condition("id) OR 1 --", Operator.EQUALS, "1"),
            condition("children", Operator.EQUALS, "garbage"),
            condition("children", Operator.EQUALS, "9223372036854775808"),
            condition("last_message", Operator.EQUALS, "yes"),
            condition("event_id", Operator.LESS_THAN, "1"),
            condition("children", Operator.CONTAINS, "1"))) {
      assertThatThrownBy(() -> EventFilterSql.compile(invalid))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  private static Condition condition(String field, Operator operator, String... values) {
    return new Condition(field, operator, List.of(values));
  }

  private SessionDatabase seeded() throws Exception {
    SessionDatabase db = TestSession.migrated(temporary.resolve("events.db"));
    try {
      long stream = EventWriterTest.openStream(db, "stream");
      try (EventWriter writer = new EventWriter(db.writerConnection())) {
        for (int id = 1; id <= 300; id++) {
          EventRecord record =
              new EventRecord(
                  stream,
                  id,
                  switch (id % 3) {
                    case 1 -> 7;
                    case 2 -> 18;
                    default -> 3;
                  },
                  OptionalLong.of(id),
                  false,
                  id % 10,
                  0,
                  id * 128L,
                  64,
                  id % 2 == 0 ? DecodeStatus.OK : DecodeStatus.UNKNOWN_FIELDS,
                  id % 2 != 0,
                  OptionalLong.of(id * 1000L),
                  id * 2000L);
          writer.write(NormalizedEvent.of(record, TestSession.identity(id)));
        }
        writer.write(NormalizedEvent.of(TestSession.eventWithUnknowns(stream, 301)));
        writer.finalizeIngest();
      }
      return db;
    } catch (Exception failure) {
      db.close();
      throw failure;
    }
  }
}
