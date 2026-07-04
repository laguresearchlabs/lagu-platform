package com.lagu.platform.workflow.service;

import com.lagu.platform.workflow.domain.WorkflowTransition;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;

/**
 * Evaluates the {@code conditions} JSONB field on a {@link WorkflowTransition} against
 * the runtime context supplied by the triggering event.
 *
 * Condition format (list of objects in the JSONB array, or a single map):
 * <pre>
 *   [ { "field": "verificationTier", "op": "in",     "value": ["BASIC","PREMIUM"] },
 *     { "field": "activeBookings",   "op": "lt",     "value": 10                  },
 *     { "field": "country",          "op": "eq",     "value": "IN"                } ]
 * </pre>
 * Supported ops: eq, neq, in, not_in, exists, not_exists, gt, lt, gte, lte.
 * All conditions must pass (AND semantics).
 */
@Component
@Slf4j
public class TransitionGuard {

    public boolean evaluate(WorkflowTransition transition, Map<String, Object> context) {
        if (transition.getConditions() == null || transition.getConditions().isEmpty()) {
            return true;
        }

        Object raw = transition.getConditions().get("rules");
        Iterable<Object> rules;
        if (raw instanceof Iterable<?> iter) {
            //noinspection unchecked
            rules = (Iterable<Object>) iter;
        } else {
            // Single-condition map stored directly
            rules = java.util.List.of(transition.getConditions());
        }

        for (Object ruleObj : rules) {
            if (!(ruleObj instanceof Map<?, ?> rawRule)) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> rule = (Map<String, Object>) rawRule;

            String field = (String) rule.get("field");
            String op    = (String) rule.get("op");
            Object expected = rule.get("value");

            Object actual = context != null ? context.get(field) : null;

            if (!passes(op, actual, expected)) {
                log.debug("TransitionGuard: condition failed — field={} op={} expected={} actual={}",
                        field, op, expected, actual);
                return false;
            }
        }
        return true;
    }

    /**
     * Fail closed: these conditions gate workflow transitions, so a rule the engine cannot
     * evaluate (unknown/missing op, non-numeric value in a numeric comparison) must block the
     * transition, not wave it through.
     */
    private boolean passes(String op, Object actual, Object expected) {
        if (op == null) {
            log.warn("TransitionGuard: condition has no 'op' — failing closed");
            return false;
        }
        return switch (op) {
            case "eq"         -> Objects.equals(str(actual), str(expected));
            case "neq"        -> !Objects.equals(str(actual), str(expected));
            case "exists"     -> actual != null;
            case "not_exists" -> actual == null;
            case "in"         -> expected instanceof Collection<?> col
                                    && col.stream().anyMatch(v -> Objects.equals(str(actual), str(v)));
            case "not_in"     -> !(expected instanceof Collection<?> col)
                                    || col.stream().noneMatch(v -> Objects.equals(str(actual), str(v)));
            case "gt"  -> numeric(op, actual, expected, cmp -> cmp > 0);
            case "lt"  -> numeric(op, actual, expected, cmp -> cmp < 0);
            case "gte" -> numeric(op, actual, expected, cmp -> cmp >= 0);
            case "lte" -> numeric(op, actual, expected, cmp -> cmp <= 0);
            default -> {
                log.warn("TransitionGuard: unknown op '{}' — failing closed", op);
                yield false;
            }
        };
    }

    private String str(Object o) {
        return o == null ? null : o.toString();
    }

    private boolean numeric(String op, Object actual, Object expected,
                            java.util.function.IntPredicate outcome) {
        try {
            double a = Double.parseDouble(str(actual));
            double e = Double.parseDouble(str(expected));
            return outcome.test(Double.compare(a, e));
        } catch (NullPointerException | NumberFormatException ex) {
            log.warn("TransitionGuard: op '{}' on non-numeric values (actual={}, expected={}) " +
                    "— failing closed", op, actual, expected);
            return false;
        }
    }
}
