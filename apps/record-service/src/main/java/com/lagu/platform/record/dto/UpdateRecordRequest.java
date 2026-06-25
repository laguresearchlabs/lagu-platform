package com.lagu.platform.record.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.Map;

@Data
public class UpdateRecordRequest {

    @NotNull
    private Map<String, Object> data;
}
