package com.lagu.platform.automation.dto;

import com.lagu.platform.automation.domain.ActionDefinition;
import lombok.Builder;

import java.util.Map;
import java.util.UUID;

@Builder
public record ActionDefinitionResponse(
        UUID id,
        String actionType,
        int executionOrder,
        Map<String, Object> config,
        boolean continueOnFailure,
        boolean active) {

    public static ActionDefinitionResponse from(ActionDefinition a) {
        return ActionDefinitionResponse.builder()
                .id(a.getId())
                .actionType(a.getActionType())
                .executionOrder(a.getExecutionOrder())
                .config(a.getConfig())
                .continueOnFailure(a.isContinueOnFailure())
                .active(a.isActive())
                .build();
    }
}
