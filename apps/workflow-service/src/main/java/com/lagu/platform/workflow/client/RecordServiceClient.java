package com.lagu.platform.workflow.client;

import com.lagu.platform.common.exception.PlatformException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@Slf4j
public class RecordServiceClient {

    /** Matches the "message" field of record-service's {success,error{code,message}} envelope. */
    private static final Pattern ERROR_MESSAGE = Pattern.compile("\"message\"\\s*:\\s*\"([^\"]*)\"");

    private final RestClient restClient;

    public RecordServiceClient(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder loadBalancedRestClientBuilder,
            @Value("${platform.gateway.shared-secret:CHANGE_ME_INSECURE_DEFAULT_SECRET_ROTATE_IN_PROD}")
            String gatewaySharedSecret) {
        // Identity: SVC_WORKFLOW_SERVICE via X-Internal-Service. DefaultPermissionEvaluator
        // grants internal callers RECORD CREATE/UPDATE/TRANSITION; tenancy is still enforced
        // downstream from the X-Tenant-Id forwarded per request.
        this.restClient = loadBalancedRestClientBuilder.clone()
                .baseUrl("http://record-service")
                .defaultHeader("X-Internal-Service", "workflow-service")
                .defaultHeader("X-Platform-Gateway-Secret", gatewaySharedSecret)
                .build();
    }

    /**
     * Replaces a record's data with the approved change set's payload.
     *
     * Deliberately propagates instead of logging-and-returning-null like the sibling clients in
     * vendor/automation-service: this call *is* the effect of approving a change set. Swallowing
     * a failure here would mark the change set APPROVED while the record kept its old values —
     * precisely the silent data loss this client exists to end. The caller runs inside a
     * transaction and lets the failure roll the review back so the change set stays PENDING and
     * can be retried.
     *
     * PUT rather than PATCH because a change set carries the record's complete intended state:
     * ChangeSetDiff treats a key absent from proposedData as a removal, and a merge would
     * silently keep fields the reviewer approved dropping.
     *
     * @param actingUserId the reviewing admin, so record-service attributes the audit entry to
     *                     the approver rather than to the service account.
     */
    public void applyApprovedData(UUID recordId, UUID tenantId, UUID actingUserId,
                                  Map<String, Object> data) {
        try {
            restClient.put()
                    .uri("/api/v1/records/{id}", recordId)
                    .header("X-Tenant-Id", tenantId != null ? tenantId.toString() : "")
                    .header("X-User-Id", actingUserId != null ? actingUserId.toString() : "")
                    .body(Map.of("data", data))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            // Surface record-service's own message as a mapped PlatformException. A rejected
            // apply is nearly always a validation failure the reviewer must see and act on — as
            // a bare RuntimeException it reached the client as 500 "An unexpected error
            // occurred", which tells them neither that nothing was applied nor what to fix.
            log.error("Failed to apply change set data to record {} (org {}): {} {}",
                    recordId, tenantId, e.getStatusCode(), e.getResponseBodyAsString());
            throw new PlatformException(
                    "CHANGE_SET_APPLY_FAILED",
                    "The approved change could not be applied to record " + recordId
                            + ", so it was left pending. record-service reported: "
                            + extractMessage(e.getResponseBodyAsString()),
                    HttpStatus.UNPROCESSABLE_ENTITY);
        } catch (Exception e) {
            log.error("Failed to apply change set data to record {} (org {}): {}",
                    recordId, tenantId, e.getMessage());
            throw new PlatformException(
                    "CHANGE_SET_APPLY_FAILED",
                    "The approved change could not be applied to record " + recordId
                            + "; it was left pending and can be retried. Cause: " + e.getMessage(),
                    HttpStatus.BAD_GATEWAY);
        }
    }

    /** Pulls the human-readable text out of record-service's error envelope, falling back to the
     *  raw body so nothing is lost when the shape is unexpected. */
    private String extractMessage(String body) {
        if (body == null || body.isBlank()) return "(no response body)";
        Matcher m = ERROR_MESSAGE.matcher(body);
        return m.find() ? m.group(1) : body;
    }
}
