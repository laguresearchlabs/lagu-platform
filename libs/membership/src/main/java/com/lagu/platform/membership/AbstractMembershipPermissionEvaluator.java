package com.lagu.platform.membership;

import com.lagu.platform.security.PermissionEvaluator;
import com.lagu.platform.security.PlatformSecurityContext;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Base for a per-service {@link PermissionEvaluator} that authorizes team-management mutations
 * (invite/updateRole/remove) against that service's own membership table. Each concrete
 * subclass becomes the sole {@code PermissionEvaluator} bean for its service (via
 * {@code @Component} + {@code @ConditionalOnMissingBean} on the default), so it replicates the
 * PLATFORM_ADMIN bypass itself — there's no "falling through" to DefaultPermissionEvaluator once
 * a custom bean is registered.
 *
 * <p>{@link PermissionEvaluator#canAccess} carries no resource-instance id (only ctx/resource/
 * action — see every other usage across the platform, all type-level checks). Since vendor/event
 * org ids are local partition keys unrelated to the caller's JWT org, the target org id is
 * instead pulled from the current request's {@code @PathVariable}s. This works because every
 * mutating membership endpoint has the org/event id as a path variable, and requires zero
 * changes to {@code libs:security}'s existing interface/aspect.
 */
public abstract class AbstractMembershipPermissionEvaluator implements PermissionEvaluator {

    @Override
    public final boolean canAccess(PlatformSecurityContext ctx, String resource, String action) {
        if (ctx == null) return false;
        if (ctx.isPlatformAdmin()) return true;
        if (!supportedResource().equals(resource)) return false;
        if (ctx.getUserId() == null) return false;

        UUID pathId = currentPathId();
        if (pathId == null) return false;

        Optional<? extends MembershipRecord> membership = findMembership(pathId, ctx.getUserId());
        if (membership.isEmpty() || !membership.get().isActive()) return false;

        return switch (action) {
            case "READ" -> true;
            case "CREATE", "UPDATE", "DELETE" -> gateRoles().contains(membership.get().getRole());
            default -> false;
        };
    }

    /** Resource name this evaluator answers for, e.g. "VENDOR_MEMBER" / "EVENT_MEMBER". */
    protected abstract String supportedResource();

    /** Roles allowed to invite/updateRole/remove — the authorization gate. */
    protected abstract Set<String> gateRoles();

    /** Path-variable name carrying the id this evaluator resolves membership from, e.g. "tenantId" / "eventId". */
    protected abstract String pathIdVariable();

    /**
     * Looks up the caller's own membership row in this service's own table, given the raw
     * path-variable id (whatever {@link #pathIdVariable()} names — vendor-service's is already
     * the org id, event-service's is the Event PK and must be translated to Event.tenantId first).
     */
    protected abstract Optional<? extends MembershipRecord> findMembership(UUID pathId, UUID userId);

    private UUID currentPathId() {
        ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs == null) return null;
        @SuppressWarnings("unchecked")
        Map<String, String> vars = (Map<String, String>) attrs.getRequest()
                .getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (vars == null) return null;
        String raw = vars.get(pathIdVariable());
        try {
            return raw != null ? UUID.fromString(raw) : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
