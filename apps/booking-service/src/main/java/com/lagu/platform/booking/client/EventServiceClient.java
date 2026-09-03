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
 * What event-service knows about a person's standing on an event.
 *
 * <p>A booking carries an {@code eventId}, but booking-service has never known what an event
 * membership <em>is</em> — so the only consumer-side read it could offer was "yours", filtered by
 * the requester. That is why two co-hosts planning one wedding could not see that the other had
 * already asked the caterer for a quote, which is the coordination the event's Vendors tab exists
 * for.
 *
 * <p>This asks the one question needed to close that, and booking-service enforces the answer
 * itself. Authorization does not move here: this returns a fact, and BookingService decides what
 * it means, the same way event-service's own clients work.
 */
@Component
@Slf4j
public class EventServiceClient {

    private final RestClient restClient;

    public EventServiceClient(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder loadBalancedRestClientBuilder,
            @Value("${platform.gateway.shared-secret:CHANGE_ME_INSECURE_DEFAULT_SECRET_ROTATE_IN_PROD}")
            String gatewaySharedSecret) {
        this.restClient = loadBalancedRestClientBuilder.clone()
                .baseUrl("http://event-service")
                .defaultHeader("X-Internal-Service", "booking-service")
                .defaultHeader("X-Platform-Gateway-Secret", gatewaySharedSecret)
                .build();
    }

    /** A member's role and status. Absent when they are not on the event at all. */
    public record Membership(String role, String status) {

        /** ADMIN or MAINTAINER, and actually in the event rather than merely invited. */
        public boolean canManage() {
            return "ACCEPTED".equals(status) && ("ADMIN".equals(role) || "MAINTAINER".equals(role));
        }
    }

    /**
     * Fails closed. A lookup that errors, times out or returns something unreadable is treated as
     * "no membership" — the caller is about to widen what somebody can see on the strength of the
     * answer, and an event-service outage must not be a way to read another host's inquiries.
     */
    @SuppressWarnings("unchecked")
    public Optional<Membership> membershipOf(UUID eventId, UUID userId) {
        try {
            Map<String, Object> response = restClient.get()
                    .uri("/api/v1/events/{eventId}/members/{userId}/membership", eventId, userId)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
            if (response == null) return Optional.empty();
            if (!(response.get("data") instanceof Map<?, ?> m)) return Optional.empty();

            Map<String, Object> data = (Map<String, Object>) m;
            return Optional.of(new Membership(
                    (String) data.get("role"),
                    (String) data.get("status")));
        } catch (Exception e) {
            // Debug, not warn: "not a member" is a 404 here and is an ordinary answer, not a fault.
            log.debug("No membership for user {} on event {}: {}", userId, eventId, e.getMessage());
            return Optional.empty();
        }
    }
}
