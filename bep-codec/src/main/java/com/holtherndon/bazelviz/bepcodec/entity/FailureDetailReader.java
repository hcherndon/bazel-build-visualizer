package com.holtherndon.bazelviz.bepcodec.entity;

import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.EnumValueDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Message;
import com.holtherndon.bazelviz.core.entity.FailureInfo;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Reads a {@code FailureDetail} into a {@link FailureInfo}, through descriptors
 * rather than through generated accessors.
 *
 * <p>The proto documents its own forward-compatibility contract: the set oneof
 * field is the category, and field number 1 inside it is the subcategory enum.
 * Reading it that way means a category or subcategory added by a Bazel newer
 * than this build's vendored protos is reported by name instead of vanishing —
 * which matters, because the categories are exactly the vocabulary a failures
 * view groups by.
 */
public final class FailureDetailReader {

    private FailureDetailReader() {}

    /**
     * @return empty when the detail is entirely blank, which is how the wire
     *     spells "there was no failure detail" — an empty submessage would
     *     otherwise become a failure row for something that did not fail
     */
    public static Optional<FailureInfo> read(FailureDetail detail) {
        String message = detail.getMessage();
        FieldDescriptor categoryField = setCategoryField(detail);
        if (categoryField == null && message.isEmpty()) {
            return Optional.empty();
        }
        Optional<String> category = Optional.ofNullable(categoryField).map(FieldDescriptor::getName);
        Optional<String> subcategory = categoryField == null
                ? Optional.empty()
                : subcategoryOf((Message) detail.getField(categoryField));
        return Optional.of(new FailureInfo(category, subcategory, message, spawnExitCodeOf(detail)));
    }

    private static FieldDescriptor setCategoryField(FailureDetail detail) {
        for (FieldDescriptor field : detail.getDescriptorForType().getFields()) {
            if (field.getContainingOneof() != null && detail.hasField(field)) {
                return field;
            }
        }
        return null;
    }

    /**
     * The subcategory enum at field number 1, read positionally because that is
     * the contract the proto documents — not by a field name this build would
     * have to know in advance.
     */
    private static Optional<String> subcategoryOf(Message categoryMessage) {
        Descriptor descriptor = categoryMessage.getDescriptorForType();
        FieldDescriptor code = descriptor.findFieldByNumber(1);
        if (code == null || code.getJavaType() != FieldDescriptor.JavaType.ENUM) {
            return Optional.empty();
        }
        Object value = categoryMessage.getField(code);
        return value instanceof EnumValueDescriptor enumValue
                ? Optional.of(enumValue.getName())
                : Optional.empty();
    }

    /**
     * The spawn's exit code, when there is one.
     *
     * <p>Zero is read as absent rather than as a real code. The proto sets this
     * field only for {@code NON_ZERO_EXIT}, so a zero here is proto3's default
     * for "not set" and never a process that exited successfully — a failed
     * spawn that exited 0 is a contradiction the wire cannot express.
     */
    private static OptionalInt spawnExitCodeOf(FailureDetail detail) {
        if (!detail.hasSpawn()) {
            return OptionalInt.empty();
        }
        int code = detail.getSpawn().getSpawnExitCode();
        return code == 0 ? OptionalInt.empty() : OptionalInt.of(code);
    }
}
