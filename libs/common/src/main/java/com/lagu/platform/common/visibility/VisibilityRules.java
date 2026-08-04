package com.lagu.platform.common.visibility;

import com.lagu.platform.common.visibility.VisibilityNode.All;
import com.lagu.platform.common.visibility.VisibilityNode.Any;
import com.lagu.platform.common.visibility.VisibilityNode.Condition;
import com.lagu.platform.common.visibility.VisibilityNode.Not;
import com.lagu.platform.common.visibility.VisibilityNode.Op;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Parses, evaluates and inspects conditional-visibility rules.
 *
 * <p>This lives in {@code libs:common} on purpose. record-service has to evaluate these rules to
 * decide whether a {@code required} field is actually in play, and schema-registry has to validate
 * them at publish time; two implementations of the same semantics would drift, and the failure
 * mode of that drift is a record the UI says is valid and the server rejects.
 *
 * <p>Semantics are mirrored by {@code events-ui/src/lib/schema-form/visibility.ts}. The unit tests
 * on both sides are deliberately the same cases — treat them as one conformance suite.
 *
 * <p>Strict on write, lenient on read: {@link #parseStrict} rejects anything malformed so a bad
 * rule can never be stored, while {@link #parse} degrades unknown constructs to "always visible"
 * so an older service reading a newer rule shows the field rather than losing the user's input.
 */
public final class VisibilityRules {

    /** Levels of all/any branching allowed. Conditions and `not` do not count. */
    private static final int MAX_BRANCH_DEPTH = 2;

    /** An empty {@code all} is vacuously true, so this is the lenient parser's "show it" fallback. */
    private static final VisibilityNode ALWAYS_VISIBLE = new All(List.of());

    private VisibilityRules() {}

    // ── Evaluation ────────────────────────────────────────────────────────────

    /** Convenience for the common case: a null or empty rule means "always visible". */
    public static boolean isVisible(Map<String, Object> rule, Map<String, Object> values) {
        if (rule == null || rule.isEmpty()) return true;
        return evaluate(parse(rule), values);
    }

    public static boolean evaluate(VisibilityNode node, Map<String, Object> values) {
        return switch (node) {
            case All all -> all.nodes().stream().allMatch(n -> evaluate(n, values));
            case Any any -> any.nodes().stream().anyMatch(n -> evaluate(n, values));
            case Not not -> !evaluate(not.node(), values);
            case Condition c -> evaluateCondition(c, values);
        };
    }

    private static boolean evaluateCondition(Condition c, Map<String, Object> values) {
        Object actual = values == null ? null : values.get(c.field());

        return switch (c.op()) {
            // Not simply "actual != null": an unselected MULTI_SELECT arrives as an empty list,
            // and an explicit false or 0 is set but not truthy.
            case TRUTHY -> !isEmpty(actual) && !isFalsy(actual);
            case EMPTY -> isEmpty(actual);
            case EQ -> looseEquals(actual, c.value());
            case NEQ -> !looseEquals(actual, c.value());
            case IN -> containsLoosely(c.value(), actual);
            case NIN -> !containsLoosely(c.value(), actual);
            case GT, GTE, LT, LTE -> compareNumeric(c, actual);
            // Forward compatibility — see VisibilityNode.Op.UNKNOWN.
            case UNKNOWN -> true;
        };
    }

    private static boolean compareNumeric(Condition c, Object actual) {
        Double a = asNumber(actual);
        Double b = asNumber(c.value());
        if (a == null || b == null) return false; // fail closed on non-numeric operands
        int cmp = a.compareTo(b);
        return switch (c.op()) {
            case GT -> cmp > 0;
            case GTE -> cmp >= 0;
            case LT -> cmp < 0;
            default -> cmp <= 0;
        };
    }

    /** Membership, or overlap when the field holds a MULTI_SELECT's list of selections. */
    private static boolean containsLoosely(Object expected, Object actual) {
        Collection<?> options = expected instanceof Collection<?> col ? col : List.of(nullToEmpty(expected));
        if (actual instanceof Collection<?> selected) {
            return selected.stream().anyMatch(v -> options.stream().anyMatch(o -> looseEquals(v, o)));
        }
        return options.stream().anyMatch(o -> looseEquals(actual, o));
    }

    /**
     * Compares across the string/number boundary. Form inputs hand back strings even for NUMBER
     * fields, so a rule authored as {@code {op: "eq", value: 50}} still has to match {@code "50"}.
     */
    private static boolean looseEquals(Object a, Object b) {
        if (a == null && b == null) return true;
        if (isEmpty(a) || isEmpty(b)) return false;
        Double na = asNumber(a);
        Double nb = asNumber(b);
        if (na != null && nb != null) return na.doubleValue() == nb.doubleValue();
        return String.valueOf(a).equals(String.valueOf(b));
    }

    private static boolean isEmpty(Object v) {
        if (v == null) return true;
        if (v instanceof CharSequence s) return s.isEmpty();
        if (v instanceof Collection<?> c) return c.isEmpty();
        if (v instanceof Map<?, ?> m) return m.isEmpty();
        return false;
    }

    private static boolean isFalsy(Object v) {
        if (v instanceof Boolean b) return !b;
        if (v instanceof Number n) return n.doubleValue() == 0d;
        return false;
    }

    private static Double asNumber(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof CharSequence s) {
            try {
                return Double.valueOf(s.toString().trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static Object nullToEmpty(Object v) {
        return v == null ? "" : v;
    }

    // ── Parsing ───────────────────────────────────────────────────────────────

    /** Lenient parse for evaluation: unknown operators become {@link Op#UNKNOWN}. */
    public static VisibilityNode parse(Map<String, Object> rule) {
        return parseNode(rule, false, 0);
    }

    /**
     * Strict parse for the write path. Throws {@link IllegalArgumentException} describing the
     * first problem so a publish or a schema edit can be rejected with a usable message.
     */
    public static VisibilityNode parseStrict(Map<String, Object> rule) {
        return parseNode(rule, true, 0);
    }

    @SuppressWarnings("unchecked")
    private static VisibilityNode parseNode(Object raw, boolean strict, int depth) {
        if (!(raw instanceof Map<?, ?> map)) {
            return fail(strict, "visibility rule must be an object, got " + describe(raw), ALWAYS_VISIBLE);
        }

        Object all = map.get("all");
        Object any = map.get("any");
        Object not = map.get("not");

        long branches = List.of(all != null, any != null, not != null).stream().filter(b -> b).count();
        if (branches > 1) {
            return fail(strict, "visibility rule must have exactly one of all/any/not", ALWAYS_VISIBLE);
        }
        // Only branching counts toward the cap. A condition is a leaf, and `not` is a modifier
        // rather than a branch — the cap exists to keep rules readable and renderable in the
        // Admin builder, and it is all/any nesting that makes them hard to follow.
        if ((all != null || any != null) && depth >= MAX_BRANCH_DEPTH) {
            return fail(strict, "visibility rule nests deeper than " + MAX_BRANCH_DEPTH
                    + " levels of all/any", ALWAYS_VISIBLE);
        }

        if (all != null) return new All(parseChildren(all, strict, depth));
        if (any != null) return new Any(parseChildren(any, strict, depth));
        if (not != null) return new Not(parseNode(not, strict, depth));
        if (map.containsKey("field")) return parseCondition((Map<String, Object>) map, strict);

        // Neither a group nor a condition: unrecognised shape.
        return fail(strict, "visibility rule has no all/any/not/field key", ALWAYS_VISIBLE);
    }

    private static List<VisibilityNode> parseChildren(Object raw, boolean strict, int depth) {
        if (!(raw instanceof Collection<?> items)) {
            fail(strict, "all/any must be an array, got " + describe(raw), null);
            return List.of();
        }
        List<VisibilityNode> nodes = new ArrayList<>(items.size());
        for (Object item : items) nodes.add(parseNode(item, strict, depth + 1));
        return nodes;
    }

    private static VisibilityNode parseCondition(Map<String, Object> map, boolean strict) {
        Object field = map.get("field");
        if (!(field instanceof String name) || name.isBlank()) {
            return fail(strict, "condition is missing a field name", ALWAYS_VISIBLE);
        }
        Object rawOp = map.get("op");
        Op op = toOp(rawOp);
        if (op == Op.UNKNOWN && strict) {
            throw new IllegalArgumentException("unknown visibility operator '" + rawOp + "' on field '" + name + "'");
        }
        return new Condition(name, op, map.get("value"));
    }

    private static Op toOp(Object raw) {
        if (!(raw instanceof String s)) return Op.UNKNOWN;
        try {
            // "UNKNOWN" itself parses here, which is fine: strict mode rejects it either way.
            return Op.valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return Op.UNKNOWN;
        }
    }

    private static VisibilityNode fail(boolean strict, String message, VisibilityNode lenientFallback) {
        if (strict) throw new IllegalArgumentException(message);
        return lenientFallback;
    }

    private static String describe(Object raw) {
        return raw == null ? "null" : raw.getClass().getSimpleName();
    }

    // ── Inspection ────────────────────────────────────────────────────────────

    /** Every field key the rule reads, for dependency and cycle analysis at publish time. */
    public static Set<String> dependencies(VisibilityNode node) {
        Set<String> keys = new LinkedHashSet<>();
        collect(node, keys);
        return keys;
    }

    public static Set<String> dependencies(Map<String, Object> rule) {
        return rule == null || rule.isEmpty() ? Set.of() : dependencies(parse(rule));
    }

    private static void collect(VisibilityNode node, Set<String> keys) {
        switch (node) {
            case Condition c -> keys.add(c.field());
            case Not n -> collect(n.node(), keys);
            case All a -> a.nodes().forEach(n -> collect(n, keys));
            case Any a -> a.nodes().forEach(n -> collect(n, keys));
        }
    }
}
