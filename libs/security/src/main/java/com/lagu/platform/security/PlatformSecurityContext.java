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
    private UUID        orgId;
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
        return userId != null && orgId != null;
    }

    /**
     * Whether this caller may read a resource owned by {@code resourceOrgId}. A null
     * {@code resourceOrgId} marks a platform-level/shared definition, readable by any org.
     */
    public boolean canReadOrgScoped(UUID resourceOrgId) {
        return resourceOrgId == null || resourceOrgId.equals(orgId);
    }

    /**
     * Whether this caller may mutate a resource owned by {@code resourceOrgId}. Platform-level
     * definitions (null {@code resourceOrgId}) affect every org and require a config/platform admin.
     */
    public boolean canWriteOrgScoped(UUID resourceOrgId) {
        return resourceOrgId == null ? isConfigAdmin() : resourceOrgId.equals(orgId);
    }
}
