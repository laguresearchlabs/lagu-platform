package com.lagu.platform.event.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.Map;

@Data
public class UpdateEventRequest {

    @NotNull
    private Map<String, Object> data;
}
