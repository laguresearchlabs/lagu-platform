package com.lagu.platform.event.security;

import com.lagu.platform.event.domain.EventMember;
import com.lagu.platform.event.domain.EventMemberRepository;
import com.lagu.platform.security.PlatformSecurityContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Since Event.id doubles as the org-partition key (Event.getTenantId() just returns id — see
 * Event.java), the {@code {eventId}} path variable is usable directly as EventMember.tenantId with
 * no translation step — these tests just confirm the direct lookup and role gating.
 */
class EventMembershipPermissionEvaluatorTest {

    private final EventMemberRepository memberRepo = mock(EventMemberRepository.class);
    private final EventMembershipPermissionEvaluator evaluator =
            new EventMembershipPermissionEvaluator(memberRepo);

    private final UUID eventId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    private void setPathVariable(String name, String value) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of(name, value));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private PlatformSecurityContext ctx() {
        return PlatformSecurityContext.builder().userId(userId).roles(Set.of()).build();
    }

    private static EventMember member(String role) {
        EventMember m = new EventMember();
        m.setRole(role);
        m.setStatus("ACCEPTED");
        return m;
    }

    @Test
    void resolvesMembershipDirectlyFromPathEventId() {
        setPathVariable("eventId", eventId.toString());
        EventMember member = member("ADMIN");
        member.setUserId(userId);
        when(memberRepo.findByTenantIdAndUserIdAndStatus(eventId, userId, "ACCEPTED"))
                .thenReturn(Optional.of(member));

        assertThat(evaluator.canAccess(ctx(), "EVENT_MEMBER", "DELETE")).isTrue();
    }

    @Test
    void deniesWhenNoMembershipExists() {
        setPathVariable("eventId", eventId.toString());
        when(memberRepo.findByTenantIdAndUserIdAndStatus(eventId, userId, "ACCEPTED"))
                .thenReturn(Optional.empty());

        assertThat(evaluator.canAccess(ctx(), "EVENT_MEMBER", "READ")).isFalse();
    }

    @Test
    void allowsMaintainerToUpdateNotJustAdmin() {
        setPathVariable("eventId", eventId.toString());
        EventMember member = member("MAINTAINER");
        member.setUserId(userId);
        when(memberRepo.findByTenantIdAndUserIdAndStatus(eventId, userId, "ACCEPTED"))
                .thenReturn(Optional.of(member));

        assertThat(evaluator.canAccess(ctx(), "EVENT_MEMBER", "UPDATE")).isTrue();
    }

    @Test
    void deniesInviteeFromUpdatingDespiteBeingAnActiveMember() {
        setPathVariable("eventId", eventId.toString());
        EventMember member = member("INVITEE");
        member.setUserId(userId);
        when(memberRepo.findByTenantIdAndUserIdAndStatus(eventId, userId, "ACCEPTED"))
                .thenReturn(Optional.of(member));

        assertThat(evaluator.canAccess(ctx(), "EVENT_MEMBER", "UPDATE")).isFalse();
    }
}
