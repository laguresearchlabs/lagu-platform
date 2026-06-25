package com.lagu.platform.security;

/**
 * Strategy for checking whether the current user may perform an action on a resource.
 * Services provide their own implementation; the default covers only platform-level roles.
 */
public interface PermissionEvaluator {

    /**
     * @param ctx      security context extracted from gateway headers
     * @param resource resource type name (e.g. "RECORD", "ATTRIBUTE", "*")
     * @param action   action name (CREATE | READ | UPDATE | DELETE | TRANSITION | APPROVE)
     * @return true if the user is permitted
     */
    boolean canAccess(PlatformSecurityContext ctx, String resource, String action);
}
