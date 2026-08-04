package com.lagu.platform.common.visibility;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Conformance suite for the visibility rule language. These cases are deliberately the same as
 * {@code events-ui/src/lib/schema-form/visibility.test.ts} — the two evaluators disagreeing is the
 * one failure this design cannot absorb, because it produces a form the UI accepts and the server
 * rejects. Change one side, change the other.
 */
class VisibilityRulesTest {

    private static Map<String, Object> cond(String field, String op, Object value) {
        Map<String, Object> c = new HashMap<>();
        c.put("field", field);
        c.put("op", op);
        c.put("value", value);
        return c;
    }

    private static Map<String, Object> all(Object... nodes) {
        return Map.of("all", List.of(nodes));
    }

    private static Map<String, Object> any(Object... nodes) {
        return Map.of("any", List.of(nodes));
    }

    /** Map.of rejects null values; visibility rules routinely evaluate against absent fields. */
    private static Map<String, Object> values(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    @Nested
    @DisplayName("operators")
    class Operators {

        @Test
        void eqMatchesAcrossTheStringNumberBoundary() {
            // NUMBER inputs hand back strings, so a numeric literal must still match.
            Map<String, Object> rule = all(cond("guests", "eq", 50));
            assertTrue(VisibilityRules.isVisible(rule, values("guests", "50")));
            assertTrue(VisibilityRules.isVisible(rule, values("guests", 50)));
            assertFalse(VisibilityRules.isVisible(rule, values("guests", "51")));
        }

        @Test
        void neqIsTheNegationOfEqIncludingForAbsentValues() {
            Map<String, Object> rule = all(cond("venue", "neq", "INDOOR"));
            assertTrue(VisibilityRules.isVisible(rule, values("venue", "OUTDOOR")));
            assertFalse(VisibilityRules.isVisible(rule, values("venue", "INDOOR")));
            assertTrue(VisibilityRules.isVisible(rule, values()));
        }

        @Test
        void inMeansOverlapWhenTheValueIsAMultiSelect() {
            Map<String, Object> in = all(cond("meal", "in", List.of("VEG", "VEGAN")));
            assertTrue(VisibilityRules.isVisible(in, values("meal", "VEGAN")));
            assertFalse(VisibilityRules.isVisible(in, values("meal", "MEAT")));
            assertTrue(VisibilityRules.isVisible(in, values("meal", List.of("MEAT", "VEG"))));
            assertFalse(VisibilityRules.isVisible(in, values("meal", List.of())));

            Map<String, Object> nin = all(cond("meal", "nin", List.of("VEG")));
            assertTrue(VisibilityRules.isVisible(nin, values("meal", "MEAT")));
            assertFalse(VisibilityRules.isVisible(nin, values("meal", "VEG")));
        }

        @Test
        void numericComparisonsFailClosedOnNonNumericOperands() {
            assertTrue(VisibilityRules.isVisible(all(cond("n", "gt", 10)), values("n", "11")));
            assertFalse(VisibilityRules.isVisible(all(cond("n", "gt", 10)), values("n", 10)));
            assertTrue(VisibilityRules.isVisible(all(cond("n", "gte", 10)), values("n", 10)));
            assertTrue(VisibilityRules.isVisible(all(cond("n", "lt", 10)), values("n", 9)));
            assertTrue(VisibilityRules.isVisible(all(cond("n", "lte", 10)), values("n", 10)));
            assertFalse(VisibilityRules.isVisible(all(cond("n", "gt", 10)), values("n", "abc")));
            assertFalse(VisibilityRules.isVisible(all(cond("n", "gt", 10)), values()));
        }

        @Test
        void blanksAreEmptyAndNotTruthyIncludingAnUnselectedMultiSelect() {
            Map<String, Object> truthy = all(cond("x", "truthy", null));
            Map<String, Object> empty = all(cond("x", "empty", null));

            // List.of() matters: an unselected MULTI_SELECT is an empty list.
            for (Object blank : Arrays.asList(null, "", List.of())) {
                assertFalse(VisibilityRules.isVisible(truthy, values("x", blank)), "truthy " + blank);
                assertTrue(VisibilityRules.isVisible(empty, values("x", blank)), "empty " + blank);
            }
            assertTrue(VisibilityRules.isVisible(truthy, values("x", true)));
            assertTrue(VisibilityRules.isVisible(truthy, values("x", List.of("a"))));
            assertFalse(VisibilityRules.isVisible(empty, values("x", "set")));
        }

        @Test
        void anExplicitFalseOrZeroIsSetButNotTruthy() {
            // truthy and empty are deliberately not strict inverses.
            for (Object falsy : List.of(false, 0)) {
                assertFalse(VisibilityRules.isVisible(all(cond("x", "truthy", null)), values("x", falsy)));
                assertFalse(VisibilityRules.isVisible(all(cond("x", "empty", null)), values("x", falsy)));
            }
        }
    }

    @Nested
    @DisplayName("composition")
    class Composition {

        @Test
        void allRequiresEveryBranchAndAnyRequiresOne() {
            Map<String, Object> v = values("a", "yes", "b", "no");
            assertTrue(VisibilityRules.isVisible(all(cond("a", "eq", "yes"), cond("b", "eq", "no")), v));
            assertFalse(VisibilityRules.isVisible(all(cond("a", "eq", "yes"), cond("b", "eq", "yes")), v));
            assertTrue(VisibilityRules.isVisible(any(cond("a", "eq", "no"), cond("b", "eq", "no")), v));
            assertFalse(VisibilityRules.isVisible(any(cond("a", "eq", "no"), cond("b", "eq", "maybe")), v));
        }

        @Test
        void notNegatesAndOneLevelOfNestingIsSupported() {
            Map<String, Object> rule = all(
                    cond("type", "eq", "WEDDING"),
                    any(cond("outdoor", "truthy", null), cond("guests", "gt", 100)));

            assertTrue(VisibilityRules.isVisible(rule, values("type", "WEDDING", "outdoor", true)));
            assertTrue(VisibilityRules.isVisible(rule, values("type", "WEDDING", "guests", 150)));
            assertFalse(VisibilityRules.isVisible(rule, values("type", "WEDDING", "guests", 10)));
            assertFalse(VisibilityRules.isVisible(rule, values("type", "BIRTHDAY", "outdoor", true)));

            Map<String, Object> negated = Map.of("not", all(cond("a", "truthy", null)));
            assertFalse(VisibilityRules.isVisible(negated, values("a", true)));
            assertTrue(VisibilityRules.isVisible(negated, values("a", false)));

            // not may also negate a bare condition, without wrapping it in a group.
            Map<String, Object> negatedLeaf = Map.of("not", cond("a", "truthy", null));
            assertFalse(VisibilityRules.isVisible(negatedLeaf, values("a", true)));
            assertTrue(VisibilityRules.isVisible(negatedLeaf, values("a", false)));
        }

        @Test
        void emptyAllIsTrueAndEmptyAnyIsFalse() {
            // An unfinished rule from the builder fails closed on `any`.
            assertTrue(VisibilityRules.isVisible(Map.of("all", List.of()), values()));
            assertFalse(VisibilityRules.isVisible(Map.of("any", List.of()), values()));
        }

        @Test
        void aNullOrEmptyRuleMeansAlwaysVisible() {
            assertTrue(VisibilityRules.isVisible(null, values()));
            assertTrue(VisibilityRules.isVisible(Map.of(), values()));
        }
    }

    @Nested
    @DisplayName("strict vs lenient parsing")
    class Parsing {

        @Test
        void anUnknownOperatorShowsTheFieldWhenRead() {
            // A rule written by a newer schema-registry must degrade to "no filtering" rather
            // than silently swallow the user's input.
            assertTrue(VisibilityRules.isVisible(all(cond("a", "matches", "x")), values("a", "anything")));
        }

        @Test
        void anUnknownOperatorIsRejectedWhenWritten() {
            var ex = assertThrows(IllegalArgumentException.class,
                    () -> VisibilityRules.parseStrict(all(cond("a", "matches", "x"))));
            assertTrue(ex.getMessage().contains("matches"), ex.getMessage());
        }

        @Test
        void strictParseRejectsMalformedRules() {
            assertThrows(IllegalArgumentException.class,
                    () -> VisibilityRules.parseStrict(Map.of("all", "not-an-array")));
            assertThrows(IllegalArgumentException.class,
                    () -> VisibilityRules.parseStrict(Map.of("nonsense", 1)));
            assertThrows(IllegalArgumentException.class,
                    () -> VisibilityRules.parseStrict(Map.of("all", List.of(Map.of("op", "eq")))));
            assertThrows(IllegalArgumentException.class,
                    () -> VisibilityRules.parseStrict(Map.of(
                            "all", List.of(cond("a", "eq", 1)),
                            "any", List.of(cond("b", "eq", 2)))));
        }

        @Test
        void strictParseRejectsNestingBeyondTwoLevelsOfBranching() {
            Map<String, Object> tooDeep = all(any(all(cond("a", "eq", 1))));
            assertThrows(IllegalArgumentException.class, () -> VisibilityRules.parseStrict(tooDeep));
        }

        @Test
        void notAndConditionLeavesDoNotCountTowardTheBranchCap() {
            // Conditions are leaves, so a deeply-populated but shallow-grouped rule is fine.
            assertDoesNotThrow(() -> VisibilityRules.parseStrict(
                    all(cond("a", "eq", 1), any(cond("b", "eq", 2), Map.of("not", cond("c", "truthy", null))))));
        }

        @Test
        void lenientParseOfAMalformedRuleShowsTheField() {
            assertTrue(VisibilityRules.isVisible(Map.of("nonsense", 1), values()));
        }
    }

    @Test
    void dependenciesGathersEveryReferencedKeyOnce() {
        Map<String, Object> rule = all(
                cond("type", "eq", "WEDDING"),
                any(cond("outdoor", "truthy", null), cond("type", "eq", "X")),
                Map.of("not", cond("cancelled", "truthy", null)));

        assertEquals(Set.of("type", "outdoor", "cancelled"), VisibilityRules.dependencies(rule));
    }
}
