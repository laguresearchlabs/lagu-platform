package com.lagu.platform.schema.service;

import com.lagu.platform.schema.dto.ListingTypeSchemaDto;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Classifies a schema publish by diffing it against the previously published snapshot.
 *
 * <p>Previously every publish was recorded as SAFE, which made the field useless for deciding
 * whether existing records still validate. Adding a required field is the case that matters most:
 * every stored record that omits it is now invalid, and nothing downstream had any way to know.
 *
 * <p>Values match the {@code change_classification} column's documented domain:
 * {@code SAFE | SOFT_BREAKING | HARD_BREAKING}.
 */
final class SchemaChangeClassifier {

    static final String SAFE = "SAFE";
    static final String SOFT_BREAKING = "SOFT_BREAKING";
    static final String HARD_BREAKING = "HARD_BREAKING";

    private SchemaChangeClassifier() {}

    record Result(String classification, List<String> reasons) {}

    static Result classify(Map<String, Object> previousSnapshot, ListingTypeSchemaDto next) {
        // First ever publish: nothing can break, because nothing exists yet.
        if (previousSnapshot == null || previousSnapshot.isEmpty()) {
            return new Result(SAFE, List.of("initial publish"));
        }

        Map<String, FieldFacts> before = flattenSnapshot(previousSnapshot);
        Map<String, FieldFacts> after = flatten(next);

        List<String> hard = new java.util.ArrayList<>();
        List<String> soft = new java.util.ArrayList<>();

        for (var entry : before.entrySet()) {
            String key = entry.getKey();
            FieldFacts was = entry.getValue();
            FieldFacts now = after.get(key);

            if (now == null) {
                // Stored values for this key are orphaned and will stop being returned.
                hard.add("field '" + key + "' was removed");
                continue;
            }
            if (!was.type().equals(now.type())) {
                hard.add("field '" + key + "' changed type from " + was.type() + " to " + now.type());
            }
            if (!now.enumValues().isEmpty() && !now.enumValues().containsAll(was.enumValues())) {
                Set<String> dropped = new HashSet<>(was.enumValues());
                dropped.removeAll(now.enumValues());
                // Records already holding a dropped value no longer validate.
                hard.add("field '" + key + "' dropped enum value(s) " + dropped);
            }
            if (now.required() && !was.required()) {
                soft.add("field '" + key + "' became required");
            }
        }

        for (String key : after.keySet()) {
            if (before.containsKey(key) || !after.get(key).required()) continue;
            // A new required field invalidates every existing record, but only on next write —
            // reads still work, which is what separates SOFT from HARD.
            soft.add("new required field '" + key + "'");
        }

        if (!hard.isEmpty()) return new Result(HARD_BREAKING, hard);
        if (!soft.isEmpty()) return new Result(SOFT_BREAKING, soft);
        return new Result(SAFE, List.of());
    }

    private record FieldFacts(String type, boolean required, Set<String> enumValues) {}

    private static Map<String, FieldFacts> flatten(ListingTypeSchemaDto schema) {
        Map<String, FieldFacts> out = new HashMap<>();
        for (var section : schema.sections()) {
            for (var f : section.fields()) {
                out.put(f.key(), new FieldFacts(
                        String.valueOf(f.fieldType()),
                        f.required(),
                        f.enumValues() == null ? Set.of() : new HashSet<>(f.enumValues())));
            }
        }
        return out;
    }

    /** The stored snapshot is a serialised {@link ListingTypeSchemaDto}, read back loosely so an
     *  older snapshot missing newer keys still classifies rather than throwing. */
    @SuppressWarnings("unchecked")
    private static Map<String, FieldFacts> flattenSnapshot(Map<String, Object> snapshot) {
        Map<String, FieldFacts> out = new HashMap<>();
        Object sections = snapshot.get("sections");
        if (!(sections instanceof List<?> sectionList)) return out;

        for (Object rawSection : sectionList) {
            if (!(rawSection instanceof Map<?, ?> section)) continue;
            if (!(section.get("fields") instanceof List<?> fieldList)) continue;

            for (Object rawField : fieldList) {
                if (!(rawField instanceof Map<?, ?> field)) continue;
                Object key = field.get("key");
                if (key == null) continue;

                Object enums = field.get("enumValues");
                Set<String> enumValues = enums instanceof List<?> list
                        ? list.stream().map(String::valueOf).collect(java.util.stream.Collectors.toSet())
                        : Set.of();

                out.put(String.valueOf(key), new FieldFacts(
                        String.valueOf(field.get("fieldType")),
                        Boolean.TRUE.equals(field.get("required")),
                        enumValues));
            }
        }
        return out;
    }
}
