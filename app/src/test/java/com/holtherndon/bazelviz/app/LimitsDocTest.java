package com.holtherndon.bazelviz.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * `docs/limits.md` against the constants it describes.
 *
 * <h2>Why the document is the input</h2>
 *
 * <p>Plan 24's Phase 10 exit criterion is that every limit is explicit, and the
 * artefact that makes it explicit is a page a person can read. A page is worth
 * nothing if it drifts — the Phase 7 audit found a status document calling
 * three finished phases "not started", and the Phase 9 audit found a privacy
 * page describing masking that did not exist. So the page is parsed and each
 * row is checked against the field it names.
 *
 * <p>This module is where it lives because {@code app} is the only one that
 * depends on everything, so every constant in the table is on its classpath.
 */
final class LimitsDocTest {

    /** A table row naming a constant in backticks and, sometimes, a number. */
    private static final Pattern ROW = Pattern.compile(
            "^\\|[^|]*\\|\\s*`([\\w.]+)`(?:\\s*—\\s*`?(\\w+)`?)?\\s*\\|\\s*([^|]*?)\\s*\\|");

    private static Path document() {
        return Path.of("..", "docs", "limits.md");
    }

    @Test
    @DisplayName("every constant the limits page names exists, with the value it claims")
    void theDocumentMatchesTheCode() throws Exception {
        List<String> lines = Files.readAllLines(document());
        List<String> problems = new ArrayList<>();
        int checked = 0;

        for (String line : lines) {
            Matcher matcher = ROW.matcher(line);
            if (!matcher.find()) {
                continue;
            }
            String reference = matcher.group(1);
            String component = matcher.group(2);
            String declared = matcher.group(3);
            if (component != null) {
                // A record component rather than a constant: checked by name
                // only, because its value lives in a factory method.
                if (!hasRecordComponent(reference, component, problems)) {
                    continue;
                }
                checked++;
                continue;
            }
            int lastDot = reference.lastIndexOf('.');
            String className = reference.substring(0, lastDot);
            String fieldName = reference.substring(lastDot + 1);
            Class<?> owner;
            try {
                owner = Class.forName(className);
            } catch (ClassNotFoundException missing) {
                problems.add("no such class: " + className);
                continue;
            }
            Field field;
            try {
                field = owner.getDeclaredField(fieldName);
            } catch (NoSuchFieldException missing) {
                problems.add("no such field: " + reference);
                continue;
            }
            field.setAccessible(true);
            Object actual = field.get(null);
            checked++;
            if (!declared.matches("\\d+")) {
                // A duration or a prose default; the page still had to name a
                // real field, which it just did.
                continue;
            }
            long expected = Long.parseLong(declared);
            long real = ((Number) actual).longValue();
            if (real != expected) {
                problems.add(reference + " is " + real + ", the page says " + expected);
            }
        }

        assertThat(problems).as("limits.md against the code").isEmpty();
        // A parser that matched nothing would pass silently, which is the one
        // way a documentation test is worse than no test.
        assertThat(checked).as("rows checked").isGreaterThanOrEqualTo(20);
    }

    private static boolean hasRecordComponent(
            String className, String component, List<String> problems) {
        try {
            Class<?> owner = Class.forName(className);
            for (java.lang.reflect.RecordComponent declared : owner.getRecordComponents()) {
                if (declared.getName().equals(component)) {
                    return true;
                }
            }
            problems.add(className + " has no component " + component);
        } catch (ClassNotFoundException | NullPointerException missing) {
            problems.add("no such record: " + className);
        }
        return false;
    }

    @Test
    @DisplayName("the page states the gap between plan 20.3 and what exists")
    void theGapIsStated() throws Exception {
        // The honest half. Fourteen of plan 20.3's nineteen limits exist as
        // constants and none has a settings screen; five are not implemented at
        // all. A page that listed only what exists would read as completeness.
        // Whitespace-normalised: a documentation test that depends on where a
        // line happens to wrap fails the next time somebody reflows a
        // paragraph, which teaches people to delete the test rather than fix
        // the page.
        String text = Files.readString(document()).replaceAll("\\s+", " ");

        assertThat(text)
                .contains("plan 20.3")
                .contains("none of them has a settings screen")
                .contains("not implemented");
    }
}
