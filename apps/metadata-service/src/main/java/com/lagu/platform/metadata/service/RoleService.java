package com.lagu.platform.metadata.service;

import com.lagu.platform.common.exception.ResourceNotFoundException;
import com.lagu.platform.common.exception.ValidationException;
import com.lagu.platform.metadata.domain.role.*;
import com.lagu.platform.metadata.dto.*;
import com.lagu.platform.security.GatewayHeaderFilter;
import com.lagu.platform.security.PlatformSecurityContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RoleService {

    private final RoleDefinitionRepository      roleRepo;
    private final PermissionDefinitionRepository permRepo;
    private final UserRoleRepository             userRoleRepo;

    public List<RoleResponse> listRoles() {
        return roleRepo.findAllForOrg(orgId()).stream()
                .map(r -> toRoleResponse(r, false))
                .toList();
    }

    public RoleResponse getRole(UUID id) {
        RoleDefinition role = findRole(id);
        return toRoleResponse(role, true);
    }

    @Transactional
    public RoleResponse createCustomRole(RoleRequest req) {
        UUID orgId = orgId();
        if (roleRepo.existsByNameAndOrgId(req.getName(), orgId)) {
            throw new ValidationException("Role name already exists");
        }
        RoleDefinition role = new RoleDefinition();
        role.setOrgId(orgId);
        role.setName(req.getName().toUpperCase());
        role.setLabel(req.getLabel());
        role.setDescription(req.getDescription());
        role.setRoleLevel("CUSTOM");
        return toRoleResponse(roleRepo.save(role), false);
    }

    public List<PermissionResponse> listPermissions(UUID roleId) {
        return permRepo.findByRoleId(roleId).stream()
                .map(this::toPermResponse)
                .toList();
    }

    @Transactional
    public PermissionResponse grantPermission(UUID roleId, PermissionRequest req) {
        RoleDefinition role = findRole(roleId);
        permRepo.findByResourceTypeAndActionAndRoleId(
                req.getResourceType().toUpperCase(), req.getAction().toUpperCase(), roleId)
                .ifPresent(p -> { throw new ValidationException("Permission already granted"); });

        PermissionDefinition perm = new PermissionDefinition();
        perm.setRole(role);
        perm.setResourceType(req.getResourceType().toUpperCase());
        perm.setAction(req.getAction().toUpperCase());
        perm.setConditions(req.getConditions());
        return toPermResponse(permRepo.save(perm));
    }

    @Transactional
    public void revokePermission(UUID roleId, UUID permId) {
        PermissionDefinition perm = permRepo.findById(permId)
                .filter(p -> p.getRole().getId().equals(roleId))
                .orElseThrow(() -> new ResourceNotFoundException("Permission", permId.toString()));
        permRepo.delete(perm);
    }

    @Transactional
    public void assignRoleToUser(UUID roleId, UUID userId) {
        UUID orgId = orgId();
        RoleDefinition role = findRole(roleId);
        if (userRoleRepo.existsByOrgIdAndUserIdAndRoleId(orgId, userId, roleId)) {
            throw new ValidationException("User already has this role");
        }
        UserRole ur = new UserRole();
        ur.setOrgId(orgId);
        ur.setUserId(userId);
        ur.setRole(role);
        ur.setGrantedBy(ctx() != null ? ctx().getUserId() : null);
        userRoleRepo.save(ur);
    }

    @Transactional
    public void revokeRoleFromUser(UUID roleId, UUID userId) {
        UUID orgId = orgId();
        UserRole ur = userRoleRepo.findByOrgIdAndUserIdAndRoleId(orgId, userId, roleId)
                .orElseThrow(() -> new ResourceNotFoundException("UserRole", roleId.toString()));
        userRoleRepo.delete(ur);
    }

    private RoleDefinition findRole(UUID id) {
        return roleRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Role", id.toString()));
    }

    private UUID orgId() {
        PlatformSecurityContext ctx = GatewayHeaderFilter.current();
        return ctx != null ? ctx.getOrgId() : null;
    }

    private PlatformSecurityContext ctx() {
        return GatewayHeaderFilter.current();
    }

    private RoleResponse toRoleResponse(RoleDefinition r, boolean includePerms) {
        List<PermissionResponse> perms = includePerms
                ? permRepo.findByRoleId(r.getId()).stream().map(this::toPermResponse).toList()
                : List.of();
        return RoleResponse.builder()
                .id(r.getId()).orgId(r.getOrgId()).name(r.getName()).label(r.getLabel())
                .description(r.getDescription()).roleLevel(r.getRoleLevel())
                .active(r.isActive()).permissions(perms).createdAt(r.getCreatedAt())
                .build();
    }

    private PermissionResponse toPermResponse(PermissionDefinition p) {
        return PermissionResponse.builder()
                .id(p.getId()).resourceType(p.getResourceType()).action(p.getAction())
                .conditions(p.getConditions()).createdAt(p.getCreatedAt())
                .build();
    }
}
