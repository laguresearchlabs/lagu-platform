package com.lagu.platform.vendor.security;

import com.lagu.platform.security.PlatformSecurityContext;
import com.lagu.platform.vendor.domain.VendorMember;
import com.lagu.platform.vendor.domain.VendorMemberRepository;
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

class VendorMembershipPermissionEvaluatorTest {

    private final VendorMemberRepository memberRepo = mock(VendorMemberRepository.class);
    private final VendorMembershipPermissionEvaluator evaluator =
            new VendorMembershipPermissionEvaluator(memberRepo);

    private final UUID tenantId = UUID.randomUUID();
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

    private VendorMember member(String role) {
        VendorMember m = new VendorMember();
        m.setTenantId(tenantId);
        m.setUserId(userId);
        m.setRole(role);
        return m; // status defaults to ACTIVE
    }

    private PlatformSecurityContext ctx(Set<String> roles) {
        return PlatformSecurityContext.builder().userId(userId).roles(roles).build();
    }

    @Test
    void platformAdminBypassesEverything() {
        assertThat(evaluator.canAccess(ctx(Set.of("PLATFORM_ADMIN")), "VENDOR_MEMBER", "DELETE")).isTrue();
    }

    @Test
    void deniesWhenNoPathVariablePresent() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        assertThat(evaluator.canAccess(ctx(Set.of()), "VENDOR_MEMBER", "CREATE")).isFalse();
    }

    @Test
    void deniesUnsupportedResource() {
        setPathVariable("tenantId", tenantId.toString());
        when(memberRepo.findByTenantIdAndUserIdAndStatus(tenantId, userId, "ACTIVE"))
                .thenReturn(Optional.of(member("OWNER")));
        assertThat(evaluator.canAccess(ctx(Set.of()), "EVENT_MEMBER", "CREATE")).isFalse();
    }

    @Test
    void allowsCreateForActiveOwner() {
        setPathVariable("tenantId", tenantId.toString());
        when(memberRepo.findByTenantIdAndUserIdAndStatus(tenantId, userId, "ACTIVE"))
                .thenReturn(Optional.of(member("OWNER")));
        assertThat(evaluator.canAccess(ctx(Set.of()), "VENDOR_MEMBER", "CREATE")).isTrue();
    }

    @Test
    void deniesDeleteForPlainMember() {
        setPathVariable("tenantId", tenantId.toString());
        when(memberRepo.findByTenantIdAndUserIdAndStatus(tenantId, userId, "ACTIVE"))
                .thenReturn(Optional.of(member("MEMBER")));
        assertThat(evaluator.canAccess(ctx(Set.of()), "VENDOR_MEMBER", "DELETE")).isFalse();
    }

    @Test
    void allowsReadForPlainMember() {
        setPathVariable("tenantId", tenantId.toString());
        when(memberRepo.findByTenantIdAndUserIdAndStatus(tenantId, userId, "ACTIVE"))
                .thenReturn(Optional.of(member("MEMBER")));
        assertThat(evaluator.canAccess(ctx(Set.of()), "VENDOR_MEMBER", "READ")).isTrue();
    }

    @Test
    void deniesWhenCallerHasNoMembershipRow() {
        setPathVariable("tenantId", tenantId.toString());
        when(memberRepo.findByTenantIdAndUserIdAndStatus(tenantId, userId, "ACTIVE")).thenReturn(Optional.empty());
        assertThat(evaluator.canAccess(ctx(Set.of()), "VENDOR_MEMBER", "READ")).isFalse();
    }
}
