package com.lagu.platform.event.client;

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
 * Whether an email invited to an event already belongs to an account — the one question standing
 * between an invitation email and that person's own notification preferences.
 *
 * <p>{@link com.lagu.platform.event.event.EventInvitationNotifier} used to stage an invitation
 * email with an address and nothing else, because event-service had no way to turn that address
 * into a user id. The consequence: someone who had switched EVENT_INVITES emails off still got
 * this one, since notification-service's preference check is keyed on {@code recipientUserId} and
 * there wasn't one to key on. This client, used from {@link
 * com.lagu.platform.event.service.EventInvitationService#invite}, closes that.
 *
 * <p>iam-services has no {@code X-Internal-Service} trust model of its own — every other endpoint
 * there needs a user's JWT — so userservice's {@code InternalUserController} is gated on the
 * shared secret alone rather than the full header set event-service's own internal endpoints
 * check. Same header, though, so nothing new had to be threaded through configuration.
 */
@Component
@Slf4j
public class UserServiceClient {

    private final RestClient restClient;

    public UserServiceClient(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder loadBalancedRestClientBuilder,
            @Value("${platform.gateway.shared-secret:CHANGE_ME_INSECURE_DEFAULT_SECRET_ROTATE_IN_PROD}")
            String gatewaySharedSecret) {
        this.restClient = loadBalancedRestClientBuilder.clone()
                .baseUrl("http://user-service")
                .defaultHeader("X-Platform-Gateway-Secret", gatewaySharedSecret)
                .build();
    }

    /**
     * Best-effort, not fail-closed: unlike an authorization check, a lookup that errors or times
     * out just means the invitation email goes out un-attributed to a user id, exactly as it did
     * before this existed. It must never turn into a reason the invitation itself fails.
     */
    @SuppressWarnings("unchecked")
    public Optional<UUID> findUserIdByEmail(String email) {
        try {
            Map<String, Object> response = restClient.get()
                    .uri("/api/internal/users/lookup-by-email?email={email}", email)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
            if (response == null || !(response.get("data") instanceof Map<?, ?> m)) return Optional.empty();

            Object id = ((Map<String, Object>) m).get("id");
            return id == null ? Optional.empty() : Optional.of(UUID.fromString(id.toString()));
        } catch (Exception e) {
            // Debug, not warn: "no account for that email" is a 404 here and is the common case
            // for a phone-a-friend invitation, not a fault.
            log.debug("Could not resolve a user id for an invited email: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
