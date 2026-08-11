package com.lagu.platform.listing.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.Map;

@Component
@Slf4j
public class SchemaRegistryClient {

    private final RestClient restClient;

    public SchemaRegistryClient(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder loadBalancedRestClientBuilder,
            @Value("${platform.gateway.shared-secret:CHANGE_ME_INSECURE_DEFAULT_SECRET_ROTATE_IN_PROD}")
            String gatewaySharedSecret) {
        this.restClient = loadBalancedRestClientBuilder.clone()
                .baseUrl("http://schema-registry")
                .defaultHeader("X-Internal-Service", "listing-service")
                .defaultHeader("X-Platform-Gateway-Secret", gatewaySharedSecret)
                .build();
    }

    public record ListingTypeFlags(boolean publishable, boolean consumerSearchable) {}

    /**
     * Whether this objectType is a publishable/searchable listing at all — replaces a
     * hardcoded allowlist that used to live in ListingSnapshotService and silently drifted out
     * of sync with schema-registry's own ListingTypeDefinition.publishable/consumerSearchable
     * flags (e.g. event record types were never added to it).
     *
     * <p><b>A "no" and a "don't know" are not the same thing.</b> Both still fail closed —
     * nothing is ever published without an affirmative yes, because this gates whether a
     * record's data becomes a public, cross-org snapshot. But they differ in what happens next:
     *
     * <ul>
     *   <li><b>schema-registry answered</b> — including 404, meaning no such listing type —
     *       is a definitive not-publishable. The caller skips, permanently and correctly.</li>
     *   <li><b>schema-registry could not be reached</b> is not an answer. This throws, so the
     *       Kafka consumers retry and eventually dead-letter, and a manual publish returns an
     *       error to the admin who asked for it.</li>
     * </ul>
     *
     * <p>Previously every failure was swallowed into {@code (false, false)}. A transient
     * registry outage therefore made an approved listing silently never reach consumers, with a
     * single WARN as the only trace and no retry — indistinguishable, to every caller, from a
     * listing type that was deliberately not publishable. {@code RecordServiceClient.getRecord}
     * already documents this exact reasoning for the same consumer.
     *
     * @throws ListingTypeLookupException when the flags could not be determined
     */
    @SuppressWarnings("unchecked")
    public ListingTypeFlags getFlags(String objectType) {
        Map<String, Object> response;
        try {
            response = restClient.get()
                    .uri("/api/v1/listing-types/{name}", objectType.toUpperCase())
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                // A real answer: schema-registry has no such listing type, so it is not one.
                log.debug("No listing type definition for {} — treating as not publishable", objectType);
                return new ListingTypeFlags(false, false);
            }
            throw new ListingTypeLookupException(objectType, e);
        } catch (Exception e) {
            // Connection refused, no load-balancer instance, timeout — no answer was obtained.
            throw new ListingTypeLookupException(objectType, e);
        }

        if (response == null || !(response.get("data") instanceof Map<?, ?> m)) {
            // A 2xx whose body is not the expected envelope means schema-registry's contract
            // moved. Treating that as "not publishable" would quietly stop every listing from
            // publishing platform-wide, so it is loud instead.
            throw new ListingTypeLookupException(objectType,
                    new IllegalStateException("Unexpected response shape: " + response));
        }

        Map<String, Object> def = (Map<String, Object>) m;
        return new ListingTypeFlags(
                Boolean.TRUE.equals(def.get("publishable")),
                Boolean.TRUE.equals(def.get("consumerSearchable")));
    }

    /** Thrown when schema-registry could not tell us whether a listing type is publishable.
     *  Distinct from a definitive "not publishable" so callers can retry rather than skip. */
    public static class ListingTypeLookupException extends RuntimeException {
        public ListingTypeLookupException(String objectType, Throwable cause) {
            super("Could not determine listing type flags for " + objectType
                    + " (schema-registry unreachable or returned an unusable response); "
                    + "not publishing rather than guessing", cause);
        }
    }
}
