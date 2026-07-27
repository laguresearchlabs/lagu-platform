package com.lagu.platform.membership;

import com.lagu.platform.common.exception.ValidationException;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Reusable membership-mutation guards shared by vendor-service and event-service. Operates
 * purely against {@link MembershipRecord}, never against either service's own role vocabulary,
 * so it stays valid whether "manager" means {OWNER, ADMIN} (vendor) or a narrower {ADMIN} (event).
 */
public final class MembershipPolicy {

    private MembershipPolicy() {}

    /** A manager must not demote or remove their own membership through this path. */
    public static void requireNotSelf(UUID requesterId, UUID targetUserId) {
        if (requesterId != null && requesterId.equals(targetUserId)) {
            throw new ValidationException("You cannot change your own membership through this action");
        }
    }

    /**
     * Refuses a role change/removal that would leave zero active members holding one of
     * {@code managerRoles}. Call this BEFORE persisting the mutation.
     *
     * @param members          the org/event's full current member list (any status)
     * @param targetUserId     the member being changed or removed
     * @param newRoleForTarget the role the target will hold afterwards; {@code null} if being removed
     * @param managerRoles     role names counted as "manager" for this guard
     */
    public static <M extends MembershipRecord> void requireManagerRemainsAfterMutation(
            List<M> members, UUID targetUserId, String newRoleForTarget, Set<String> managerRoles) {

        boolean targetCurrentlyCounts = members.stream().anyMatch(m ->
                m.getUserId().equals(targetUserId) && m.isActive() && managerRoles.contains(m.getRole()));
        if (!targetCurrentlyCounts) {
            return; // target wasn't a counted manager; this mutation can't reduce the count
        }
        if (newRoleForTarget != null && managerRoles.contains(newRoleForTarget)) {
            return; // target keeps a manager role
        }
        boolean anotherManagerRemains = members.stream()
                .filter(m -> !m.getUserId().equals(targetUserId))
                .anyMatch(m -> m.isActive() && managerRoles.contains(m.getRole()));
        if (!anotherManagerRemains) {
            throw new ValidationException(
                    "Cannot remove or demote the last remaining manager (" + managerRoles + ")");
        }
    }
}
