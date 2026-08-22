package com.holtherndon.bazelviz.core.id;

import java.util.Objects;
import java.util.Optional;

/**
 * A configured target: a label built in a particular configuration
 * (plan 11.1).
 *
 * <h2>Why the configuration is part of the identity</h2>
 *
 * <p>The same label is routinely built more than once in one invocation — once
 * for the target platform and once for the execution platform that builds the
 * tools, at minimum. Those are different configured targets with different
 * actions, different outputs and different durations, and a table keyed on
 * label alone merges them into one row whose numbers belong to neither.
 *
 * <p>Aspects are part of the identity for the same reason. Bazel emits a
 * separate completion event for an aspect applied to an already-completed
 * target, and the two describe different work.
 *
 * @param label the Bazel label, exactly as the event carried it
 * @param configuration the configuration it was built in
 * @param aspect the aspect applied, absent for the target itself
 */
public record TargetId(String label, ConfigurationId configuration, Optional<String> aspect) {

    public TargetId {
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(configuration, "configuration");
        aspect = Objects.requireNonNull(aspect, "aspect");
        if (label.isEmpty()) {
            throw new IllegalArgumentException("a target label must not be empty");
        }
    }

    /** A configured target with no aspect. */
    public static TargetId of(String label, ConfigurationId configuration) {
        return new TargetId(label, configuration, Optional.empty());
    }

    /** The same target with an aspect applied. */
    public TargetId withAspect(String aspectName) {
        return new TargetId(label, configuration, Optional.of(aspectName));
    }

    /**
     * A stable text key for storage and lookup.
     *
     * <p>Newline-separated because a Bazel label can contain almost anything
     * else — spaces, colons, at-signs, plus signs — and a separator that can
     * appear inside a component makes two different targets collide. A label
     * cannot contain a newline.
     */
    public String storageKey() {
        return label + "\n" + configuration.value() + "\n" + aspect.orElse("");
    }

    /** What a user reads: the label, with its aspect when it has one. */
    public String displayName() {
        return aspect.map(name -> label + " (aspect " + name + ")").orElse(label);
    }

    @Override
    public String toString() {
        return displayName() + " [" + configuration + "]";
    }
}
