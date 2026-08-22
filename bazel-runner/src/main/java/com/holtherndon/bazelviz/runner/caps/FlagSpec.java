package com.holtherndon.bazelviz.runner.caps;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * One flag as the probed binary describes it.
 *
 * <p>A projection of {@code bazel_flags.FlagInfo}, which is what
 * {@code bazel help flags-as-proto} emits. It is re-stated as a record rather
 * than passed around as the generated type so that the capability API does not
 * force every caller onto the protobuf classpath, and so that the text-scraping
 * fallback can produce the same shape from {@code bazel help build --long} when
 * the proto probe is unavailable.
 *
 * <p>Fields the fallback cannot determine are absent rather than guessed:
 * {@link #requiresValue} is an {@link Optional} because "this flag needs a
 * value" is a fact the help text does not reliably state, and assuming it would
 * make the planner emit {@code --flag=value} where {@code --flag} was wanted.
 *
 * @param name flag name without leading dashes
 * @param commands the Bazel commands that accept it
 * @param hasNegativeForm true when {@code --noname} also exists
 * @param requiresValue whether a value must be supplied, when known
 * @param defaultValue the binary's own default, for showing the user what the
 *     injected flag is changing
 * @param enumValues the legal values for an enum-typed flag, empty when the flag
 *     is not enum-typed or the probe could not tell
 */
public record FlagSpec(
        String name,
        Set<String> commands,
        boolean hasNegativeForm,
        Optional<Boolean> requiresValue,
        Optional<String> defaultValue,
        List<String> enumValues) {

    public FlagSpec {
        Objects.requireNonNull(name, "name");
        commands = Set.copyOf(commands);
        requiresValue = Objects.requireNonNull(requiresValue, "requiresValue");
        defaultValue = Objects.requireNonNull(defaultValue, "defaultValue");
        enumValues = List.copyOf(enumValues);
        if (name.startsWith("-")) {
            throw new IllegalArgumentException("flag names are stored without dashes, got " + name);
        }
    }

    /** A flag known only by name and the commands that take it. */
    public static FlagSpec of(String name, Set<String> commands) {
        return new FlagSpec(name, commands, false, Optional.empty(), Optional.empty(), List.of());
    }

    public boolean appliesTo(String command) {
        return commands.contains(command);
    }

    /** True when {@code value} is one this flag will accept, as far as the probe knows. */
    public boolean accepts(String value) {
        return enumValues.isEmpty() || enumValues.contains(value);
    }
}
