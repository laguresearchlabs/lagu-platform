package com.lagu.platform.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * Fallback evaluator based purely on platform-level roles from the gateway header.
 * Services that need DB-backed custom role checks should provide their own
 * {@link PermissionEvaluator} bean, which will suppress this one via
 * {@code @ConditionalOnMissingBean}.
 */
@Component
@ConditionalOnMissingBean(value = PermissionEvaluator.class, ignored = DefaultPermissionEvaluator.class)
public class DefaultPermissionEvaluator implements PermissionEvaluator {

    @Override
    public boolean canAccess(PlatformSecurityContext ctx, String resource, String action) {
        if (ctx == null) return false;

        // PLATFORM_ADMIN bypasses all checks
        if (ctx.isPlatformAdmin()) return true;

        // CONFIG_ADMIN can manage all configuration resources
        if (ctx.isConfigAdmin()) {
            return isConfigResource(resource);
        }

        // Org members can read any resource within their org
        if (ctx.isOrgMember() && "READ".equals(action)) return true;

        // ORG_MANAGER and above can create/update/delete records
        if (ctx.hasAnyRole("ORG_MANAGER", "ORG_OWNER") && isRecordAction(action)) return true;

        return false;
    }

    private boolean isConfigResource(String resource) {
        return switch (resource) {
            case "ATTRIBUTE", "ENTITY", "OBJECT_TYPE", "RELATIONSHIP", "ROLE", "PERMISSION", "GROUP", "WORKFLOW", "*"
                    -> true;
            default -> false;
        };
    }

    private boolean isRecordAction(String action) {
        return switch (action) {
            case "CREATE", "READ", "UPDATE", "DELETE", "TRANSITION" -> true;
            default -> false;
        };
    }
}
