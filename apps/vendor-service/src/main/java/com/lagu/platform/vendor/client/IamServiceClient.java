package com.lagu.platform.vendor.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.UUID;

@Component
@Slf4j
public class IamServiceClient {

    private final RestClient restClient;

    public IamServiceClient(
            @Value("${platform.iam-service.url:http://iam-service:8080}") String url) {
        this.restClient = RestClient.builder()
                .baseUrl(url)
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
