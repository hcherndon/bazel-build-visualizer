package com.holtherndon.bazelviz.analysis;

import com.holtherndon.bazelviz.core.source.DataSource;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * How much of the build a source actually describes.
 *
 * <h2>Why a dashboard has to lead with this</h2>
 *
 * <p>Plan 15.5 gives the shape verbatim — timing coverage, runner coverage,
 * input-size coverage, action-graph coverage, target-graph coverage,
 * correlation confidence — and the reason is that every other number on the
 * screen is a statement about the covered subset. A cache-hit rate over the
 * 30% of actions whose cache state was recorded is not the build's cache-hit
 * rate, and the only thing standing between those two readings is this figure
 * being on the same screen.
 *
 * <h2>Two ways to be uncovered</h2>
 *
 * <p>A source can be absent — no execution log was captured — or present and
 * silent about a particular action. Both leave the same hole, and
 * {@link #reason} is what separates "nobody ran this enrichment" from "this
 * enrichment ran and Bazel does not report the field", which are different
 * problems with different fixes.
 *
 * @param name what is covered, in the words a user reads
 * @param covered members the source described
 * @param total members there were
 * @param source which source this is about (plan 11.5)
 * @param reason why the uncovered members are uncovered, when that is knowable
 */
public record Coverage(
        String name, long covered, long total, DataSource source, Optional<String> reason) {

    public Coverage {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(reason, "reason");
        if (covered < 0 || total < 0) {
            throw new IllegalArgumentException(
                    "coverage cannot be negative: " + covered + " of " + total);
        }
        if (covered > total) {
            throw new IllegalArgumentException(
                    "more covered than there are: " + covered + " of " + total);
        }
    }

    public static Coverage of(String name, long covered, long total, DataSource source) {
        return new Coverage(name, covered, total, source, Optional.empty());
    }

    public static Coverage of(
            String name, long covered, long total, DataSource source, String reason) {
        return new Coverage(name, covered, total, source, Optional.of(reason));
    }

    /**
     * A source that produced nothing at all.
     *
     * <p>Zero of zero would divide by nothing and read as complete; this reads
     * as unavailable, which is what it is.
     */
    public static Coverage unavailable(String name, long total, DataSource source, String reason) {
        return new Coverage(name, 0, total, source, Optional.of(reason));
    }

    /** The covered fraction, or empty when there was nothing to cover. */
    public OptionalDouble fraction() {
        return total == 0 ? OptionalDouble.empty() : OptionalDouble.of((double) covered / total);
    }

    public boolean isComplete() {
        return total > 0 && covered == total;
    }

    /** One line, in the shape plan 15.5 asks for. */
    public String describe() {
        if (total == 0) {
            return name + ": nothing to cover";
        }
        if (covered == 0) {
            return name + ": unavailable for all " + total
                    + reason.map(why -> " — " + why).orElse("");
        }
        String line = name + ": " + String.format("%.1f%%", fraction().orElse(0) * 100)
                + " (" + covered + " of " + total + ")";
        return isComplete() ? line : line + reason.map(why -> " — " + why).orElse("");
    }

    /** Every coverage figure a session can state, in display order. */
    public record Report(List<Coverage> entries) {

        public Report {
            entries = List.copyOf(entries);
        }

        /** True when every entry covers everything. */
        public boolean isComplete() {
            return entries.stream().allMatch(Coverage::isComplete);
        }

        /** The entries that do not, which is what a warning banner lists. */
        public List<Coverage> incomplete() {
            return entries.stream().filter(entry -> !entry.isComplete()).toList();
        }

        /** One named entry, when the caller needs a specific figure. */
        public Optional<Coverage> find(String name) {
            return entries.stream().filter(entry -> entry.name().equals(name)).findFirst();
        }
    }
}
