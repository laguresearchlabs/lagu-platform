package com.lagu.platform.event.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * What a listing type says may be read by whom.
 *
 * <p>Exists for exactly one caller: the share preview, which is the only unauthenticated
 * projection of an event and therefore the only place this service has to decide what a
 * stranger may see. Every other read path answers that question with an EventMember role check
 * instead, and does not need the schema at all.
 */
@Component
@Slf4j
public class SchemaRegistryClient {

    /**
     * The widest audience a section can carry. Mirrors events-ui's ladder in
     * {@code lib/schema-form/audience.ts} — PUBLIC &lt; GUEST &lt; HOST — of which only the
     * widest rung is safe to serve without a session.
     */
    private static final String PUBLIC_AUDIENCE = "PUBLIC";

    private final RestClient restClient;

    public SchemaRegistryClient(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder loadBalancedRestClientBuilder,
            @Value("${platform.gateway.shared-secret:CHANGE_ME_INSECURE_DEFAULT_SECRET_ROTATE_IN_PROD}")
            String gatewaySharedSecret) {
        this.restClient = loadBalancedRestClientBuilder.clone()
                .baseUrl("http://schema-registry")
                .defaultHeader("X-Internal-Service", "event-service")
                .defaultHeader("X-Platform-Gateway-Secret", gatewaySharedSecret)
                .build();
    }

    /** A type's public field keys and the schema version they were read from. */
    public record PublicFields(Set<String> keys, Integer version) {
        static PublicFields none() {
            return new PublicFields(Set.of(), null);
        }
    }

    /**
     * The field keys of every PUBLIC-audience section of a type.
     *
     * <p><strong>Fails closed, and that is the whole point of the method.</strong> Any failure —
     * schema-registry down, a 4xx, a malformed body, a section with no audience at all — yields
     * an empty set, so the share preview degrades to the hand-picked scalars it has always
     * carried. The opposite default would publish an entire event record, including a host's
     * budget, to anyone holding a forwarded link, the first time a deploy raced a restart.
     *
     * <p>A section with no {@code audience} is deliberately <em>not</em> treated as PUBLIC.
     * events-ui reads an absent audience as GUEST (see {@code sectionAudience}), which is what
     * every section meant before schema-registry had the column, and a guest rung is already
     * more than a stranger gets.
     */
    @SuppressWarnings("unchecked")
    public PublicFields publicFields(String objectType) {
        if (objectType == null || objectType.isBlank()) return PublicFields.none();

        try {
            Map<String, Object> response = restClient.get()
                    .uri("/api/v1/listing-types/{objectType}/schema", objectType)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});

            Object data = response != null ? response.get("data") : null;
            if (!(data instanceof Map<?, ?> schema)) return PublicFields.none();

            Object sections = ((Map<String, Object>) schema).get("sections");
            if (!(sections instanceof List<?> list)) return PublicFields.none();

            Set<String> keys = list.stream()
                    .filter(Map.class::isInstance)
                    .map(s -> (Map<String, Object>) s)
                    .filter(s -> PUBLIC_AUDIENCE.equals(s.get("audience")))
                    .map(s -> s.get("fields"))
                    .filter(List.class::isInstance)
                    .flatMap(f -> ((List<?>) f).stream())
                    .filter(Map.class::isInstance)
                    .map(f -> ((Map<String, Object>) f).get("key"))
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .collect(Collectors.toUnmodifiableSet());

            Integer version = Optional.ofNullable(((Map<String, Object>) schema).get("version"))
                    .filter(Number.class::isInstance)
                    .map(v -> ((Number) v).intValue())
                    .orElse(null);

            return new PublicFields(keys, version);
        } catch (Exception e) {
            // Warn, not error: a share card falling back to its title and date is a degraded
            // preview, not a broken one, and this endpoint is hit by crawlers in bursts.
            log.warn("Could not resolve public fields for {} — share preview will carry none: {}",
                    objectType, e.getMessage());
            return PublicFields.none();
        }
    }
}
