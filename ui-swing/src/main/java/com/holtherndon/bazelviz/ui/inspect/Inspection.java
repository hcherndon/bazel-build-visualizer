package com.holtherndon.bazelviz.ui.inspect;

import com.holtherndon.bazelviz.ui.nav.EntityRef;
import com.holtherndon.bazelviz.ui.files.FileLink;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * What the shared inspector is showing: one entity, described in sections of
 * named fields.
 *
 * <p>One shape for actions, targets, tests and failures rather than four
 * panels, because the interesting part is the same in all four — the fields,
 * whether each is known, and which event the row came from — and because a
 * user who has learned to read one has learned to read them all (plan 17.11).
 *
 * <h2>Unknown is a state, not an empty string</h2>
 *
 * <p>A {@link Field} carries an absent value <em>and</em> the reason it is
 * absent. The panel renders those differently from a value that happens to be
 * blank, which is what makes "unknown values are visibly unknown" true of every
 * view at once rather than of each view separately.
 *
 * @param title the entity's identity — a label, or a path when there is no label
 * @param subtitle a short qualifier: the configuration, the outcome
 * @param sections the fields, grouped
 * @param sourceEventId the {@code bep_events} row this entity was normalized
 *     from, which the panel offers to open. Absent when the entity has no
 *     single source event.
 * @param refs the cross-view identities this entity answers to — its label,
 *     its action, its source event — for the shared navigation actions.
 *     Empty for an inspection built by a view that has not adopted them,
 *     which renders exactly as before.
 */
public record Inspection(
        String title,
        Optional<String> subtitle,
        List<Section> sections,
        OptionalLong sourceEventId,
        List<EntityRef> refs) {

    /** Nothing selected. */
    public static final Inspection NONE =
            new Inspection("", Optional.empty(), List.of(), OptionalLong.empty(), List.of());

    public Inspection {
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(subtitle, "subtitle");
        sections = List.copyOf(sections);
        Objects.requireNonNull(sourceEventId, "sourceEventId");
        refs = List.copyOf(refs);
    }

    public boolean isEmpty() {
        return title.isEmpty() && sections.isEmpty();
    }

    /** A group of related fields under a heading. */
    public record Section(String heading, List<Field> fields) {
        public Section {
            Objects.requireNonNull(heading, "heading");
            fields = List.copyOf(fields);
        }
    }

    /**
     * One named value.
     *
     * @param value the value, absent when it is not known
     * @param unknownNote why it is not known, shown beside the absence. Never
     *     set when {@code value} is present.
     */
    public record Field(
            String name,
            Optional<String> value,
            Optional<String> unknownNote,
            Optional<FileLink> fileLink) {

        public Field {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(unknownNote, "unknownNote");
            Objects.requireNonNull(fileLink, "fileLink");
            if (value.isPresent() && unknownNote.isPresent()) {
                throw new IllegalArgumentException(
                        "a field with a value must not also explain why it has none: " + name);
            }
            if (fileLink.isPresent() && value.isEmpty()) {
                throw new IllegalArgumentException(
                        "an openable file field must have a displayed location: " + name);
            }
        }

        /** Compatibility shape for ordinary, non-openable fields. */
        public Field(String name, Optional<String> value, Optional<String> unknownNote) {
            this(name, value, unknownNote, Optional.empty());
        }

        public static Field of(String name, String value) {
            return new Field(name, Optional.of(value), Optional.empty(), Optional.empty());
        }

        /** A known file location with an explicit Open action. */
        public static Field file(String name, String value, FileLink link) {
            return new Field(name, Optional.of(value), Optional.empty(), Optional.of(link));
        }

        /** A value that is not known, and why. */
        public static Field unknown(String name, String why) {
            return new Field(name, Optional.empty(), Optional.of(why), Optional.empty());
        }

        /** A value that is not known, with nothing more to say about it. */
        public static Field unknown(String name) {
            return new Field(name, Optional.empty(), Optional.empty(), Optional.empty());
        }

        public boolean isKnown() {
            return value.isPresent();
        }
    }

    /** Builds an inspection without the ceremony of nested list literals. */
    public static final class Builder {
        private final String title;
        private Optional<String> subtitle = Optional.empty();
        private final List<Section> sections = new ArrayList<>();
        private OptionalLong sourceEventId = OptionalLong.empty();
        private final List<EntityRef> refs = new ArrayList<>();
        private String heading;
        private List<Field> fields = new ArrayList<>();

        public Builder(String title) {
            this.title = Objects.requireNonNull(title, "title");
        }

        public Builder subtitle(String value) {
            this.subtitle = Optional.of(value);
            return this;
        }

        public Builder sourceEvent(OptionalLong eventId) {
            this.sourceEventId = eventId;
            return this;
        }

        /** Adds one cross-view identity for the shared navigation actions. */
        public Builder ref(EntityRef ref) {
            refs.add(Objects.requireNonNull(ref, "ref"));
            return this;
        }

        public Builder section(String value) {
            flush();
            this.heading = value;
            return this;
        }

        public Builder field(Field field) {
            fields.add(field);
            return this;
        }

        public Builder field(String name, String value) {
            return field(Field.of(name, value));
        }

        public Builder file(String name, String value, FileLink link) {
            return field(Field.file(name, value, link));
        }

        public Builder unknown(String name, String why) {
            return field(Field.unknown(name, why));
        }

        public Inspection build() {
            flush();
            return new Inspection(title, subtitle, sections, sourceEventId, refs);
        }

        private void flush() {
            if (heading != null && !fields.isEmpty()) {
                sections.add(new Section(heading, fields));
            }
            fields = new ArrayList<>();
        }
    }
}
