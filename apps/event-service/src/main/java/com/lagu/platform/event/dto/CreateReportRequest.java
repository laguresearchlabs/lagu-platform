package com.lagu.platform.event.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateReportRequest {

    /** SPAM | INAPPROPRIATE | OFF_TOPIC | OTHER */
    @NotBlank
    private String reason;

    @Size(max = 2000)
    private String details;
}
