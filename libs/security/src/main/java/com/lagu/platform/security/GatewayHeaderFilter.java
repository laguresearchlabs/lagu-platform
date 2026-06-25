package com.lagu.platform.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class GatewayHeaderFilter extends OncePerRequestFilter {

    static final String HEADER_USER_ID    = "X-User-Id";
    static final String HEADER_ORG_ID     = "X-Org-Id";
    static final String HEADER_USER_ROLES = "X-User-Roles";
    static final String HEADER_USER_EMAIL = "X-User-Email";

    private static final ThreadLocal<PlatformSecurityContext> CONTEXT = new ThreadLocal<>();

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res,
                                    FilterChain chain) throws IOException, ServletException {
        try {
            String userIdHeader = req.getHeader(HEADER_USER_ID);
            if (userIdHeader != null) {
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
            }
            chain.doFilter(req, res);
        } finally {
            CONTEXT.remove();
            SecurityContextHolder.clearContext();
        }
    }

    public static PlatformSecurityContext current() {
        return CONTEXT.get();
    }
}
