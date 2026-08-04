package com.lagu.platform.schema.service;

import com.lagu.platform.common.exception.ValidationException;
import com.lagu.platform.common.visibility.VisibilityRules;
import com.lagu.platform.schema.dto.ListingTypeSchemaDto;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Whole-schema checks on conditional-visibility rules, run at publish time.
 *
 * <p>These cannot be done when a rule is written, only when a listing type is assembled. Field
 * groups are shared across listing types, so a rule on {@code address} may reference
 * {@code venue_type} — a field WEDDING_EVENT has and CORPORATE_EVENT does not. In the type that
 * lacks it the rule would quietly evaluate against nothing and hide the section forever. Catching
 * that at publish is the whole reason validation runs against the *flattened* schema.
 */
final class SchemaRuleValidator {

    private SchemaRuleValidator() {}

    static void validate(ListingTypeSchemaDto schema) {
        Set<String> known = new HashSet<>();
        for (var section : schema.sections()) {
            for (var field : section.fields()) known.add(field.key());
        }

        List<String> errors = new ArrayList<>();
        // field key -> keys its visibility depends on, for cycle detection.
        Map<String, Set<String>> edges = new HashMap<>();

        for (var section : schema.sections()) {
            Set<String> sectionDeps = depsOf(section.visibleWhen(), "section '" + section.sectionKey() + "'", known, errors);

            for (var field : section.fields()) {
                Set<String> fieldDeps = depsOf(field.visibleWhen(), "field '" + field.key() + "'", known, errors);
                // A field's visibility is its own rule AND its section's, so it depends on both.
                Set<String> combined = new LinkedHashSet<>(sectionDeps);
                combined.addAll(fieldDeps);
                combined.remove(field.key()); // self-reference is reported separately below
                edges.put(field.key(), combined);

                if (fieldDeps.contains(field.key()) || sectionDeps.contains(field.key())) {
                    errors.add("field '" + field.key() + "' visibility depends on itself");
                }
            }
        }

        findCycle(edges).ifPresent(cycle ->
                errors.add("visibility rules form a cycle: " + String.join(" -> ", cycle)));

        if (!errors.isEmpty()) {
            throw new ValidationException("listing type " + schema.listingType(), errors);
        }
    }

    private static Set<String> depsOf(Map<String, Object> rule, String where, Set<String> known, List<String> errors) {
        if (rule == null || rule.isEmpty()) return Set.of();
        try {
            VisibilityRules.parseStrict(rule);
        } catch (IllegalArgumentException e) {
            errors.add(where + ": " + e.getMessage());
            return Set.of();
        }
        Set<String> deps = VisibilityRules.dependencies(rule);
        for (String dep : deps) {
            if (!known.contains(dep)) {
                errors.add(where + " references unknown field '" + dep + "'");
            }
        }
        return deps;
    }

    /** Iterative DFS over the dependency graph, returning the first cycle found. */
    private static java.util.Optional<List<String>> findCycle(Map<String, Set<String>> edges) {
        Set<String> settled = new HashSet<>();

        for (String start : edges.keySet()) {
            if (settled.contains(start)) continue;

            LinkedHashSet<String> path = new LinkedHashSet<>();
            var cycle = walk(start, edges, settled, path);
            if (cycle.isPresent()) return cycle;
        }
        return java.util.Optional.empty();
    }

    private static java.util.Optional<List<String>> walk(String node, Map<String, Set<String>> edges,
                                                         Set<String> settled, LinkedHashSet<String> path) {
        if (path.contains(node)) {
            List<String> cycle = new ArrayList<>(path);
            cycle = cycle.subList(cycle.indexOf(node), cycle.size());
            cycle.add(node);
            return java.util.Optional.of(cycle);
        }
        if (settled.contains(node)) return java.util.Optional.empty();

        path.add(node);
        for (String next : edges.getOrDefault(node, Set.of())) {
            var cycle = walk(next, edges, settled, path);
            if (cycle.isPresent()) return cycle;
        }
        path.remove(node);
        settled.add(node);
        return java.util.Optional.empty();
    }
}
