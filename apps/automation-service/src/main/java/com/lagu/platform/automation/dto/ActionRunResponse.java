package com.lagu.platform.automation.dto;

import com.lagu.platform.automation.domain.ActionRun;
import lombok.Builder;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Builder
public record ActionRunResponse(
        UUID id,
        UUID actionId,
        String actionType,
        String status,
        String errorMessage,
        OffsetDateTime executedAt) {

    public static ActionRunResponse from(ActionRun ar) {
        return ActionRunResponse.builder()
                .id(ar.getId())
                .actionId(ar.getAction() != null ? ar.getAction().getId() : null)
                .actionType(ar.getActionType())
                .status(ar.getStatus())
                .errorMessage(ar.getErrorMessage())
                .executedAt(ar.getExecutedAt().atOffset(ZoneOffset.UTC))
                .build();
    }
}
