package com.lagu.platform.event.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * What a listing type says may be read by whom.
 *
 * <p>Two callers, asking the same question at different rungs. The share preview asks what a
 * stranger may see, and is the only unauthenticated projection of an event. Every member read
 * asks what <em>this member</em> may see — which used to be nobody's question here at all:
 * {@code GET /events/{id}} returned the whole record to any membership row, and the section
 * audience was enforced only by the client that happened to be rendering it.
 */
@Component
@Slf4j
public class SchemaRegistryClient {

    /**
     * The audience ladder, least to most privileged. Mirrors events-ui's in
     * {@code lib/schema-form/audience.ts} — a reader sees the sections at or below their own rung
     * — and this must stay a mechanical port of it for the same reason the visibility evaluator
     * does: two implementations of "may this reader see this" drift, and the drift is silent in
     * the direction that matters.
     */
    private static final List<String> LADDER = List.of("PUBLIC", "GUEST", "HOST");

    public static final String PUBLIC_AUDIENCE = "PUBLIC";
    public static final String GUEST_AUDIENCE = "GUEST";
    public static final String HOST_AUDIENCE = "HOST";

    /**
     * What a section with no {@code audience} means. GUEST is what every section meant before
     * schema-registry had the column, and is the same default {@code sectionAudience} applies.
     */
    private static final String DEFAULT_AUDIENCE = GUEST_AUDIENCE;

    /**
     * How long a parsed schema is served before it is fetched again. Matches the staleness the
     * clients already accept — events-ui holds schemas for five minutes against schema-registry's
     * own Redis cache — so an admin publishing a change reaches a reader in the same window
     * whichever end resolves it.
     */
    private static final Duration TTL = Duration.ofMinutes(5);

    private final RestClient restClient;

    /**
     * Parsed schemas by objectType. In-process and unbounded-in-principle, which is fine at the
     * scale of "how many listing types exist": this is a handful of rows an admin authors, not
     * per-record state.
     */
    private final ConcurrentHashMap<String, Snapshot> cache = new ConcurrentHashMap<>();

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

    /** A type's field keys at some rung, and the schema version they were read from. */
    public record VisibleFields(Set<String> keys, Integer version) {
        public static VisibleFields none() {
            return new VisibleFields(Set.of(), null);
        }
    }

    /** One section, reduced to the two things a read filter needs of it. */
    private record Section(int rank, Set<String> keys) {}

    private record Snapshot(List<Section> sections, Integer version, Instant fetchedAt) {
        boolean isFresh() {
            return Duration.between(fetchedAt, Instant.now()).compareTo(TTL) < 0;
        }
    }

    /**
     * The field keys of every section a reader at {@code audience} may read.
     *
     * <p>Empty {@code Optional} means <em>could not be determined</em>, which is deliberately not
     * the same answer as "nothing is visible". A caller filtering a record has to tell those two
     * apart: serving a stripped record because a schema lookup failed would show a member an event
     * with no name and no date and no way to know why. What each caller does about it is theirs to
     * decide — see EventService, where a single read fails loudly and a list degrades.
     */
    public Optional<VisibleFields> visibleFields(String objectType, String audience) {
        Snapshot snapshot = snapshot(objectType);
        if (snapshot == null) return Optional.empty();

        int readerRank = rankOf(audience);
        Set<String> keys = snapshot.sections().stream()
                .filter(section -> section.rank() <= readerRank)
                .flatMap(section -> section.keys().stream())
                .collect(Collectors.toUnmodifiableSet());

        return Optional.of(new VisibleFields(keys, snapshot.version()));
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
     * <p>A section with no {@code audience} is deliberately <em>not</em> treated as PUBLIC. An
     * absent audience reads as GUEST, which is what every section meant before schema-registry
     * had the column, and a guest rung is already more than a stranger gets.
     */
    public VisibleFields publicFields(String objectType) {
        return visibleFields(objectType, PUBLIC_AUDIENCE).orElseGet(VisibleFields::none);
    }

    /**
     * Where an audience sits on the ladder.
     *
     * <p>An unrecognised value ranks below every section rather than above them, so a rung this
     * build has never heard of reads nothing instead of everything. Narrowing is the safe
     * direction to be wrong in — the same argument schema-registry's own audience backfill makes.
     */
    private static int rankOf(String audience) {
        int rank = LADDER.indexOf(audience);
        return rank >= 0 ? rank : -1;
    }

    /**
     * A parsed schema, fetched at most once per TTL per type.
     *
     * <p><strong>Stale on error.</strong> A failed refresh serves the last good parse rather than
     * discarding it, because the alternative is that a schema-registry blip decides what a member
     * may read. Only a cold cache and a failing registry together yield null, and that is the one
     * case a caller has to have a policy for.
     */
    private Snapshot snapshot(String objectType) {
        if (objectType == null || objectType.isBlank()) return null;

        Snapshot cached = cache.get(objectType);
        if (cached != null && cached.isFresh()) return cached;

        try {
            Snapshot fresh = fetch(objectType);
            cache.put(objectType, fresh);
            return fresh;
        } catch (Exception e) {
            log.warn("Could not resolve the schema for {} — {}: {}",
                    objectType,
                    cached != null ? "serving the last good copy" : "no cached copy to fall back to",
                    e.getMessage());
            return cached;
        }
    }

    /** Throws on anything it cannot parse, so the caller's stale-on-error path covers a malformed
     *  body as well as an unreachable service. */
    @SuppressWarnings("unchecked")
    private Snapshot fetch(String objectType) {
        Map<String, Object> response = restClient.get()
                .uri("/api/v1/listing-types/{objectType}/schema", objectType)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});

        Object data = response != null ? response.get("data") : null;
        if (!(data instanceof Map<?, ?> schema)) {
            throw new IllegalStateException("no schema object in the response");
        }

        Object sections = ((Map<String, Object>) schema).get("sections");
        if (!(sections instanceof List<?> list)) {
            throw new IllegalStateException("no sections in the schema");
        }

        List<Section> parsed = list.stream()
                .filter(Map.class::isInstance)
                .map(s -> (Map<String, Object>) s)
                .map(s -> new Section(
                        rankOf(s.get("audience") instanceof String a ? a : DEFAULT_AUDIENCE),
                        fieldKeys(s.get("fields"))))
                .toList();

        Integer version = Optional.ofNullable(((Map<String, Object>) schema).get("version"))
                .filter(Number.class::isInstance)
                .map(v -> ((Number) v).intValue())
                .orElse(null);

        return new Snapshot(parsed, version, Instant.now());
    }

    @SuppressWarnings("unchecked")
    private static Set<String> fieldKeys(Object fields) {
        if (!(fields instanceof List<?> list)) return Set.of();
        return list.stream()
                .filter(Map.class::isInstance)
                .map(f -> ((Map<String, Object>) f).get("key"))
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .collect(Collectors.toUnmodifiableSet());
    }
}
