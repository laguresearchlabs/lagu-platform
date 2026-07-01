package com.lagu.platform.vendor.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.UUID;

@Component
@Slf4j
public class IamServiceClient {

    private final RestClient restClient;

    // "iam-service" is user-service (iam-services repo, Eureka app name "user-service") —
    // org association lives on PUT /api/v1/users/{userId}/platform-org/{orgId}.
    public IamServiceClient(RestClient.Builder loadBalancedRestClientBuilder) {
        this.restClient = loadBalancedRestClientBuilder.clone()
                .baseUrl("http://user-service")
                .defaultHeader("X-Internal-Service", "vendor-service")
                .build();
    }

    /** Associates a platform orgId with the user. The auth token is forwarded from the request. */
    public void associateOrgWithUser(UUID userId, UUID orgId, String bearerToken) {
        try {
            restClient.put()
                    .uri("/api/v1/users/{userId}/platform-org/{orgId}", userId, orgId)
                    .header("Authorization", bearerToken)
                    .retrieve()
                    .toBodilessEntity();
            log.info("Associated user {} with org {}", userId, orgId);
        } catch (Exception e) {
            log.error("Failed to associate org {} with user {}: {}", orgId, userId, e.getMessage());
        }
    }
}
