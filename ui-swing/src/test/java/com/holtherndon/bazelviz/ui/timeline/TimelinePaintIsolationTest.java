package com.holtherndon.bazelviz.ui.timeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Painting cannot reach a database, and this is how that stays true.
 *
 * <p>Plan 24's fourth Phase 6 exit criterion is that no SQLite access occurs
 * during painting. Reviewing a paint method for it works once; a test that
 * inspects what the painting classes can reach works every time someone adds a
 * field.
 *
 * <p>The check is deliberately about reachability rather than about behaviour.
 * A paint method that happens not to query today but holds a {@code Connection}
 * is one refactor away from doing so, and the point of the design is that the
 * refactor should be impossible rather than merely discouraged.
 */
final class TimelinePaintIsolationTest {

    /** Types that mean a class can talk to the database. */
    private static final List<String> DATABASE_TYPES = List.of(
            "java.sql.",
            "com.holtherndon.bazelviz.storage.",
            "com.holtherndon.bazelviz.ui.session.EntityReader",
            "com.holtherndon.bazelviz.ui.session.SessionSource",
            "com.holtherndon.bazelviz.ui.session.SessionReader");

    @Test
    @DisplayName("the timeline view holds nothing that can reach a database")
    void theViewCannotQuery() {
        assertThat(databaseFieldsOf(TimelineView.class))
                .as("TimelineView must paint from its prepared model alone")
                .isEmpty();
    }

    @Test
    @DisplayName("nor does anything it paints from")
    void theModelCannotQuery() {
        for (Class<?> painted : List.of(
                TimelineModel.class, TimelineLodIndex.class, SpanWindow.class,
                TimelineViewport.class, TimelineTransform.class)) {
            assertThat(databaseFieldsOf(painted))
                    .as("%s is painted from and must carry no database handle",
                            painted.getSimpleName())
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("the controller is where the database lives, so the split is real")
    void theControllerIsWhereQueryingHappens() {
        // The complement of the checks above. If this were also empty the split
        // would be a coincidence rather than a design, and the tests above
        // would be passing for the wrong reason.
        assertThat(mentionsDatabase(TimelineController.class))
                .as("TimelineController is the half that queries")
                .isTrue();
    }

    private static List<String> databaseFieldsOf(Class<?> type) {
        List<String> offenders = new ArrayList<>();
        for (Class<?> current = type; current != null && current != Object.class;
                current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                String name = field.getType().getName();
                if (DATABASE_TYPES.stream().anyMatch(name::startsWith)) {
                    offenders.add(current.getSimpleName() + "." + field.getName()
                            + " : " + name);
                }
            }
        }
        return offenders;
    }

    private static boolean mentionsDatabase(Class<?> type) {
        for (java.lang.reflect.Method method : type.getDeclaredMethods()) {
            for (Class<?> parameter : method.getParameterTypes()) {
                if (DATABASE_TYPES.stream()
                        .anyMatch(prefix -> parameter.getName().startsWith(prefix))) {
                    return true;
                }
            }
        }
        for (Field field : type.getDeclaredFields()) {
            if (DATABASE_TYPES.stream()
                    .anyMatch(prefix -> field.getType().getName().startsWith(prefix))) {
                return true;
            }
        }
        return false;
    }
}
