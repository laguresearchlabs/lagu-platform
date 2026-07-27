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

        // Internal service-to-service callers (SVC_* role from X-Internal-Service): allowed to
        // read anything and to drive record lifecycles, but never DELETE and never config writes.
        // Tenancy is still enforced downstream via the X-Tenant-Id they forward per request.
        if (ctx.isInternalService()) {
            if ("READ".equals(action)) return true;
            if ("RECORD".equals(resource)) {
                return switch (action) {
                    case "CREATE", "UPDATE", "TRANSITION" -> true;
                    default -> false;
                };
            }
            // automation-service revokes/expires verifications (EXPIRE_VERIFICATION action)
            return "RECORD_VERIFICATION".equals(resource) && "MANAGE".equals(action);
        }

        // CONFIG_ADMIN can manage all configuration resources
        if (ctx.isConfigAdmin()) {
            return isConfigResource(resource);
        }

        // Notifications: any authenticated user can read/manage their own
        if ("NOTIFICATION".equals(resource)) return true;

        // Documents: any authenticated user can upload/read their own;
        // REVIEW action (verify/reject) requires ORG_MANAGER or ORG_OWNER
        if ("DOCUMENT".equals(resource)) {
            if ("REVIEW".equals(action)) return ctx.hasAnyRole("ORG_MANAGER", "ORG_OWNER");
            return true;
        }

        // Org members can read any resource within their org
        if (ctx.isOrgMember() && "READ".equals(action)) return true;

        // ORG_MANAGER and above can create/update/delete/transition records — and only records:
        // isRecordAction() checks the action shape, not the resource, so without the explicit
        // "RECORD".equals(resource) check here this branch would grant ORG_MANAGER the same
        // CREATE/UPDATE/DELETE on ATTRIBUTE/OBJECT_TYPE/WORKFLOW/TRIGGER/"*" (or any other
        // resource name) as it does on RECORD — silently defeating every config-admin gate in
        // schema-registry/automation-service/workflow-service and the resource="*" checks like
        // AdminReindexController's.
        if ("RECORD".equals(resource) && ctx.hasAnyRole("ORG_MANAGER", "ORG_OWNER")
                && isRecordAction(action)) {
            return true;
        }

        return false;
    }

    private boolean isConfigResource(String resource) {
        return switch (resource) {
            case "ATTRIBUTE", "ENTITY", "OBJECT_TYPE", "RELATIONSHIP",
                 "ROLE", "PERMISSION", "GROUP", "WORKFLOW", "TRIGGER",
                 "TIER_CONFIG", "TIER_RULE", "DOCUMENT_REQUIREMENT", "SEARCH_DEFINITION", "*"
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
