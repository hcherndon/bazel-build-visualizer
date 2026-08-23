package com.holtherndon.bazelviz.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import javax.swing.JComponent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Plan 24's Phase 10 exit criterion: no known routine path blocks the EDT.
 *
 * <h2>What can be checked, and what cannot</h2>
 *
 * <p>"Nothing blocks the EDT" is not decidable by inspection — a method that
 * takes a microsecond on one session takes a second on another. What <em>is</em>
 * decidable is the structural rule the whole UI was built on: a component that
 * can reach a database must own a thread to do it on. A view that could query
 * and had no executor would be a view whose only option is to query on the
 * event thread.
 *
 * <p>This walks every compiled {@link JComponent} in {@code ui-swing} rather
 * than a list somebody maintains, so a view added next year is checked by the
 * same rule without anybody remembering to add it.
 *
 * <h2>The other half is already enforced</h2>
 *
 * <p>{@code TimelinePaintIsolationTest}, {@code GraphPaintIsolationTest} and
 * {@code FindingsViewTest.theViewCannotRead} assert the converse for the three
 * custom-painted views: they can reach neither a connection nor an executor,
 * because painting must be pure. Together the two rules say: if you can read,
 * you have a thread; if you paint, you can do neither.
 */
final class EdtDisciplineTest {

    /** Field types that mean "this component can block". */
    private static final List<String> CAN_BLOCK = List.of(
            "java.sql.Connection",
            "com.holtherndon.bazelviz.ui.session.SessionSource",
            "com.holtherndon.bazelviz.ui.session.SessionReader",
            "com.holtherndon.bazelviz.ui.session.EntityReader",
            "com.holtherndon.bazelviz.ui.table.RowSource",
            "com.holtherndon.bazelviz.ui.timeline.SpanSource",
            "com.holtherndon.bazelviz.storage.graph.GraphQueries",
            "com.holtherndon.bazelviz.storage.metrics.MetricQueries",
            "com.holtherndon.bazelviz.storage.SessionDatabase");

    /**
     * Field types that own a thread of their own.
     *
     * <p>A component satisfies the rule either by holding an {@link Executor}
     * directly or by holding a service that holds one — which is the better
     * shape, and the one the graph, timeline, metrics and export paths use.
     */
    private static final List<String> OWNS_A_THREAD = List.of(
            "com.holtherndon.bazelviz.ui.graph.GraphLayoutService",
            "com.holtherndon.bazelviz.ui.metrics.MetricsService",
            "com.holtherndon.bazelviz.ui.export.ExportController",
            "com.holtherndon.bazelviz.ui.timeline.TimelineController",
            "com.holtherndon.bazelviz.ui.session.ImportController",
            "com.holtherndon.bazelviz.ui.capture.LaunchController");

    @Test
    @DisplayName("every component that can reach a database owns a thread to do it on")
    void readersHaveThreads() throws Exception {
        List<Class<?>> components = compiledComponents();
        assertThat(components)
                .as("the compiled UI classes; if this is empty the scan is broken, not the code")
                .hasSizeGreaterThan(20);

        List<String> offenders = new ArrayList<>();
        for (Class<?> component : components) {
            List<String> blocking = fieldTypesMatching(component, CAN_BLOCK);
            if (blocking.isEmpty()) {
                continue;
            }
            if (!ownsAThread(component)) {
                offenders.add(component.getSimpleName() + " holds " + blocking
                        + " and no executor or service that owns one");
            }
        }

        assertThat(offenders)
                .as("components that can query and have nowhere but the EDT to do it")
                .isEmpty();
    }

    @Test
    @DisplayName("the rule catches a component that could only query on the event thread")
    void theRuleWouldFire() {
        // A rule nothing can violate proves nothing, so here is one that does.
        class Offender extends JComponent {
            private static final long serialVersionUID = 1L;
            private com.holtherndon.bazelviz.ui.session.EntityReader reader;
        }

        assertThat(fieldTypesMatching(Offender.class, CAN_BLOCK)).isNotEmpty();
        assertThat(ownsAThread(Offender.class)).isFalse();
    }

    private static boolean ownsAThread(Class<?> component) {
        for (Field field : component.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            if (Executor.class.isAssignableFrom(field.getType())
                    || OWNS_A_THREAD.contains(field.getType().getName())) {
                return true;
            }
        }
        return false;
    }

    private static List<String> fieldTypesMatching(Class<?> component, List<String> types) {
        List<String> found = new ArrayList<>();
        for (Field field : component.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            if (types.contains(field.getType().getName())) {
                found.add(field.getType().getSimpleName());
            }
        }
        return found;
    }

    /**
     * Every compiled Swing component in this module.
     *
     * <p>Read off the class output rather than from a list, because a list is a
     * thing somebody has to remember to add a new view to and this rule is
     * exactly the kind nobody remembers.
     */
    private static List<Class<?>> compiledComponents() throws Exception {
        Path classes = Path.of("build", "classes", "java", "main");
        assertThat(Files.isDirectory(classes))
                .as("compiled classes at %s", classes.toAbsolutePath())
                .isTrue();
        List<Class<?>> components = new ArrayList<>();
        try (var walk = Files.walk(classes)) {
            for (Path file : walk.filter(path -> path.toString().endsWith(".class")).toList()) {
                String name = classes.relativize(file).toString()
                        .replace(java.io.File.separatorChar, '.')
                        .replaceAll("\\.class$", "");
                Class<?> loaded;
                try {
                    loaded = Class.forName(name, false, EdtDisciplineTest.class.getClassLoader());
                } catch (Throwable unloadable) {
                    continue;
                }
                if (JComponent.class.isAssignableFrom(loaded)
                        || java.awt.Window.class.isAssignableFrom(loaded)) {
                    components.add(loaded);
                }
            }
        }
        return components;
    }
}
