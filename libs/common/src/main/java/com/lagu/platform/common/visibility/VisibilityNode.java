package com.lagu.platform.common.visibility;

import java.util.List;

/**
 * Parsed form of a conditional-visibility rule — the AST behind a section's or field's
 * {@code visibleWhen}. See {@code todo/17-conditional-field-visibility.md} (ADR-18).
 *
 * <p>Deliberately a small closed language rather than a general expression evaluator: the same
 * rule is evaluated here, in schema-registry at publish time, and by the TypeScript evaluator in
 * events-ui. A closed AST is the only version of this that can be statically checked for dangling
 * field references and cycles, and the only one an Admin Portal dropdown builder can round-trip.
 */
public sealed interface VisibilityNode {

    /** Comparison against a sibling field's value within the same listing type. */
    record Condition(String field, Op op, Object value) implements VisibilityNode {}

    /** True when every child is true. An empty {@code all} is vacuously true. */
    record All(List<VisibilityNode> nodes) implements VisibilityNode {}

    /** True when any child is true. An empty {@code any} is false, so an unfinished rule
     *  authored in the builder fails closed rather than revealing a field. */
    record Any(List<VisibilityNode> nodes) implements VisibilityNode {}

    /** Negates any node, group or bare condition — {@code {not: {field, op, value}}} is legal,
     *  so negating one comparison does not cost an extra level of grouping. */
    record Not(VisibilityNode node) implements VisibilityNode {}

    enum Op {
        EQ, NEQ, IN, NIN, GT, GTE, LT, LTE, TRUTHY, EMPTY,

        /**
         * An operator this build does not recognise. Only ever produced by the lenient parse used
         * at evaluation time, and always evaluates true, so a rule written by a newer
         * schema-registry degrades to "no filtering" instead of silently discarding user input.
         * {@link VisibilityRules#parseStrict} rejects it, so such a rule can never be published
         * by this build in the first place.
         */
        UNKNOWN
    }
}
