package com.holtherndon.bazelviz.capture.live;

import com.holtherndon.bazelviz.format.session.AtomicFiles;
import com.holtherndon.bazelviz.format.session.json.JsonValue;
import com.holtherndon.bazelviz.format.session.json.JsonWriter;
import com.holtherndon.bazelviz.runner.plan.AddedFlag;
import com.holtherndon.bazelviz.runner.plan.InstrumentationPlan;
import com.holtherndon.bazelviz.runner.plan.PlanConflict;
import com.holtherndon.bazelviz.runner.plan.ReplacedFlag;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Writes {@code instrumentation-plan.json} into the session.
 *
 * <h2>Why this is stored at all</h2>
 *
 * <p>The manifest already lists the injected flags. This records why each one
 * was added, what it cost, which conflicts arose and how they were resolved.
 * ADR-007 makes instrumentation transparent, and transparency that lasts only
 * as long as the dialog is open is not transparency: six months later, the
 * question "why was my build run with these extra flags" has to be answerable
 * from the session alone.
 *
 * <p>Written once, at launch, and never read back by this application — it is
 * evidence for a person, not state for a program. That is deliberate: nothing
 * downstream can come to depend on its shape, so it can grow as the planner
 * does without a migration.
 */
public final class InstrumentationPlanCodec {

    /** Bumped when the shape changes, so a reader knows what it is looking at. */
    public static final int FORMAT_VERSION = 1;

    private InstrumentationPlanCodec() {}

    /** Writes the plan atomically, so a crash cannot leave half a file. */
    public static void write(Path file, InstrumentationPlan plan, Preflight preflight)
            throws IOException {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(preflight, "preflight");
        AtomicFiles.writeString(file, JsonWriter.writePretty(toJson(plan, preflight)));
    }

    /** The plan as JSON, exposed for tests and for a redacted export. */
    public static JsonValue toJson(InstrumentationPlan plan, Preflight preflight) {
        Map<String, JsonValue> root = new LinkedHashMap<>();
        root.put("formatVersion", JsonValue.of(FORMAT_VERSION));
        root.put("preset", JsonValue.of(plan.preset().name()));
        root.put("bazelExecutable", JsonValue.of(preflight.executable().resolved().toString()));
        preflight.executable().effectiveVersion()
                .ifPresent(version -> root.put("bazelVersion", JsonValue.of(version)));
        root.put("capabilityDetection", JsonValue.of(preflight.capabilities().detection().name()));
        root.put("besEndpoint", JsonValue.of(preflight.endpoint().besBackendUri()));
        preflight.workspace().workspaceRoot()
                .ifPresent(root2 -> root.put("workspaceRoot", JsonValue.of(root2.toString())));
        root.put("workingDirectory", JsonValue.of(plan.original().workingDirectory().toString()));
        root.put("originalCommand", JsonValue.JsonArray.ofStrings(plan.original().toArgv()));
        root.put("effectiveCommand", JsonValue.JsonArray.ofStrings(plan.effective().toArgv()));
        root.put("addedFlags", addedFlags(plan.addedFlags()));
        root.put("replacedFlags", replacedFlags(plan.replacedFlags()));
        root.put("conflicts", conflicts(plan.conflicts()));
        root.put("warnings", JsonValue.JsonArray.ofStrings(plan.warnings()));
        root.put("errors", JsonValue.JsonArray.ofStrings(plan.errors()));
        root.put("expectedOutputs", JsonValue.JsonArray.ofStrings(
                plan.expectedOutputs().stream().map(Path::toString).toList()));
        root.put("sourceAvailability", availability(plan));
        root.put("probeWarnings", JsonValue.JsonArray.ofStrings(preflight.capabilities().probeWarnings()));
        return new JsonValue.JsonObject(root);
    }

    private static JsonValue addedFlags(List<AddedFlag> flags) {
        List<JsonValue> entries = new ArrayList<>(flags.size());
        for (AddedFlag flag : flags) {
            Map<String, JsonValue> member = new LinkedHashMap<>();
            member.put("flag", JsonValue.of(flag.argv()));
            member.put("placement", JsonValue.of(flag.placement().name()));
            member.put("capability", JsonValue.of(flag.capability().name()));
            member.put("capabilityStatus", JsonValue.of(flag.capabilityStatus().name()));
            member.put("applied", JsonValue.of(flag.isApplied()));
            member.put("reason", JsonValue.of(flag.reason()));
            member.put("enables", JsonValue.of(flag.enables().name()));
            member.put("overhead", JsonValue.of(flag.overhead().displayName()));
            flag.writesFile().ifPresent(file -> member.put("writesFile", JsonValue.of(file.toString())));
            member.put("mayContainSensitiveData", JsonValue.of(flag.mayContainSensitiveData()));
            member.put("userCanDisable", JsonValue.of(flag.userCanDisable()));
            entries.add(new JsonValue.JsonObject(member));
        }
        return JsonValue.JsonArray.of(entries);
    }

    private static JsonValue replacedFlags(List<ReplacedFlag> flags) {
        List<JsonValue> entries = new ArrayList<>(flags.size());
        for (ReplacedFlag flag : flags) {
            Map<String, JsonValue> member = new LinkedHashMap<>();
            member.put("original", JsonValue.of(flag.original()));
            member.put("replacement", JsonValue.of(flag.replacement()));
            member.put("approvedBy", JsonValue.of(flag.approvedBy()));
            member.put("reason", JsonValue.of(flag.reason()));
            entries.add(new JsonValue.JsonObject(member));
        }
        return JsonValue.JsonArray.of(entries);
    }

    private static JsonValue conflicts(List<PlanConflict> conflicts) {
        List<JsonValue> entries = new ArrayList<>(conflicts.size());
        for (PlanConflict conflict : conflicts) {
            Map<String, JsonValue> member = new LinkedHashMap<>();
            member.put("kind", JsonValue.of(conflict.kind().name()));
            member.put("mandatory", JsonValue.of(conflict.mandatory()));
            member.put("summary", JsonValue.of(conflict.summary()));
            member.put("detail", JsonValue.of(conflict.detail()));
            conflict.offendingArgv()
                    .ifPresent(argv -> member.put("offendingArgv", JsonValue.of(argv)));
            List<JsonValue> resolutions = new ArrayList<>();
            for (PlanConflict.Resolution resolution : conflict.resolutions()) {
                Map<String, JsonValue> option = new LinkedHashMap<>();
                option.put("id", JsonValue.of(resolution.id()));
                option.put("label", JsonValue.of(resolution.label()));
                option.put("consequence", JsonValue.of(resolution.consequence()));
                resolutions.add(new JsonValue.JsonObject(option));
            }
            member.put("resolutions", JsonValue.JsonArray.of(resolutions));
            entries.add(new JsonValue.JsonObject(member));
        }
        return JsonValue.JsonArray.of(entries);
    }

    private static JsonValue availability(InstrumentationPlan plan) {
        Map<String, JsonValue> members = new LinkedHashMap<>();
        plan.sourceAvailability().bySource().forEach((source, entry) -> {
            Map<String, JsonValue> member = new LinkedHashMap<>();
            member.put("availability", JsonValue.of(entry.availability().name()));
            member.put("reason", JsonValue.of(entry.reason()));
            entry.enabledBy().ifPresent(flag -> member.put("enabledBy", JsonValue.of(flag)));
            members.put(source.name(), new JsonValue.JsonObject(member));
        });
        return new JsonValue.JsonObject(members);
    }
}
