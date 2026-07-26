package com.lagu.platform.event.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.Map;

@Data
public class CreateEventRequest {

    /** BIRTHDAY_EVENT | WEDDING_EVENT | ... — schema-driven, see schema-registry. */
    @NotBlank
    private String objectType;

    @NotNull
    private Map<String, Object> data;
}
