package com.lagu.platform.booking.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

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
                .defaultHeader("X-Internal-Service", "booking-service")
                .defaultHeader("X-Platform-Gateway-Secret", gatewaySharedSecret)
                .build();
    }

    public record ListingInfo(UUID recordId, UUID tenantId, String objectType, String status,
                              String verificationTier) {}

    /**
     * Fails closed (empty) on any lookup failure or non-existent/unpublished listing — a missing
     * listing must not let an Inquiry be created against it.
     */
    @SuppressWarnings("unchecked")
    public Optional<ListingInfo> getSnapshot(UUID recordId) {
        try {
            Map<String, Object> response = restClient.get()
                    .uri("/api/v1/listings/{recordId}/snapshot", recordId)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
            if (response == null) return Optional.empty();
            Object data = response.get("data");
            if (!(data instanceof Map<?, ?> m)) return Optional.empty();
            Map<String, Object> snap = (Map<String, Object>) m;
            return Optional.of(new ListingInfo(
                    UUID.fromString(snap.get("recordId").toString()),
                    UUID.fromString(snap.get("tenantId").toString()),
                    (String) snap.get("objectType"),
                    (String) snap.get("status"),
                    (String) snap.get("verificationTier")));
        } catch (Exception e) {
            log.warn("Failed to fetch listing snapshot for {}: {}", recordId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Atomically claims recordId's availability on {@code date} using bookingRef as the claim
     * key. Unlike the fail-closed lookups above, a request failure here is NOT swallowed to
     * {@code false} — that would be indistinguishable from a legitimate "lost the race" result,
     * and Confirm must not silently proceed as if nothing happened. Network/5xx failures
     * propagate as a RuntimeException so the caller surfaces a 502/503, not a misleading 409.
     */
    @SuppressWarnings("unchecked")
    public boolean bookSlot(UUID recordId, LocalDate date, UUID bookingRef) {
        Map<String, Object> response = restClient.post()
                .uri("/internal/listings/{recordId}/availability/{date}/book", recordId, date)
                .body(Map.of("bookingRef", bookingRef))
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        Map<String, Object> data = (Map<String, Object>) response.get("data");
        return Boolean.TRUE.equals(data.get("claimed"));
    }

    /**
     * Hands a listing's review aggregate to listing-service, which puts it on the snapshot and
     * republishes so search-service reindexes it. That is the only route a rating has onto a
     * marketplace card: the alternative is the results grid fetching one per tile.
     *
     * <p><b>Best-effort, unlike the slot claims below.</b> A review is already saved and durable by
     * the time this runs; the aggregate is a derived read model, and losing one push costs a card
     * showing a slightly stale average until the next review or reconcile. Failing the consumer's
     * review because a downstream service was briefly unavailable would trade a real write for a
     * cosmetic one.
     *
     * @param average null when nothing verified has been said, which is not the same as zero
     */
    public void applyRating(UUID recordId, java.math.BigDecimal average, long reviewCount) {
        try {
            Map<String, Object> body = new java.util.HashMap<>();
            body.put("average", average);          // HashMap, because Map.of rejects a null value
            body.put("reviewCount", reviewCount);
            restClient.post()
                    .uri("/internal/listings/{recordId}/rating", recordId)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            log.warn("Could not push rating for listing {} — the card will show a stale average "
                    + "until the next review: {}", recordId, e.getMessage());
        }
    }

    /**
     * Inverse of {@link #bookSlot} — same fail-loud rationale: a Cancel of a CONFIRMED booking
     * must not silently report success while the vendor's slot stays wrongly marked BOOKED.
     */
    @SuppressWarnings("unchecked")
    public boolean releaseSlot(UUID recordId, LocalDate date, UUID bookingRef) {
        Map<String, Object> response = restClient.post()
                .uri("/internal/listings/{recordId}/availability/{date}/release", recordId, date)
                .body(Map.of("bookingRef", bookingRef))
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        Map<String, Object> data = (Map<String, Object>) response.get("data");
        return Boolean.TRUE.equals(data.get("released"));
    }
}
