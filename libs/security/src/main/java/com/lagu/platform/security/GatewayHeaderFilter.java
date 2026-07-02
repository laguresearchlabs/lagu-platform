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
 * Trusts X-User-Id/X-Org-Id/X-User-Roles only when accompanied by the shared
 * {@link #HEADER_GATEWAY_SECRET} header that gateway-service alone knows how to set.
 * Without a matching secret, a caller reaching a service directly (bypassing the gateway,
 * e.g. via docker-compose's published host ports) cannot forge identity — the request is
 * simply treated as unauthenticated instead.
 */
@Slf4j
public class GatewayHeaderFilter extends OncePerRequestFilter {

    static final String HEADER_USER_ID        = "X-User-Id";
    static final String HEADER_ORG_ID         = "X-Org-Id";
    static final String HEADER_USER_ROLES     = "X-User-Roles";
    static final String HEADER_USER_EMAIL     = "X-User-Email";
    static final String HEADER_GATEWAY_SECRET = "X-Platform-Gateway-Secret";

    private static final ThreadLocal<PlatformSecurityContext> CONTEXT = new ThreadLocal<>();

    private final String expectedGatewaySecret;

    public GatewayHeaderFilter(String expectedGatewaySecret) {
        this.expectedGatewaySecret = expectedGatewaySecret;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res,
                                    FilterChain chain) throws IOException, ServletException {
        try {
            String userIdHeader = req.getHeader(HEADER_USER_ID);
            if (userIdHeader != null && requestFromTrustedGateway(req)) {
                String rolesHeader = req.getHeader(HEADER_USER_ROLES);
                String orgIdHeader = req.getHeader(HEADER_ORG_ID);

                Set<String> roles = rolesHeader != null
                        ? new HashSet<>(Arrays.asList(rolesHeader.split(",")))
                        : Collections.emptySet();

                PlatformSecurityContext ctx = PlatformSecurityContext.builder()
                        .userId(UUID.fromString(userIdHeader))
                        .orgId(orgIdHeader != null ? UUID.fromString(orgIdHeader) : null)
                        .roles(roles)
                        .userEmail(req.getHeader(HEADER_USER_EMAIL))
                        .build();

                CONTEXT.set(ctx);

                UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                        ctx, null,
                        roles.stream().map(SimpleGrantedAuthority::new).toList());
                SecurityContextHolder.getContext().setAuthentication(auth);
            } else if (userIdHeader != null) {
                log.warn("Ignoring identity headers on request without a valid gateway secret " +
                        "(path={}) — treating as unauthenticated", req.getRequestURI());
            }
            chain.doFilter(req, res);
        } catch (IllegalArgumentException e) {
            // Malformed X-User-Id/X-Org-Id (not a UUID) — reject cleanly rather than 500.
            res.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid identity header");
        } finally {
            CONTEXT.remove();
            SecurityContextHolder.clearContext();
        }
    }

    private boolean requestFromTrustedGateway(HttpServletRequest req) {
        if (expectedGatewaySecret == null || expectedGatewaySecret.isBlank()) {
            // Not configured on this service — fail open to avoid breaking environments that
            // haven't set platform.gateway.shared-secret yet, but make it loud in the logs.
            log.warn("platform.gateway.shared-secret is not configured — trusting identity " +
                    "headers unconditionally. Set this property in production.");
            return true;
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
