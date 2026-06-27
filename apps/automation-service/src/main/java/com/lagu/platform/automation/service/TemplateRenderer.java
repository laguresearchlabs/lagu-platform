package com.lagu.platform.automation.service;

import com.lagu.platform.automation.model.AutomationEventContext;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class TemplateRenderer {

    private static final Pattern TOKEN = Pattern.compile("\\{\\{([^}]+)}}");

    public String render(String template, AutomationEventContext ctx) {
        if (template == null) return null;

        StringBuffer sb  = new StringBuffer();
        Matcher      m   = TOKEN.matcher(template);

        while (m.find()) {
            String key   = m.group(1).trim();
            String value = resolve(key, ctx);
            m.appendReplacement(sb, Matcher.quoteReplacement(value));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    public Map<String, Object> renderMap(Map<String, Object> template, AutomationEventContext ctx) {
        if (template == null) return null;
        java.util.HashMap<String, Object> result = new java.util.HashMap<>();
        template.forEach((k, v) -> result.put(k, v instanceof String s ? render(s, ctx) : v));
        return result;
    }

    private String resolve(String key, AutomationEventContext ctx) {
        if (key.startsWith("data.") && ctx.getData() != null) {
            Object val = ctx.getData().get(key.substring(5));
            return val != null ? String.valueOf(val) : "";
        }
        return switch (key) {
            case "recordId"       -> ctx.getRecordId()   != null ? ctx.getRecordId().toString()   : "";
            case "orgId"          -> ctx.getOrgId()      != null ? ctx.getOrgId().toString()      : "";
            case "objectType"     -> ctx.getObjectType() != null ? ctx.getObjectType()             : "";
            case "currentStatus"  -> ctx.getCurrentStatus()  != null ? ctx.getCurrentStatus()      : "";
            case "previousStatus" -> ctx.getPreviousStatus() != null ? ctx.getPreviousStatus()     : "";
            case "now"            -> Instant.now().toString();
            case "changedBy"      -> ctx.getChangedBy()  != null ? ctx.getChangedBy().toString()  : "";
            default               -> "{{" + key + "}}";
        };
    }
}
