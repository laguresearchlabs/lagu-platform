package com.lagu.platform.security;

import lombok.Builder;
import lombok.Data;

import java.util.Arrays;
import java.util.Set;
import java.util.UUID;

@Data
@Builder
public class PlatformSecurityContext {

    private UUID        userId;
    private UUID        tenantId;
    private Set<String> roles;
    private String      userEmail;

    public boolean hasRole(String role) {
        return roles != null && roles.contains(role);
    }

    public boolean hasAnyRole(String... checkRoles) {
        if (roles == null) return false;
        return Arrays.stream(checkRoles).anyMatch(roles::contains);
    }

    public boolean isPlatformAdmin() {
        return hasRole("PLATFORM_ADMIN");
    }

    public boolean isConfigAdmin() {
        return hasRole("CONFIG_ADMIN") || isPlatformAdmin();
    }

    public boolean isOrgMember() {
        return userId != null && tenantId != null;
    }

    /**
     * True when the caller is another lagu-platform service (authenticated via
     * X-Internal-Service + gateway secret and granted a single {@code SVC_*} role by
     * {@link GatewayHeaderFilter}). Service callers are not users: they get the narrow
     * grant defined in {@link DefaultPermissionEvaluator}, never role-based user permissions.
     */
    public boolean isInternalService() {
        return roles != null && roles.stream()
                .anyMatch(r -> r.startsWith(GatewayHeaderFilter.SERVICE_ROLE_PREFIX));
    }

    /**
     * Whether this caller may read a resource owned by {@code resourceTenantId}. A null
     * {@code resourceTenantId} marks a platform-level/shared definition, readable by any org.
     */
    public boolean canReadTenantScoped(UUID resourceTenantId) {
        return resourceTenantId == null || resourceTenantId.equals(tenantId);
    }

    /**
     * Whether this caller may mutate a resource owned by {@code resourceTenantId}. Platform-level
     * definitions (null {@code resourceTenantId}) affect every org and require a config/platform admin.
     */
    public boolean canWriteTenantScoped(UUID resourceTenantId) {
        return resourceTenantId == null ? isConfigAdmin() : resourceTenantId.equals(tenantId);
    }
}
