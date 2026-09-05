package com.holtherndon.bazelviz.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Rules 9 and 16, checked by construction rather than by measurement.
 *
 * <h2>Why a structural test and not a heap dump</h2>
 *
 * <p>Rule 9 says never retain all events as Java objects, and plan section 25 says the application
 * must not construct an object-per-edge whole graph. A heap measurement proves the current build
 * does not — `docs/performance.md` has those figures, 176 MB of CSR against 704 MB of
 * object-per-edge at Tier 2 — but it proves it for the inputs that were measured, and it fails long
 * after somebody has written the field that broke it.
 *
 * <p>This checks the thing that cannot be undone accidentally: the classes that hold the per-event
 * and per-edge data hold it in primitive arrays. A {@code List<Edge>} added to any of them fails
 * here, in the module's own test run, with the field named.
 *
 * <h2>What is deliberately not on this list</h2>
 *
 * <p>Classes that legitimately hold collections of bounded size — a page of two hundred rows, forty
 * aggregate groups, a dozen catalog entries. The rule is about data that scales with the build, not
 * about every collection.
 */
final class BoundedMemoryTest {

  /**
   * Classes whose whole state must be primitive arrays.
   *
   * <p>These hold one entry per edge, per span or per observation, so a collection of any kind in
   * one of them is a per-item object header.
   */
  private static final List<String> MUST_BE_FLAT =
      List.of(
          "com.holtherndon.bazelviz.graph.CsrBuilder",
          "com.holtherndon.bazelviz.ui.timeline.TimelineLodIndex",
          "com.holtherndon.bazelviz.analysis.ConcurrencySweep$Spans",
          "com.holtherndon.bazelviz.analysis.QuantileSketch",
          "com.holtherndon.bazelviz.analysis.QuantileSketch$Distribution");

  /**
   * Classes that stream the whole build and may hold bounded caches.
   *
   * <p>A different rule, because an LRU keyed by interned text is exactly the right design here and
   * a blanket "no maps" would forbid it. What they may not do is retain the <em>domain objects</em>
   * — an event, an action, a span — which is what rule 9 is actually about.
   *
   * <p>Written after this test's first version flagged {@code EntityWriter.knownLabels} and the
   * code turned out to be right: it is a {@code LinkedHashMap} with {@code removeEldestEntry},
   * bounded by the dictionary-cache size. The rule was too coarse, not the field.
   */
  private static final List<String> MAY_CACHE_BUT_NOT_RETAIN =
      List.of(
          "com.holtherndon.bazelviz.storage.events.EventWriter",
          "com.holtherndon.bazelviz.storage.entities.EntityWriter",
          "com.holtherndon.bazelviz.storage.events.StringDictionary");

  @Test
  @DisplayName("what must be flat holds primitive arrays and nothing else")
  void flatAccumulatorsAreFlat() throws Exception {
    List<String> offenders = new ArrayList<>();
    for (String name : MUST_BE_FLAT) {
      Class<?> owner = Class.forName(name);
      for (Field field : owner.getDeclaredFields()) {
        if (Modifier.isStatic(field.getModifiers())) {
          continue;
        }
        Class<?> type = field.getType();
        if (Collection.class.isAssignableFrom(type) || Map.class.isAssignableFrom(type)) {
          offenders.add(
              owner.getSimpleName()
                  + "."
                  + field.getName()
                  + " is a "
                  + type.getSimpleName()
                  + ", which is one object header per item");
        }
        if (type.isArray() && !isPrimitiveOrNested(type.getComponentType())) {
          offenders.add(owner.getSimpleName() + "." + field.getName() + " is an array of objects");
        }
      }
    }

    assertThat(offenders).as("rule 9 and ADR-006").isEmpty();
  }

  @Test
  @DisplayName("what streams the build caches text, never the build's own objects")
  void streamingWritersRetainNoDomainObjects() throws Exception {
    List<String> offenders = new ArrayList<>();
    for (String name : MAY_CACHE_BUT_NOT_RETAIN) {
      Class<?> owner = Class.forName(name);
      for (Field field : owner.getDeclaredFields()) {
        if (Modifier.isStatic(field.getModifiers())) {
          continue;
        }
        if (!(field.getGenericType() instanceof ParameterizedType parameterized)) {
          continue;
        }
        for (java.lang.reflect.Type argument : parameterized.getActualTypeArguments()) {
          if (argument instanceof Class<?> element
              && element.getName().startsWith("com.holtherndon.bazelviz")) {
            offenders.add(
                owner.getSimpleName()
                    + "."
                    + field.getName()
                    + " holds "
                    + element.getSimpleName()
                    + ", a domain object, one per item");
          }
        }
      }
    }

    assertThat(offenders).as("rule 9: never retain all events as Java objects").isEmpty();
  }

  private static boolean isPrimitiveOrNested(Class<?> component) {
    return component.isPrimitive()
        || (component.isArray() && isPrimitiveOrNested(component.getComponentType()));
  }

  @Test
  @DisplayName("the graph index is flat on heap and retains only bounded mapping metadata")
  void theGraphIsNotObjectPerEdge() throws Exception {
    // Plan section 25's definition of done names this one specifically, and
    // ADR-006 exists because of it. The small/test form owns two primitive arrays; the production
    // form reads the same logical arrays from bounded read-only mapping segments.
    Class<?> csr = Class.forName("com.holtherndon.bazelviz.graph.CsrGraph");
    assertThat(instanceFields(csr)).containsExactly("storage: Storage");

    Class<?> heapStorage = nestedClass(csr, "HeapStorage");
    assertThat(instanceFields(heapStorage))
        .containsExactlyInAnyOrder("offsets: long[]", "targets: int[]");

    Class<?> mappedStorage = nestedClass(csr, "MappedStorage");
    assertThat(instanceFields(mappedStorage))
        .containsExactlyInAnyOrder(
            "nodeCount: long",
            "edgeCount: long",
            "offsetsBytes: long",
            "bodyBytes: long",
            "segmentBytes: long",
            "segments: MemorySegment[]",
            "arena: Arena");
  }

  private static Class<?> nestedClass(Class<?> owner, String simpleName) {
    return Arrays.stream(owner.getDeclaredClasses())
        .filter(candidate -> candidate.getSimpleName().equals(simpleName))
        .findFirst()
        .orElseThrow();
  }

  private static List<String> instanceFields(Class<?> owner) {
    return Arrays.stream(owner.getDeclaredFields())
        .filter(field -> !Modifier.isStatic(field.getModifiers()))
        .map(field -> field.getName() + ": " + field.getType().getSimpleName())
        .toList();
  }

  @Test
  @DisplayName("the rule would fire on a class that kept its events")
  void theRuleWouldFire() {
    // A list nothing can violate proves nothing.
    class Offender {
      private final List<String> everyEvent = new ArrayList<>();

      @SuppressWarnings("unused")
      List<String> events() {
        return everyEvent;
      }
    }

    List<String> offenders = new ArrayList<>();
    for (Field field : Offender.class.getDeclaredFields()) {
      if (!Modifier.isStatic(field.getModifiers())
          && Collection.class.isAssignableFrom(field.getType())) {
        offenders.add(field.getName());
      }
    }
    assertThat(offenders).contains("everyEvent");
  }
}
