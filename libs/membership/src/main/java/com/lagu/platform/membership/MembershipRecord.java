package com.lagu.platform.membership;

import java.util.UUID;

/**
 * Minimal view of a membership row that {@link MembershipPolicy} needs. VendorMember
 * (vendor-service) and EventMember (event-service) each implement this against their own
 * status field — there is no shared membership table, only a shared shape.
 */
public interface MembershipRecord {
    UUID getUserId();

    String getRole();

    /** True for a currently-in-effect membership (vendor: ACTIVE; event: ACCEPTED). */
    boolean isActive();
}
