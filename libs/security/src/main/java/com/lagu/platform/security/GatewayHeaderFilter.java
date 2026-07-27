package com.lagu.platform.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Trusts X-User-Id/X-Tenant-Id/X-User-Roles only when accompanied by the shared
 * {@link #HEADER_GATEWAY_SECRET} header that gateway-service alone knows how to set.
 * Without a matching secret, a caller reaching a service directly (bypassing the gateway,
 * e.g. via docker-compose's published host ports) cannot forge identity — the request is
 * simply treated as unauthenticated instead.
 *
 * <p>Two identity shapes are recognised on trusted requests:
 * <ul>
 *   <li><b>User</b> — {@code X-User-Id} (+ optional org/roles/email) set by the gateway from
 *       the caller's JWT.</li>
 *   <li><b>Internal service</b> — {@code X-Internal-Service: <service-name>} set by another
 *       lagu-platform service. The caller is granted a single {@code SVC_<NAME>} role (never
 *       user/admin roles; {@link DefaultPermissionEvaluator} defines what services may do).
 *       {@code X-User-Id}/{@code X-Tenant-Id} are optional and carry the acting user/org for
 *       tenancy scoping and audit attribution. The gateway strips {@code X-Internal-Service}
 *       from external requests, so this shape cannot be reached from outside.</li>
 * </ul>
 */
@Slf4j
public class GatewayHeaderFilter extends OncePerRequestFilter {

    static final String HEADER_USER_ID          = "X-User-Id";
    static final String HEADER_TENANT_ID           = "X-Tenant-Id";
    static final String HEADER_USER_ROLES       = "X-User-Roles";
    static final String HEADER_USER_EMAIL       = "X-User-Email";
    static final String HEADER_GATEWAY_SECRET   = "X-Platform-Gateway-Secret";
    static final String HEADER_INTERNAL_SERVICE = "X-Internal-Service";

    /** Prefix for roles granted to internal service callers, e.g. {@code SVC_VENDOR_SERVICE}. */
    public static final String SERVICE_ROLE_PREFIX = "SVC_";

    private static final ThreadLocal<PlatformSecurityContext> CONTEXT = new ThreadLocal<>();

    private final String expectedGatewaySecret;

    public GatewayHeaderFilter(String expectedGatewaySecret) {
        this.expectedGatewaySecret = expectedGatewaySecret;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res,
                                    FilterChain chain) throws IOException, ServletException {
        try {
            String userIdHeader  = req.getHeader(HEADER_USER_ID);
            String serviceHeader = req.getHeader(HEADER_INTERNAL_SERVICE);
            boolean hasIdentity  = userIdHeader != null || serviceHeader != null;

            if (hasIdentity && requestFromTrustedGateway(req)) {
                PlatformSecurityContext ctx = serviceHeader != null
                        ? buildServiceContext(req, serviceHeader, userIdHeader)
                        : buildUserContext(req, userIdHeader);

                CONTEXT.set(ctx);
                UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                        ctx, null,
                        ctx.getRoles().stream().map(SimpleGrantedAuthority::new).toList());
                SecurityContextHolder.getContext().setAuthentication(auth);
            } else if (hasIdentity) {
                log.warn("Ignoring identity headers on request without a valid gateway secret " +
                        "(path={}) — treating as unauthenticated", req.getRequestURI());
            }
            chain.doFilter(req, res);
        } catch (IllegalArgumentException e) {
            // Malformed X-User-Id/X-Tenant-Id (not a UUID) — reject cleanly rather than 500.
            res.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid identity header");
        } finally {
            CONTEXT.remove();
            SecurityContextHolder.clearContext();
        }
    }

    private PlatformSecurityContext buildUserContext(HttpServletRequest req, String userIdHeader) {
        String rolesHeader = req.getHeader(HEADER_USER_ROLES);
        String tenantIdHeader = req.getHeader(HEADER_TENANT_ID);

        Set<String> roles = rolesHeader != null
                ? new HashSet<>(Arrays.asList(rolesHeader.split(",")))
                : Collections.emptySet();
        // A user request must never carry service roles — only X-Internal-Service grants those.
        roles.removeIf(r -> r.startsWith(SERVICE_ROLE_PREFIX));

        return PlatformSecurityContext.builder()
                .userId(UUID.fromString(userIdHeader))
                .tenantId(tenantIdHeader != null ? UUID.fromString(tenantIdHeader) : null)
                .roles(roles)
                .userEmail(req.getHeader(HEADER_USER_EMAIL))
                .build();
    }

    /**
     * Internal callers get exactly one role derived from their own service name — the
     * X-User-Roles header is deliberately ignored so a service cannot self-assign
     * PLATFORM_ADMIN (or any other user role).
     */
    private PlatformSecurityContext buildServiceContext(HttpServletRequest req, String serviceName,
                                                        String actingUserIdHeader) {
        String tenantIdHeader = req.getHeader(HEADER_TENANT_ID);
        return PlatformSecurityContext.builder()
                .userId(actingUserIdHeader != null ? UUID.fromString(actingUserIdHeader) : null)
                .tenantId(tenantIdHeader != null && !tenantIdHeader.isBlank()
                        ? UUID.fromString(tenantIdHeader) : null)
                .roles(Set.of(serviceRole(serviceName)))
                .build();
    }

    static String serviceRole(String serviceName) {
        return SERVICE_ROLE_PREFIX + serviceName.trim().toUpperCase().replaceAll("[^A-Z0-9]", "_");
    }

    private boolean requestFromTrustedGateway(HttpServletRequest req) {
        if (expectedGatewaySecret == null || expectedGatewaySecret.isBlank()) {
            // Fail closed: with no secret configured there is no way to tell the gateway from
            // an impostor, so identity headers are never trusted. ServiceSecurityConfig refuses
            // to start in this state unless explicitly overridden for local dev.
            log.error("platform.gateway.shared-secret is not configured — refusing to trust " +
                    "identity headers. All requests are treated as unauthenticated.");
            return false;
        }
        String presented = req.getHeader(HEADER_GATEWAY_SECRET);
        return presented != null && constantTimeEquals(expectedGatewaySecret, presented);
    }

    private boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    public static PlatformSecurityContext current() {
        return CONTEXT.get();
    }
}
