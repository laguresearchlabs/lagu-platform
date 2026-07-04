package com.lagu.platform.workflow.service;

import com.lagu.platform.workflow.domain.WorkflowTransition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guard conditions gate workflow transitions, so anything the engine cannot evaluate must
 * fail closed — an unknown op or a garbage numeric must block, never allow.
 */
class TransitionGuardTest {

    private final TransitionGuard guard = new TransitionGuard();

    private static WorkflowTransition withRules(Map<String, Object>... rules) {
        WorkflowTransition tx = new WorkflowTransition();
        tx.setConditions(Map.of("rules", List.of(rules)));
        return tx;
    }

    // ── happy-path semantics ──────────────────────────────────────────────────

    @Test
    void noConditionsAlwaysPass() {
        WorkflowTransition tx = new WorkflowTransition();
        tx.setConditions(null);
        assertThat(guard.evaluate(tx, Map.of())).isTrue();
    }

    @Test
    void eqInAndNumericOpsEvaluateAgainstContext() {
        WorkflowTransition tx = withRules(
                Map.of("field", "country", "op", "eq", "value", "IN"),
                Map.of("field", "verificationTier", "op", "in", "value", List.of("BASIC", "PREMIUM")),
                Map.of("field", "activeBookings", "op", "lt", "value", 10));

        assertThat(guard.evaluate(tx, Map.of(
                "country", "IN", "verificationTier", "BASIC", "activeBookings", 3))).isTrue();

        assertThat(guard.evaluate(tx, Map.of(
                "country", "IN", "verificationTier", "NONE", "activeBookings", 3))).isFalse();
    }

    @Test
    void singleConditionMapWithoutRulesArrayIsSupported() {
        WorkflowTransition tx = new WorkflowTransition();
        tx.setConditions(Map.of("field", "country", "op", "eq", "value", "IN"));
        assertThat(guard.evaluate(tx, Map.of("country", "IN"))).isTrue();
        assertThat(guard.evaluate(tx, Map.of("country", "US"))).isFalse();
    }

    @Test
    void missingContextFieldFailsEq() {
        WorkflowTransition tx = withRules(Map.of("field", "country", "op", "eq", "value", "IN"));
        assertThat(guard.evaluate(tx, Map.of())).isFalse();
        assertThat(guard.evaluate(tx, null)).isFalse();
    }

    // ── fail-closed behavior ──────────────────────────────────────────────────

    @Test
    void unknownOpFailsClosed() {
        WorkflowTransition tx = withRules(
                Map.of("field", "country", "op", "matches_regex", "value", ".*"));
        assertThat(guard.evaluate(tx, Map.of("country", "IN"))).isFalse();
    }

    @Test
    void missingOpFailsClosed() {
        WorkflowTransition tx = withRules(Map.of("field", "country", "value", "IN"));
        assertThat(guard.evaluate(tx, Map.of("country", "IN"))).isFalse();
    }

    @Test
    void nonNumericValuesFailNumericOpsClosed() {
        // Previously: parse failure compared as equal, so gte/lte passed on garbage.
        WorkflowTransition gte = withRules(
                Map.of("field", "activeBookings", "op", "gte", "value", 1));
        assertThat(guard.evaluate(gte, Map.of("activeBookings", "not-a-number"))).isFalse();

        WorkflowTransition lte = withRules(
                Map.of("field", "activeBookings", "op", "lte", "value", 10));
        assertThat(guard.evaluate(lte, Map.of("activeBookings", "not-a-number"))).isFalse();
    }

    @Test
    void absentNumericFieldFailsClosed() {
        WorkflowTransition tx = withRules(
                Map.of("field", "activeBookings", "op", "lt", "value", 10));
        assertThat(guard.evaluate(tx, Map.of())).isFalse();
    }
}
