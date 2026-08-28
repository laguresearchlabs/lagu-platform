package com.lagu.platform.vendor.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.UUID;

/**
 * Tells listing-service that an org has been activated, so listings held behind the KYC gate can
 * go live.
 *
 * <p><b>Best-effort, and deliberately so.</b> The activation itself is the thing that must not
 * fail: an admin approving a vendor should not get an error because a downstream service is
 * restarting, and rolling the approval back would be worse than a delayed publish. A failure here
 * costs visibility until someone re-runs it, which is why the reconcile endpoint is idempotent and
 * why this logs at WARN with the exact call to repeat.
 */
@Component
@Slf4j
public class ListingServiceClient {

    private final RestClient restClient;

    public ListingServiceClient(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder loadBalancedRestClientBuilder,
            @Value("${platform.gateway.shared-secret:CHANGE_ME_INSECURE_DEFAULT_SECRET_ROTATE_IN_PROD}")
            String gatewaySharedSecret) {
        this.restClient = loadBalancedRestClientBuilder.clone()
                .baseUrl("http://listing-service")
                .defaultHeader("X-Internal-Service", "vendor-service")
                .defaultHeader("X-Platform-Gateway-Secret", gatewaySharedSecret)
                .build();
    }

    /** @return how many held snapshots went live, or -1 if the call could not be made */
    @SuppressWarnings("unchecked")
    public int reconcileHeldListings(UUID tenantId) {
        try {
            Map<String, Object> response = restClient.post()
                    .uri("/internal/listings/reconcile/{tenantId}", tenantId)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
            Object data = response == null ? null : response.get("data");
            if (data instanceof Map<?, ?> m) {
                Object released = ((Map<String, Object>) m).get("released");
                return released instanceof Number n ? n.intValue() : 0;
            }
            return 0;
        } catch (Exception e) {
            log.warn("Could not release held listings for org {} after activation: {} — "
                            + "the org is ACTIVE but its listings stay hidden until this is re-run: "
                            + "POST http://listing-service/internal/listings/reconcile/{}",
                    tenantId, e.getMessage(), tenantId);
            return -1;
        }
    }
}
