package com.lagu.platform.security;

import lombok.RequiredArgsConstructor;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Aspect
@Component
@RequiredArgsConstructor
public class RequirePermissionAspect {

    private final PermissionEvaluator evaluator;

    @Before("@annotation(gate)")
    public void enforce(RequirePermission gate) {
        PlatformSecurityContext ctx = GatewayHeaderFilter.current();
        if (!evaluator.canAccess(ctx, gate.resource(), gate.action())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Permission denied: " + gate.action() + " on " + gate.resource());
        }
    }
}
