package com.lagu.platform.event.security;

import com.lagu.platform.event.domain.EventMemberRepository;
import com.lagu.platform.membership.AbstractMembershipPermissionEvaluator;
import com.lagu.platform.membership.MembershipRecord;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Authorizes EventMemberController's invite/updateRole/remove endpoints against EventMember.
 *
 * <p>The {@code {eventId}} path variable can be used directly as EventMember.tenantId now — Event.id
 * doubles as the org-partition key (see {@code Event.getTenantId()}), so no separate lookup is
 * needed to translate one into the other.
 */
@Component
@RequiredArgsConstructor
public class EventMembershipPermissionEvaluator extends AbstractMembershipPermissionEvaluator {

    private final EventMemberRepository memberRepo;

    @Override
    protected String supportedResource() {
        return "EVENT_MEMBER";
    }

    @Override
    protected Set<String> gateRoles() {
        return Set.of("ADMIN", "MAINTAINER"); // matches EventMember.canManage()
    }

    @Override
    protected String pathIdVariable() {
        return "eventId";
    }

    @Override
    protected Optional<? extends MembershipRecord> findMembership(UUID pathEventId, UUID userId) {
        return memberRepo.findByTenantIdAndUserIdAndStatus(pathEventId, userId, "ACCEPTED");
    }
}
