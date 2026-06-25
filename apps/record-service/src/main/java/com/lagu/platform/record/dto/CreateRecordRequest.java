package com.lagu.platform.record.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.Map;

@Data
public class CreateRecordRequest {

    @NotBlank
    private String objectType;

    @NotNull
    private Map<String, Object> data;

    private String status = "DRAFT";
}
