package com.lagu.platform.automation.dto;

import com.lagu.platform.automation.domain.TriggerDefinition;
import lombok.Builder;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Builder
public record TriggerDefinitionResponse(
        UUID id,
        UUID tenantId,
        String name,
        String label,
        String description,
        String eventType,
        String objectType,
        List<Map<String, Object>> conditions,
        boolean active,
        List<ActionDefinitionResponse> actions,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public static TriggerDefinitionResponse from(TriggerDefinition t) {
        return TriggerDefinitionResponse.builder()
                .id(t.getId())
                .tenantId(t.getTenantId())
                .name(t.getName())
                .label(t.getLabel())
                .description(t.getDescription())
                .eventType(t.getEventType())
                .objectType(t.getObjectType())
                .conditions(t.getConditions())
                .active(t.isActive())
                .actions(t.getActions() == null ? List.of()
                        : t.getActions().stream().map(ActionDefinitionResponse::from).toList())
                .createdAt(t.getCreatedAt().atOffset(ZoneOffset.UTC))
                .updatedAt(t.getUpdatedAt().atOffset(ZoneOffset.UTC))
                .build();
    }
}
