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
}
