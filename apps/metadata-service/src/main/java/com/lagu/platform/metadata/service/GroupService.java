package com.lagu.platform.metadata.service;

import com.lagu.platform.common.exception.ResourceNotFoundException;
import com.lagu.platform.common.exception.ValidationException;
import com.lagu.platform.metadata.domain.team.*;
import com.lagu.platform.metadata.dto.*;
import com.lagu.platform.metadata.event.TeamEventPublisher;
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
public class GroupService {

    private final GroupDefinitionRepository groupRepo;
    private final GroupMemberRepository memberRepo;
    private final TeamEventPublisher teamEventPublisher;

    public List<GroupResponse> listGroups() {
        UUID orgId = orgId();
        return groupRepo.findByOrgIdAndActiveTrue(orgId).stream()
                .map(g -> toResponse(g, false))
                .toList();
    }

    public GroupResponse getGroup(UUID id) {
        GroupDefinition group = findGroupForOrg(id);
        return toResponse(group, true);
    }

    @Transactional
    public GroupResponse createGroup(GroupRequest req) {
        UUID orgId = orgId();
        if (groupRepo.existsByNameAndOrgId(req.getName(), orgId)) {
            throw new ValidationException("Group name already exists in this org");
        }
        GroupDefinition group = new GroupDefinition();
        group.setOrgId(orgId);
        group.setName(req.getName());
        group.setDescription(req.getDescription());
        return toResponse(groupRepo.save(group), false);
    }

    @Transactional
    public GroupResponse updateGroup(UUID id, GroupRequest req) {
        GroupDefinition group = findGroupForOrg(id);
        group.setName(req.getName());
        group.setDescription(req.getDescription());
        return toResponse(groupRepo.save(group), true);
    }

    @Transactional
    public void deactivateGroup(UUID id) {
        GroupDefinition group = findGroupForOrg(id);
        group.setActive(false);
        groupRepo.save(group);
    }

    @Transactional
    public MemberResponse addMember(UUID groupId, AddMemberRequest req) {
        GroupDefinition group = findGroupForOrg(groupId);
        if (memberRepo.existsByGroupIdAndUserId(groupId, req.getUserId())) {
            throw new ValidationException("User is already a member of this group");
        }
        GroupMember member = new GroupMember();
        member.setGroup(group);
        member.setOrgId(group.getOrgId());
        member.setUserId(req.getUserId());
        member.setRoleName(req.getRoleName());
        MemberResponse saved = toMemberResponse(memberRepo.save(member));
        teamEventPublisher.publishMemberAdded(group.getOrgId(), groupId, req.getUserId(), req.getRoleName());
        return saved;
    }

    @Transactional
    public MemberResponse updateMemberRole(UUID groupId, UUID userId, AddMemberRequest req) {
        GroupMember member = memberRepo.findByGroupIdAndUserId(groupId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Member", userId.toString()));
        member.setRoleName(req.getRoleName());
        MemberResponse saved = toMemberResponse(memberRepo.save(member));
        teamEventPublisher.publishRoleAssigned(member.getOrgId(), groupId, userId, req.getRoleName());
        return saved;
    }

    @Transactional
    public void removeMember(UUID groupId, UUID userId) {
        GroupMember member = memberRepo.findByGroupIdAndUserId(groupId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Member", userId.toString()));
        UUID orgId = member.getOrgId();
        memberRepo.delete(member);
        teamEventPublisher.publishMemberRemoved(orgId, groupId, userId);
    }

    private GroupDefinition findGroupForOrg(UUID id) {
        return groupRepo.findByIdAndOrgId(id, orgId())
                .orElseThrow(() -> new ResourceNotFoundException("Group", id.toString()));
    }

    private UUID orgId() {
        PlatformSecurityContext ctx = GatewayHeaderFilter.current();
        return ctx != null ? ctx.getOrgId() : null;
    }

    private GroupResponse toResponse(GroupDefinition g, boolean includeMembers) {
        List<MemberResponse> members = includeMembers
                ? g.getMembers().stream().map(this::toMemberResponse).toList()
                : List.of();
        return GroupResponse.builder()
                .id(g.getId()).orgId(g.getOrgId()).name(g.getName())
                .description(g.getDescription()).active(g.isActive())
                .members(members).createdAt(g.getCreatedAt()).updatedAt(g.getUpdatedAt())
                .build();
    }

    private MemberResponse toMemberResponse(GroupMember m) {
        return MemberResponse.builder()
                .id(m.getId()).userId(m.getUserId())
                .roleName(m.getRoleName()).joinedAt(m.getJoinedAt())
                .build();
    }
}
