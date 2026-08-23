package com.holtherndon.bazelviz.ui.graph;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The graph canvas cannot reach a database, and this is how that stays true.
 *
 * <p>Plan 17.7: "never query SQLite from {@code paintComponent}". The same
 * check the timeline gets, for the same reason — reviewing a paint method works
 * once, and a test that inspects what the painting classes can reach works every
 * time someone adds a field.
 *
 * <p>Deliberately about reachability rather than behaviour. A paint method that
 * happens not to query today but holds a {@code Connection} is one refactor away
 * from doing so, and the design's promise is that the refactor should be
 * impossible rather than merely discouraged.
 */
final class GraphPaintIsolationTest {

    /** Types that mean a class can talk to the database. */
    private static final List<String> DATABASE_TYPES = List.of(
            "java.sql.",
            "com.holtherndon.bazelviz.storage.",
            "com.holtherndon.bazelviz.ui.session.EntityReader",
            "com.holtherndon.bazelviz.ui.session.SessionSource",
            "com.holtherndon.bazelviz.ui.session.SessionReader");

    @Test
    @DisplayName("the canvas holds nothing that can reach a database")
    void theCanvasCannotQuery() {
        assertThat(databaseFieldsOf(GraphCanvas.class))
                .as("GraphCanvas must paint from its prepared model alone")
                .isEmpty();
    }

    @Test
    @DisplayName("nor does anything it paints from")
    void thePaintedTypesCannotQuery() {
        for (Class<?> painted : List.of(
                GraphModel.class, GraphSpatialIndex.class, GraphTransform.class,
                GraphColours.class)) {
            assertThat(databaseFieldsOf(painted))
                    .as("%s is painted from and must carry no database handle",
                            painted.getSimpleName())
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("the service is where the database lives, so the split is real")
    void theServiceIsWhereQueryingHappens() {
        // The complement of the checks above. If this were also empty the split
        // would be a coincidence rather than a design, and the tests above would
        // be passing for the wrong reason.
        assertThat(databaseFieldsOf(GraphLayoutService.class))
                .as("GraphLayoutService is the one class here that reads the session")
                .isNotEmpty();
    }

    @Test
    @DisplayName("the canvas cannot lay a graph out either")
    void theCanvasCannotLayOut() {
        // Plan 17.7's other prohibition: never perform layout on the EDT. The
        // canvas has no route to a layout function -- it is handed a finished
        // GraphModel -- so the EDT cannot start one however the code changes.
        for (Field field : GraphCanvas.class.getDeclaredFields()) {
            assertThat(field.getType().getName())
                    .as("%s", field.getName())
                    .doesNotContain("GraphLayoutService")
                    .doesNotContain("ExecutorService");
        }
    }

    private static List<String> databaseFieldsOf(Class<?> type) {
        List<String> offenders = new ArrayList<>();
        for (Field field : type.getDeclaredFields()) {
            String fieldType = field.getType().getName();
            for (String database : DATABASE_TYPES) {
                if (fieldType.startsWith(database) || fieldType.equals(database)) {
                    offenders.add(field.getName() + " : " + fieldType);
                }
            }
        }
        return offenders;
    }
}
