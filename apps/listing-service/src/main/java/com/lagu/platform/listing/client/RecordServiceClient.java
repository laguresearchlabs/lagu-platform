package com.lagu.platform.listing.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
@Slf4j
public class RecordServiceClient {

    private final RestClient restClient;

    public RecordServiceClient(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder loadBalancedRestClientBuilder,
            @Value("${platform.gateway.shared-secret:CHANGE_ME_INSECURE_DEFAULT_SECRET_ROTATE_IN_PROD}")
            String gatewaySharedSecret) {
        this.restClient = loadBalancedRestClientBuilder.clone()
                .baseUrl("http://record-service")
                .defaultHeader("X-Internal-Service", "listing-service")
                .defaultHeader("X-Platform-Gateway-Secret", gatewaySharedSecret)
                .build();
    }

    /**
     * Signs media keys in bulk, so a results page costs one call rather than one per listing.
     *
     * <p>record-service does the signing because its bucket credential is the one scoped to the
     * {@code record/} prefix; widening this service's IAM to save a hop would dissolve that
     * boundary. Each key travels with the record it belongs to, which is what lets the other side
     * verify it rather than trusting it.
     *
     * @param keys the keys to sign, each paired with its owning record
     * @return signed URL per key; a key that could not be signed is simply absent
     */
    /**
     * One key to sign, paired with the record it belongs to.
     *
     * <p>A pair rather than a map entry because the relationship is many-to-one: a gallery
     * carousel signs a dozen keys that all belong to the same record, and every one of them must
     * still travel with that record id — it is what the far side checks the key against before
     * signing. Keying a map by record id could only ever carry one.
     */
    public record MediaKey(UUID recordId, String key) {}

    @SuppressWarnings("unchecked")
    public Map<String, String> signMediaKeys(Collection<MediaKey> keys) {
        if (keys == null || keys.isEmpty()) return Map.of();

        List<Map<String, String>> items = keys.stream()
                .filter(k -> k.recordId() != null && k.key() != null)
                .distinct()
                .map(k -> Map.of(
                        "recordId", k.recordId().toString(),
                        "key", k.key()))
                .toList();
        if (items.isEmpty()) return Map.of();

        Map<String, Object> envelope = restClient.post()
                .uri("/internal/records/media/sign")
                .body(Map.<String, Object>of("items", items))
                .retrieve()
                .body(new ParameterizedTypeReference<Map<String, Object>>() {});

        if (envelope == null || !(envelope.get("data") instanceof Map)) {
            // Media is decoration on a results page; a listing without a cover photo still
            // renders. Failing the whole search because signing was unavailable would turn a
            // cosmetic dependency into an outage.
            log.warn("Unexpected response signing {} media key(s); returning none",
                    items.size());
            return Map.of();
        }
        return (Map<String, String>) envelope.get("data");
    }

    /**
     * Returns the record itself, unwrapped from record-service's {@code ApiResponse} envelope.
     *
     * The unwrap is the whole point of this method existing: the endpoint answers
     * {@code {success, message, data:{id, data:{...fields}, status, ...}}}, so a caller handed the
     * raw body sees a map whose {@code data} key is the *record*, not the record's fields. That is
     * exactly the mistake this method previously invited — every published listing snapshot was
     * stored double-nested and rendered as "Unnamed Service" with no image in the consumer app,
     * while {@code verificationTier} (a record field, absent from the envelope) silently defaulted
     * to NONE so search boost never applied.
     *
     * Propagates HTTP failures rather than returning null: the Kafka consumer relies on the
     * exception to trigger retry/DLT, and a swallowed failure here would silently skip a
     * snapshot publication. Envelope drift is treated the same way — loudly — rather than
     * degrading to a half-populated snapshot.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getRecord(UUID recordId, UUID tenantId) {
        Map<String, Object> envelope = restClient.get()
                .uri("/api/v1/records/{id}", recordId)
                .header("X-Tenant-Id", tenantId != null ? tenantId.toString() : "")
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});

        if (envelope == null) return null;

        Object record = envelope.get("data");
        if (!(record instanceof Map)) {
            throw new IllegalStateException(
                    "Unexpected response shape from record-service for record " + recordId
                            + ": expected an ApiResponse with a 'data' object, got "
                            + (record == null ? "null" : record.getClass().getSimpleName()));
        }
        return (Map<String, Object>) record;
    }
}
