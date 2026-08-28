package com.lagu.platform.record.client;

import com.lagu.platform.common.exception.PlatformException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The change-approval gate's read side.
 *
 * <p>`requiresChangeApproval` has been settable on a workflow state, and surfaced in the admin
 * portal, while nothing on the write path ever read it — so a vendor editing a listing in a gated
 * state had the edit applied immediately and the review queue was bypassed entirely. This is the
 * lookup that closes that.
 *
 * <p><b>Cached, not asked per edit.</b> The answer is per object type, not per record, so one
 * cached entry covers every record of that type and a vendor edit costs no extra network hop. The
 * cost is a staleness window after an admin changes a workflow definition: there is no
 * definition-changed event to evict on today, so it is bounded by the cache TTL
 * (see RecordServiceConfig). Two minutes was chosen as short enough that an admin toggling the
 * flag sees it take effect while they are still looking at the screen.
 *
 * <p><b>Degrades to the last known rules, and only then fails closed.</b> Three behaviours, in
 * order of preference:
 *
 * <ol>
 *   <li>Cache hit — no call at all.</li>
 *   <li>Call fails but this object type has resolved successfully before — the last known gated set
 *       is reused. A workflow-service outage is then invisible to vendors editing existing types,
 *       which is the whole reason for caching rather than asking per edit.</li>
 *   <li>Call fails and the type has never resolved — the edit is refused with 503. Guessing here
 *       means either applying an edit that should have been reviewed (unrecoverable: it is live
 *       and unreviewed the moment it lands) or queueing one that did not need review. Refusing is
 *       a retry a minute later, and only ever affects a cold start.</li>
 * </ol>
 */
@Component
@Slf4j
public class WorkflowClient {

    public static final String GATED_STATES_CACHE = "workflow-gated-states";

    private final RestClient restClient;

    public WorkflowClient(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder loadBalancedRestClientBuilder,
            @Value("${platform.gateway.shared-secret:CHANGE_ME_INSECURE_DEFAULT_SECRET_ROTATE_IN_PROD}")
            String gatewaySharedSecret) {
        this.restClient = loadBalancedRestClientBuilder.clone()
                .baseUrl("http://workflow-service")
                .defaultHeader("X-Internal-Service", "record-service")
                .defaultHeader("X-Platform-Gateway-Secret", gatewaySharedSecret)
                .build();
    }

    /** @param workflowId null when the object type has no active workflow */
    public record GatedStates(UUID workflowId, Set<String> states) {
        public boolean holds(String stateName) {
            return stateName != null && states != null && states.contains(stateName);
        }
    }

    /**
     * Last successfully-resolved answer per cache key, outliving the cache entry on purpose: it is
     * the fallback for step 2 above, so it must survive the TTL expiring during an outage.
     * Bounded by the number of object types, which is small and admin-controlled.
     */
    private final Map<String, GatedStates> lastKnownGood = new java.util.concurrent.ConcurrentHashMap<>();

    @Cacheable(value = GATED_STATES_CACHE, key = "#objectType + ':' + #tenantId")
    @SuppressWarnings("unchecked")
    public GatedStates gatedStates(String objectType, UUID tenantId) {
        String key = objectType + ':' + tenantId;
        try {
            Map<String, Object> response = restClient.get()
                    .uri(b -> b.path("/internal/workflows/gated-states")
                            .queryParam("objectType", objectType)
                            .queryParam("tenantId", tenantId)
                            .build())
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});

            if (response == null || !(response.get("data") instanceof Map<?, ?> data)) {
                throw new IllegalStateException("unexpected response shape");
            }
            Map<String, Object> d = (Map<String, Object>) data;
            Object rawStates = d.get("states");
            Set<String> states = rawStates instanceof List<?> l
                    ? l.stream().map(String::valueOf).collect(java.util.stream.Collectors.toSet())
                    : Set.of();
            Object wf = d.get("workflowId");
            GatedStates resolved =
                    new GatedStates(wf != null ? UUID.fromString(wf.toString()) : null, states);
            lastKnownGood.put(key, resolved);
            return resolved;
        } catch (Exception e) {
            GatedStates stale = lastKnownGood.get(key);
            if (stale != null) {
                log.warn("Could not refresh gated workflow states for {} ({}) — reusing the last "
                        + "known set {}", objectType, e.getMessage(), stale.states());
                return stale;
            }
            // Never resolved this type: there is no safe guess, so refuse rather than risk
            // applying an edit that should have been reviewed.
            log.error("Could not resolve gated workflow states for {} and have no previous answer: {}",
                    objectType, e.getMessage());
            throw new PlatformException("APPROVAL_GATE_UNAVAILABLE",
                    "This change cannot be saved right now because the review rules could not be "
                            + "checked. Please try again in a moment.",
                    HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    /**
     * Files the vendor's edit as a change set for review instead of applying it.
     *
     * <p>Propagates on failure rather than swallowing: if this does not land, the edit is neither
     * applied nor queued, and reporting success would tell the vendor their change is awaiting
     * review when nothing exists to review.
     */
    @SuppressWarnings("unchecked")
    public UUID submitChangeSet(UUID recordId, UUID tenantId, String objectType, UUID workflowId,
                                Map<String, Object> originalData, Map<String, Object> proposedData,
                                UUID submittedBy) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("recordId", recordId);
        body.put("tenantId", tenantId);
        body.put("objectType", objectType);
        body.put("workflowId", workflowId);
        body.put("originalData", originalData);
        body.put("proposedData", proposedData);
        body.put("submittedBy", submittedBy);

        Map<String, Object> response = restClient.post()
                .uri("/api/v1/change-sets")
                .header("X-User-Id", submittedBy != null ? submittedBy.toString() : "")
                .body(body)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});

        if (response == null || !(response.get("data") instanceof Map<?, ?> data)) {
            throw new PlatformException("CHANGE_SET_SUBMIT_FAILED",
                    "Your change could not be sent for review. Please try again.",
                    HttpStatus.BAD_GATEWAY);
        }
        Object id = ((Map<String, Object>) data).get("id");
        return id != null ? UUID.fromString(id.toString()) : null;
    }
}
