package com.lagu.platform.booking.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Resolves a vendor org to the one person another service can address.
 *
 * booking-service holds `vendorId` (the org), but notification-service delivers to a single
 * `recipientUserId` — so "tell the vendor about this inquiry" needs a user, and only
 * vendor-service knows who that is.
 *
 * <p><b>Best-effort by design.</b> Unlike {@link ListingServiceClient#bookSlot} — where a failed
 * call must not be mistaken for a lost race — a failure here costs a notification, not
 * correctness. A vendor who is not emailed can still see the inquiry in the portal, whereas
 * propagating would roll back the quote or cancellation that was the actual point of the request.
 * Failures are logged at WARN and the caller publishes with a null recipient; the vendor-side
 * triggers are conditioned on the recipient being present, so nothing is addressed to nobody.
 */
@Component
@Slf4j
public class VendorServiceClient {

    private final RestClient restClient;

    public VendorServiceClient(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder loadBalancedRestClientBuilder,
            @Value("${platform.gateway.shared-secret:CHANGE_ME_INSECURE_DEFAULT_SECRET_ROTATE_IN_PROD}")
            String gatewaySharedSecret) {
        this.restClient = loadBalancedRestClientBuilder.clone()
                .baseUrl("http://vendor-service")
                .defaultHeader("X-Internal-Service", "booking-service")
                .defaultHeader("X-Platform-Gateway-Secret", gatewaySharedSecret)
                .build();
    }

    /**
     * Who to notify for a vendor org, and where to email them.
     *
     * @param userId       the org's active OWNER — never null when the target resolves at all
     * @param contactEmail the org's business contact address from its VENDOR record; **nullable**,
     *                     since the field is optional on the VENDOR schema. A null address costs
     *                     the email half of the notification; the in-app half still lands.
     */
    public record NotificationTarget(UUID userId, String contactEmail) {}

    /**
     * The org's notification target, or empty when vendor-service is unreachable, the org has no
     * active owner, or the response is not shaped as expected.
     *
     * <p>One recipient is a known limitation rather than a modelling choice: a vendor org can have
     * several ADMIN/MEMBER users who handle bookings, and today only the owner hears about them.
     * Widening this needs fan-out in notification-service, which takes one recipient per
     * notification — see AutomationSeeder's javadoc.
     */
    public Optional<NotificationTarget> findNotificationTarget(UUID tenantId) {
        if (tenantId == null) return Optional.empty();
        try {
            Map<String, Object> response = restClient.get()
                    .uri("/internal/memberships/{tenantId}/owner", tenantId)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
            if (response == null) return Optional.empty();
            if (!(response.get("data") instanceof Map<?, ?> data)) return Optional.empty();

            Object userId = data.get("userId");
            if (userId == null) return Optional.empty();

            Object email = data.get("contactEmail");
            String contactEmail = email != null && !email.toString().isBlank() ? email.toString() : null;

            return Optional.of(new NotificationTarget(UUID.fromString(userId.toString()), contactEmail));
        } catch (Exception e) {
            log.warn("Could not resolve notification recipient for vendor org {}: {} — "
                    + "the booking still commits, but its vendor-side notification is skipped",
                    tenantId, e.getMessage());
            return Optional.empty();
        }
    }
}
