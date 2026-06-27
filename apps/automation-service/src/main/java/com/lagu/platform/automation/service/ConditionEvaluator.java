package com.lagu.platform.automation.service;

import com.lagu.platform.automation.model.AutomationEventContext;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;

@Component
public class ConditionEvaluator {

    public boolean matches(List<Map<String, Object>> conditions, AutomationEventContext ctx) {
        if (conditions == null || conditions.isEmpty()) return true;
        return conditions.stream().allMatch(rule -> evaluate(rule, ctx));
    }

    private boolean evaluate(Map<String, Object> rule, AutomationEventContext ctx) {
        String field    = (String) rule.get("field");
        String operator = (String) rule.get("operator");
        Object ruleVal  = rule.get("value");

        Object fieldValue = resolveField(field, ctx);

        return switch (operator) {
            case "EQ"          -> Objects.equals(String.valueOf(fieldValue), String.valueOf(ruleVal));
            case "NEQ"         -> !Objects.equals(String.valueOf(fieldValue), String.valueOf(ruleVal));
            case "CONTAINS"    -> fieldValue instanceof String s && s.contains(String.valueOf(ruleVal));
            case "STARTS_WITH" -> fieldValue instanceof String s && s.startsWith(String.valueOf(ruleVal));
            case "IN"          -> {
                @SuppressWarnings("unchecked")
                List<String> values = (List<String>) rule.get("values");
                yield values != null && values.contains(String.valueOf(fieldValue));
            }
            case "GT"          -> compareNumbers(fieldValue, ruleVal) > 0;
            case "LT"          -> compareNumbers(fieldValue, ruleVal) < 0;
            case "GTE"         -> compareNumbers(fieldValue, ruleVal) >= 0;
            case "LTE"         -> compareNumbers(fieldValue, ruleVal) <= 0;
            case "IS_NULL"     -> fieldValue == null;
            case "IS_NOT_NULL" -> fieldValue != null;
            default            -> false;
        };
    }

    private Object resolveField(String fieldPath, AutomationEventContext ctx) {
        if (fieldPath == null) return null;
        if (fieldPath.startsWith("data.") && ctx.getData() != null) {
            return ctx.getData().get(fieldPath.substring(5));
        }
        return switch (fieldPath) {
            case "currentStatus"  -> ctx.getCurrentStatus();
            case "previousStatus" -> ctx.getPreviousStatus();
            case "objectType"     -> ctx.getObjectType();
            case "eventType"      -> ctx.getEventType();
            case "orgId"          -> ctx.getOrgId() != null ? ctx.getOrgId().toString() : null;
            default               -> null;
        };
    }

    private int compareNumbers(Object fieldValue, Object ruleValue) {
        try {
            double a = Double.parseDouble(String.valueOf(fieldValue));
            double b = Double.parseDouble(String.valueOf(ruleValue));
            return Double.compare(a, b);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
