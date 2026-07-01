package com.lagu.platform.automation.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

@Component
@Slf4j
public class WorkflowServiceClient {

    private final RestClient restClient;

    public WorkflowServiceClient(RestClient.Builder loadBalancedRestClientBuilder) {
        this.restClient = loadBalancedRestClientBuilder.clone()
                .baseUrl("http://workflow-service")
                .defaultHeader("X-Internal-Service", "automation-service")
                .defaultHeader("X-User-Id", "00000000-0000-0000-0000-000000000001")
                .defaultHeader("X-User-Roles", "PLATFORM_ADMIN")
                .build();
    }

    /** Fetch pending approvals older than the specified minutes (for timeout escalation). */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> fetchTimedOutApprovals(int olderThanMinutes) {
        try {
            Map<String, Object> resp = restClient.get()
                    .uri("/api/v1/approvals/pending?olderThanMinutes={m}", olderThanMinutes)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
            if (resp == null) return List.of();
            Map<?, ?> data = (Map<?, ?>) resp.get("data");
            if (data == null) return List.of();
            return (List<Map<String, Object>>) data.get("content");
        } catch (Exception e) {
            log.warn("Could not fetch timed-out approvals: {}", e.getMessage());
            return List.of();
        }
    }
}
