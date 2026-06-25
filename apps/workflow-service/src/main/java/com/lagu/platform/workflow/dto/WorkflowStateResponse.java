package com.lagu.platform.workflow.dto;

import lombok.Builder;
import lombok.Data;

import java.util.UUID;

@Data @Builder
public class WorkflowStateResponse {
    private UUID    id;
    private String  name;
    private String  label;
    private String  description;
    private boolean terminal;
    private int     displayOrder;
    private String  color;
}
