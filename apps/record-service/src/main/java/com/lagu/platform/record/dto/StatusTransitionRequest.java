package com.lagu.platform.record.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class StatusTransitionRequest {

    @NotBlank
    private String trigger;

    private String comment;
}
