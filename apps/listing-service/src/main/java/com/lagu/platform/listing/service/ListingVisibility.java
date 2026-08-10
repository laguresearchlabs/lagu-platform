package com.lagu.platform.listing.service;

import com.lagu.platform.listing.domain.ListingSnapshot;
import com.lagu.platform.security.GatewayHeaderFilter;
import com.lagu.platform.security.PlatformSecurityContext;

/**
 * Who may see a snapshot.
 *
 * <p>A PUBLISHED snapshot is genuinely public — that is what "consumer-facing" means here.
 * Anything else (UNPUBLISHED, SUSPENDED, a suspended vendor's data) is visible only to the owning
 * org or a platform admin.
 *
 * <p>Shared rather than repeated because it now governs more than one endpoint, and the failure
 * mode of a second copy is silent: a new endpoint that forgets it exposes unpublished listings
 * without anything looking wrong.
 */
public final class ListingVisibility {

    private ListingVisibility() {
    }

    public static boolean isVisibleToCaller(ListingSnapshot snapshot) {
        if ("PUBLISHED".equals(snapshot.getStatus())) return true;
        PlatformSecurityContext ctx = GatewayHeaderFilter.current();
        return ctx != null
                && (ctx.isPlatformAdmin() || snapshot.getTenantId().equals(ctx.getTenantId()));
    }
}
