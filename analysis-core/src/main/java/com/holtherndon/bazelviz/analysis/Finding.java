package com.holtherndon.bazelviz.analysis;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * One evidence-backed optimization candidate (plan section 16).
 *
 * <h2>Every field the plan lists, and two that are enforced</h2>
 *
 * <p>Plan 16 requires a title, severity, confidence, evidence, affected
 * actions or targets, metric values, the threshold used, why it may matter,
 * caveats, a suggested next investigation, and links to relevant views. All of
 * them are components here, and two of them are checked at construction because
 * they are Phase 8 exit criteria rather than preferences:
 *
 * <ul>
 *   <li>{@link #evidence} may not be empty — "findings link to supporting
 *       records" is not satisfied by a finding that asserts something about a
 *       build and points at nothing in it.
 *   <li>{@link #whyItMayMatter} and the rest go through {@link FindingLanguage}
 *       — "findings avoid unsupported causal language" holds because a finding
 *       that breaks it cannot be constructed.
 * </ul>
 *
 * <h2>A finding is a candidate, not a diagnosis</h2>
 *
 * <p>Everything here is a correlation over one build that ran once, on one
 * machine, under one set of flags. The rules can say that an action's queue
 * time was most of its attempt; they cannot say that shortening the queue would
 * have shortened the build, because the build where it was shorter was never
 * run. {@link #caveats} is where the specific reason this particular finding
 * might be wrong goes, and it is required.
 *
 * @param provenByFailureData true only when this rests on a structured failure
 *     record rather than on a correlation; it is what permits the words
 *     {@link FindingLanguage} otherwise refuses
 */
public record Finding(
        String ruleId,
        String title,
        Severity severity,
        Confidence confidence,
        List<Evidence> evidence,
        List<MetricValue> metrics,
        String thresholdUsed,
        String whyItMayMatter,
        String caveats,
        String suggestedInvestigation,
        List<Link> links,
        boolean provenByFailureData) {

    public Finding {
        Objects.requireNonNull(ruleId, "ruleId");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(confidence, "confidence");
        evidence = List.copyOf(evidence);
        metrics = List.copyOf(metrics);
        links = List.copyOf(links);
        if (evidence.isEmpty()) {
            throw new IllegalArgumentException(
                    "a finding must link to the records that support it (plan 24, Phase 8): "
                            + title);
        }
        Objects.requireNonNull(thresholdUsed, "thresholdUsed");
        FindingLanguage.check("title", title, provenByFailureData);
        FindingLanguage.check("whyItMayMatter", whyItMayMatter, provenByFailureData);
        FindingLanguage.check("caveats", caveats, provenByFailureData);
        FindingLanguage.check("suggestedInvestigation", suggestedInvestigation,
                provenByFailureData);
        FindingLanguage.requireHedge("whyItMayMatter", whyItMayMatter);
    }

    /** How much of the build this is about. Not how certain it is. */
    public enum Severity {
        /** Worth knowing, unlikely to be where the time went. */
        LOW("Low"),
        /** A visible share of the build. */
        MEDIUM("Medium"),
        /** A large share of the build, or something that blocks measurement. */
        HIGH("High");

        private final String displayName;

        Severity(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }
    }

    /**
     * How well the data supports the finding. Not how large the effect is.
     *
     * <p>Kept separate from severity on purpose: a large effect measured
     * through a source that covered a third of the build is high severity and
     * low confidence, and a reader shown one number cannot tell those apart.
     */
    public enum Confidence {
        /** The supporting source covered little of the build. */
        LOW("Low"),
        /** The source covered most of it. */
        MEDIUM("Medium"),
        /** Every action this is about reported the values it rests on. */
        HIGH("High");

        private final String displayName;

        Confidence(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }
    }

    /** A record in this session that supports the finding. */
    public record Evidence(Kind kind, OptionalLong id, String label, String detail) {

        public Evidence {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(detail, "detail");
        }

        public static Evidence action(long actionId, String label, String detail) {
            return new Evidence(Kind.ACTION, OptionalLong.of(actionId), label, detail);
        }

        public static Evidence group(String key, String detail) {
            return new Evidence(Kind.GROUP, OptionalLong.empty(), key, detail);
        }

        public static Evidence window(long startMicros, String label, String detail) {
            return new Evidence(Kind.TIME_WINDOW, OptionalLong.of(startMicros), label, detail);
        }

        public static Evidence coverage(Coverage coverage) {
            return new Evidence(
                    Kind.COVERAGE, OptionalLong.empty(), coverage.name(), coverage.describe());
        }

        /** What kind of record this points at. */
        public enum Kind {
            ACTION,
            /** A mnemonic, runner, package or other aggregate key. */
            GROUP,
            /** A stretch of the build's clock. */
            TIME_WINDOW,
            /** A coverage figure, which is what a measurement finding rests on. */
            COVERAGE
        }
    }

    /**
     * A number the finding rests on, with the words needed to read it.
     *
     * @param source where the number came from, in the wording a user reads —
     *     required, because "every displayed metric reports source and
     *     completeness" applies to the numbers inside a finding too
     */
    public record MetricValue(String name, String value, String source) {

        public MetricValue {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(source, "source");
        }

        /** Reads a metric series, carrying its own provenance sentence across. */
        public static MetricValue from(MetricSeries series, String value) {
            return new MetricValue(series.name(), value, series.describe());
        }
    }

    /** Where to go to look into this. */
    public record Link(View view, String description, Optional<String> filter,
            OptionalLong focusId) {

        public Link {
            Objects.requireNonNull(view, "view");
            Objects.requireNonNull(description, "description");
            Objects.requireNonNull(filter, "filter");
            Objects.requireNonNull(focusId, "focusId");
        }

        public static Link to(View view, String description) {
            return new Link(view, description, Optional.empty(), OptionalLong.empty());
        }

        public static Link filtered(View view, String description, String filter) {
            return new Link(view, description, Optional.of(filter), OptionalLong.empty());
        }

        public static Link focused(View view, String description, long id) {
            return new Link(view, description, Optional.empty(), OptionalLong.of(id));
        }

        /**
         * The views a finding can send a reader to.
         *
         * <p>Named here rather than referring to the UI's own navigation enum,
         * because analysis-core knows nothing about Swing and a rule should not
         * have to. The UI maps these onto its cards.
         */
        public enum View {
            ACTIONS,
            TIMELINE,
            GRAPH,
            TESTS,
            FAILURES,
            COVERAGE,
            OVERVIEW
        }
    }

    /** One line for a list. The detail lives in the fields. */
    public String summary() {
        return severity.displayName() + " · " + title;
    }
}
