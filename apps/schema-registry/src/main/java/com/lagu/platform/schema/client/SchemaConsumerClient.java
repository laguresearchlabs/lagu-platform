package com.lagu.platform.schema.client;

import com.lagu.platform.common.dto.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * Asks the services that cache schemas which version they are actually using.
 *
 * <p>Defect 6: publishing is fire-and-forget. The event goes to the outbox, Kafka relays it, each
 * consumer evicts its cache — and nothing reports back. When an eviction is missed, validation runs
 * against the previous version until the ten-minute TTL expires, so an admin who publishes and
 * immediately tests sees the old schema and concludes the publish failed. It did not; they are
 * looking at a stale reader, and there was no way to tell the two apart.
 *
 * <p>Only record-service is asked, because it is the only consumer that can answer. search-service
 * caches a flattened field list and throws the version away, so it has nothing to report without
 * restructuring what it caches — and its cache affects which fields are indexed, not whether a
 * record validates, which is the symptom this exists to explain. Worth revisiting if search
 * staleness ever becomes visible to anyone.
 *
 * <p>Every failure here is reported, never thrown. This endpoint exists to explain a confusing
 * situation; turning "I could not reach record-service" into a 500 on the publish screen would make
 * it more confusing, not less.
 */
@Slf4j
@Component
public class SchemaConsumerClient {

    private final RestClient recordServiceRestClient;

    public SchemaConsumerClient(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder loadBalancedRestClientBuilder,
            @Value("${platform.gateway.shared-secret:CHANGE_ME_INSECURE_DEFAULT_SECRET_ROTATE_IN_PROD}")
            String gatewaySharedSecret) {
        this.recordServiceRestClient = loadBalancedRestClientBuilder.clone()
                .baseUrl("http://record-service")
                .defaultHeader("X-Internal-Service", "schema-registry")
                .defaultHeader("X-Platform-Gateway-Secret", gatewaySharedSecret)
                .build();
    }

    /**
     * @param cachedVersion the version the consumer holds, or null when it holds nothing. Null is
     *                      not staleness: an empty cache means the next read fetches the current
     *                      schema, which is the same outcome a successful eviction produces.
     */
    public record ConsumerState(String service, Integer cachedVersion, boolean inSync, String note) {

        static ConsumerState of(String service, Integer cachedVersion, int currentVersion) {
            if (cachedVersion == null) {
                return new ConsumerState(service, null, true, "nothing cached; will read the current schema");
            }
            boolean synced = cachedVersion >= currentVersion;
            return new ConsumerState(service, cachedVersion, synced,
                    synced ? "using the published version"
                           : "still using v" + cachedVersion + "; will catch up within the cache window");
        }

        static ConsumerState unreachable(String service, String reason) {
            // Not "in sync". An unreachable consumer is an unknown one, and saying otherwise would
            // put a green tick next to a service nobody has heard from.
            return new ConsumerState(service, null, false, "could not be reached: " + reason);
        }
    }

    public List<ConsumerState> statesFor(String listingType, int currentVersion) {
        return List.of(recordService(listingType, currentVersion));
    }

    private ConsumerState recordService(String listingType, int currentVersion) {
        try {
            ApiResponse<CachedSchemaVersion> response = recordServiceRestClient.get()
                    .uri("/internal/schema-cache/{objectType}", listingType)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});

            if (response == null || !response.isSuccess() || response.getData() == null) {
                return ConsumerState.unreachable("record-service", "unexpected response");
            }
            return ConsumerState.of("record-service", response.getData().version(), currentVersion);
        } catch (Exception e) {
            log.warn("Could not read record-service schema cache for {}: {}", listingType, e.toString());
            return ConsumerState.unreachable("record-service", e.getClass().getSimpleName());
        }
    }

    public record CachedSchemaVersion(String objectType, Integer version) {}
}
