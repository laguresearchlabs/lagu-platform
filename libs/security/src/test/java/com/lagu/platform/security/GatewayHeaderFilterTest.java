package com.lagu.platform.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The single highest-stakes class in the platform, and until now it had no tests at all.
 *
 * <p>Every service trusts {@code X-User-Id} because this filter says it may. If that decision is
 * wrong in either direction the consequences are total: trust a forged header and any caller
 * becomes any user, or leak a ThreadLocal and one request answers as the previous request's user.
 * Neither failure produces an error anyone would notice.
 *
 * <p>gateway-service's only test class is entirely commented out, so nothing anywhere covered this
 * path. These are the properties the whole authorization model rests on.
 */
class GatewayHeaderFilterTest {

    private static final String SECRET = "the-real-shared-secret";
    private static final String USER = "11111111-1111-1111-1111-111111111111";
    private static final String TENANT = "22222222-2222-2222-2222-222222222222";

    /** What the downstream service would see — captured mid-chain, before the finally-block clears it. */
    private PlatformSecurityContext seen;

    private final FilterChain capturing = (req, res) -> seen = GatewayHeaderFilter.current();

    @AfterEach
    void clearLeakedState() {
        SecurityContextHolder.clearContext();
    }

    private MockHttpServletResponse run(GatewayHeaderFilter filter, MockHttpServletRequest req)
            throws ServletException, IOException {
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, capturing);
        return res;
    }

    private MockHttpServletRequest request() {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/records");
        req.setRequestURI("/api/v1/records");
        return req;
    }

    private GatewayHeaderFilter filter() {
        return new GatewayHeaderFilter(SECRET);
    }

    // ── the property everything else rests on ─────────────────────────────────

    @Test
    void identityHeadersWithoutTheSecretAreIgnored() throws Exception {
        // A caller that reaches a service pod directly inside the cluster and simply asserts who
        // it is. If this passes, every authorization check in the platform is decorative.
        MockHttpServletRequest req = request();
        req.addHeader("X-User-Id", USER);
        req.addHeader("X-User-Roles", "PLATFORM_ADMIN");

        run(filter(), req);

        assertThat(seen).isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void identityHeadersWithTheWrongSecretAreIgnored() throws Exception {
        MockHttpServletRequest req = request();
        req.addHeader("X-User-Id", USER);
        req.addHeader("X-Platform-Gateway-Secret", "not-the-secret");

        run(filter(), req);

        assertThat(seen).isNull();
    }

    @Test
    void aSecretThatIsAPrefixOfTheRealOneIsRejected() throws Exception {
        // Guards the constant-time comparison against being replaced by something that returns
        // early on length or first-mismatch.
        MockHttpServletRequest req = request();
        req.addHeader("X-User-Id", USER);
        req.addHeader("X-Platform-Gateway-Secret", SECRET.substring(0, SECRET.length() - 1));

        run(filter(), req);

        assertThat(seen).isNull();
    }

    @Test
    void withNoSecretConfiguredNothingIsTrustedEvenWithAMatchingHeader() throws Exception {
        // Fail closed. A blank configured secret must not become "any caller presenting a blank
        // secret is the gateway" — which is what a naive equals() would do.
        for (String configured : new String[]{null, "", "   "}) {
            seen = null;
            MockHttpServletRequest req = request();
            req.addHeader("X-User-Id", USER);
            req.addHeader("X-Platform-Gateway-Secret", configured == null ? "" : configured);

            run(new GatewayHeaderFilter(configured), req);

            assertThat(seen).as("configured secret %s", configured == null ? "null" : "'" + configured + "'").isNull();
        }
    }

    @Test
    void aValidGatewayRequestIsTrusted() throws Exception {
        MockHttpServletRequest req = request();
        req.addHeader("X-User-Id", USER);
        req.addHeader("X-Tenant-Id", TENANT);
        req.addHeader("X-User-Roles", "ORG_OWNER,USER");
        req.addHeader("X-User-Email", "someone@example.com");
        req.addHeader("X-User-Phone", "+15551234567");
        req.addHeader("X-Platform-Gateway-Secret", SECRET);

        run(filter(), req);

        assertThat(seen).isNotNull();
        assertThat(seen.getUserId()).isEqualTo(UUID.fromString(USER));
        assertThat(seen.getTenantId()).isEqualTo(UUID.fromString(TENANT));
        assertThat(seen.getRoles()).containsExactlyInAnyOrder("ORG_OWNER", "USER");
        assertThat(seen.getUserEmail()).isEqualTo("someone@example.com");
        assertThat(seen.getUserPhone()).isEqualTo("+15551234567");
    }

    /**
     * The gateway only forwards X-User-Phone when the JWT's phone claim was verified — this just
     * confirms the header is optional here too, mirroring email, rather than assuming presence.
     */
    @Test
    void aRequestWithNoPhoneHeaderLeavesUserPhoneNull() throws Exception {
        MockHttpServletRequest req = request();
        req.addHeader("X-User-Id", USER);
        req.addHeader("X-Platform-Gateway-Secret", SECRET);

        run(filter(), req);

        assertThat(seen).isNotNull();
        assertThat(seen.getUserPhone()).isNull();
    }

    // ── privilege escalation in both directions ───────────────────────────────

    @Test
    void aUserRequestCannotSelfAssignAServiceRole() throws Exception {
        // SVC_* roles grant the internal-caller powers in DefaultPermissionEvaluator. Only
        // X-Internal-Service may confer them, never a role string the gateway forwarded.
        MockHttpServletRequest req = request();
        req.addHeader("X-User-Id", USER);
        req.addHeader("X-User-Roles", "USER,SVC_RECORD_SERVICE,SVC_ANYTHING");
        req.addHeader("X-Platform-Gateway-Secret", SECRET);

        run(filter(), req);

        assertThat(seen.getRoles()).containsExactly("USER");
        assertThat(seen.isInternalService()).isFalse();
    }

    @Test
    void anInternalServiceCannotSelfAssignPlatformAdmin() throws Exception {
        // The mirror image: X-User-Roles is ignored entirely for a service caller, so a
        // compromised service cannot promote itself past the narrow SVC_* grant.
        MockHttpServletRequest req = request();
        req.addHeader("X-Internal-Service", "record-service");
        req.addHeader("X-User-Roles", "PLATFORM_ADMIN,CONFIG_ADMIN");
        req.addHeader("X-Platform-Gateway-Secret", SECRET);

        run(filter(), req);

        assertThat(seen.getRoles()).containsExactly("SVC_RECORD_SERVICE");
        assertThat(seen.isPlatformAdmin()).isFalse();
        assertThat(seen.isConfigAdmin()).isFalse();
        assertThat(seen.isInternalService()).isTrue();
    }

    @Test
    void aServiceNameCannotSmuggleCharactersIntoTheRoleItGrants() throws Exception {
        // serviceRole() sanitises, so a crafted name cannot produce a role string that collides
        // with a real one or breaks a comma-separated role list downstream.
        assertThat(GatewayHeaderFilter.serviceRole("record-service")).isEqualTo("SVC_RECORD_SERVICE");
        assertThat(GatewayHeaderFilter.serviceRole("  spaced  ")).isEqualTo("SVC_SPACED");

        // The property, rather than a hand-counted literal: whatever the name contains, nothing
        // that could split a role list or traverse a path survives into the granted role.
        for (String hostile : new String[]{
                "evil,PLATFORM_ADMIN", "../../etc", "a b\tc", "x;y",
                "role\nPLATFORM_ADMIN"}) {
            assertThat(GatewayHeaderFilter.serviceRole(hostile))
                    .as("service name %s", hostile)
                    .startsWith("SVC_")
                    .matches("SVC_[A-Z0-9_]*");
        }

        // And specifically: a comma cannot survive to forge a second role downstream.
        assertThat(GatewayHeaderFilter.serviceRole("evil,PLATFORM_ADMIN")).doesNotContain(",");
    }

    @Test
    void aServiceCallerCarriesTheActingUserForAuditWithoutGainingTheirRoles() throws Exception {
        // workflow-service applying an approved change set does exactly this: the approving
        // admin's id travels for attribution, but the roles are the service's own.
        MockHttpServletRequest req = request();
        req.addHeader("X-Internal-Service", "workflow-service");
        req.addHeader("X-User-Id", USER);
        req.addHeader("X-Tenant-Id", TENANT);
        req.addHeader("X-Platform-Gateway-Secret", SECRET);

        run(filter(), req);

        assertThat(seen.getUserId()).isEqualTo(UUID.fromString(USER));
        assertThat(seen.getTenantId()).isEqualTo(UUID.fromString(TENANT));
        assertThat(seen.getRoles()).containsExactly("SVC_WORKFLOW_SERVICE");
    }

    @Test
    void aBlankTenantHeaderOnAServiceCallIsNoTenantRatherThanAParseFailure() throws Exception {
        // vendor-service's IamServiceClient sends "" when it has no org in hand.
        MockHttpServletRequest req = request();
        req.addHeader("X-Internal-Service", "vendor-service");
        req.addHeader("X-Tenant-Id", "");
        req.addHeader("X-Platform-Gateway-Secret", SECRET);

        run(filter(), req);

        assertThat(seen).isNotNull();
        assertThat(seen.getTenantId()).isNull();
    }

    // ── malformed input ───────────────────────────────────────────────────────

    @Test
    void aMalformedUserIdIs401RatherThan500() throws Exception {
        MockHttpServletRequest req = request();
        req.addHeader("X-User-Id", "not-a-uuid");
        req.addHeader("X-Platform-Gateway-Secret", SECRET);

        MockHttpServletResponse res = run(filter(), req);

        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(seen).isNull();
    }

    @Test
    void aMalformedTenantIdIs401RatherThan500() throws Exception {
        MockHttpServletRequest req = request();
        req.addHeader("X-User-Id", USER);
        req.addHeader("X-Tenant-Id", "also-not-a-uuid");
        req.addHeader("X-Platform-Gateway-Secret", SECRET);

        assertThat(run(filter(), req).getStatus()).isEqualTo(401);
    }

    @Test
    void aRequestWithNoIdentityAtAllPassesThroughAsAnonymous() throws Exception {
        // Public endpoints — consumer search, the share preview — depend on this.
        MockHttpServletResponse res = run(filter(), request());

        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(seen).isNull();
    }

    // ── the leak that would be invisible ──────────────────────────────────────

    @Test
    void theContextIsClearedAfterTheRequest() throws Exception {
        // Tomcat pools threads. A context left behind would make the *next* request on that
        // thread answer as the previous request's user — cross-tenant data disclosure with no
        // error, no log line, and nothing to notice.
        MockHttpServletRequest req = request();
        req.addHeader("X-User-Id", USER);
        req.addHeader("X-Platform-Gateway-Secret", SECRET);

        run(filter(), req);

        assertThat(seen).isNotNull();
        assertThat(GatewayHeaderFilter.current()).isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void theContextIsClearedEvenWhenTheChainThrows() throws Exception {
        // The finally-block matters most on the failure path: an exception mid-request must not
        // strand an authenticated identity on a pooled thread.
        MockHttpServletRequest req = request();
        req.addHeader("X-User-Id", USER);
        req.addHeader("X-Platform-Gateway-Secret", SECRET);

        FilterChain exploding = (rq, rs) -> { throw new ServletException("downstream blew up"); };

        try {
            filter().doFilter(req, new MockHttpServletResponse(), exploding);
        } catch (ServletException expected) {
            // the point is what happens to the ThreadLocal, not the exception
        }

        assertThat(GatewayHeaderFilter.current()).isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void aRejectedRequestLeavesNothingBehindForTheNextOne() throws Exception {
        MockHttpServletRequest forged = request();
        forged.addHeader("X-User-Id", USER);
        forged.addHeader("X-User-Roles", "PLATFORM_ADMIN");

        run(filter(), forged);

        assertThat(GatewayHeaderFilter.current()).isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void authoritiesHandedToSpringSecurityMatchTheResolvedRoles() throws Exception {
        // @RequirePermission reads PlatformSecurityContext, but anything using Spring Security's
        // own authorities reads these — they must not diverge.
        AtomicReference<Object> authorities = new AtomicReference<>();
        MockHttpServletRequest req = request();
        req.addHeader("X-User-Id", USER);
        req.addHeader("X-User-Roles", "ORG_OWNER,SVC_SNEAKY");
        req.addHeader("X-Platform-Gateway-Secret", SECRET);

        filter().doFilter(req, new MockHttpServletResponse(), (rq, rs) ->
                authorities.set(SecurityContextHolder.getContext().getAuthentication().getAuthorities()
                        .stream().map(Object::toString).sorted().toList()));

        assertThat(authorities.get()).isEqualTo(java.util.List.of("ORG_OWNER"));
    }
}
