package com.lagu.platform.workflow.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class WorkflowStateRequest {

    @NotBlank
    private String name;

    @NotBlank
    private String label;

    private String  description;
    private boolean terminal     = false;
    private int     displayOrder = 0;
    private String  color;

    /**
     * Whether edits to a record sitting in this state are held for admin review instead of being
     * applied. The column and the entity field have existed since the workflow schema was written;
     * this request never carried it, so it could only ever be false through the API and the gate
     * had nothing to fire on.
     */
    private boolean requiresChangeApproval = false;
}
