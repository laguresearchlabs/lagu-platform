package com.lagu.platform.automation.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.lagu.platform.automation.domain.AutomationRun;
import lombok.Builder;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AutomationRunResponse(
        UUID id,
        UUID triggerId,
        UUID recordId,
        UUID tenantId,
        String eventType,
        String status,
        String errorMessage,
        OffsetDateTime startedAt,
        OffsetDateTime completedAt,
        List<ActionRunResponse> actionRuns) {

    /**
     * List view — omits actionRuns rather than fetch-joining them per row in a paged query;
     * use {@link #from} for the single-run detail view where the child rows are wanted.
     */
    public static AutomationRunResponse summary(AutomationRun r) {
        return baseBuilder(r).build();
    }

    public static AutomationRunResponse from(AutomationRun r) {
        return baseBuilder(r)
                .actionRuns(r.getActionRuns() == null ? List.of()
                        : r.getActionRuns().stream().map(ActionRunResponse::from).toList())
                .build();
    }

    private static AutomationRunResponseBuilder baseBuilder(AutomationRun r) {
        return AutomationRunResponse.builder()
                .id(r.getId())
                .triggerId(r.getTrigger() != null ? r.getTrigger().getId() : null)
                .recordId(r.getRecordId())
                .tenantId(r.getTenantId())
                .eventType(r.getEventType())
                .status(r.getStatus())
                .errorMessage(r.getErrorMessage())
                .startedAt(r.getStartedAt().atOffset(ZoneOffset.UTC))
                .completedAt(r.getCompletedAt() != null ? r.getCompletedAt().atOffset(ZoneOffset.UTC) : null);
    }
}
