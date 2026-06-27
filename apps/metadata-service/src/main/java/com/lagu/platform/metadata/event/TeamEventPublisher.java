package com.lagu.platform.metadata.event;

import com.lagu.platform.events.PlatformTopics;
import com.lagu.platform.events.TeamEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class TeamEventPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public void publishMemberAdded(UUID orgId, UUID groupId, UUID userId, String roleName) {
        publish(orgId.toString(), TeamEvent.builder()
                .eventType("MEMBER_ADDED")
                .orgId(orgId).groupId(groupId).userId(userId).roleName(roleName)
                .occurredAt(Instant.now())
                .build());
    }

    public void publishMemberRemoved(UUID orgId, UUID groupId, UUID userId) {
        publish(orgId.toString(), TeamEvent.builder()
                .eventType("MEMBER_REMOVED")
                .orgId(orgId).groupId(groupId).userId(userId)
                .occurredAt(Instant.now())
                .build());
    }

    public void publishRoleAssigned(UUID orgId, UUID groupId, UUID userId, String roleName) {
        publish(orgId.toString(), TeamEvent.builder()
                .eventType("ROLE_ASSIGNED")
                .orgId(orgId).groupId(groupId).userId(userId).roleName(roleName)
                .occurredAt(Instant.now())
                .build());
    }

    private void publish(String key, TeamEvent event) {
        kafkaTemplate.send(PlatformTopics.TEAM_EVENTS, key, event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish TeamEvent type={}", event.getEventType(), ex);
                    }
                });
    }
}
