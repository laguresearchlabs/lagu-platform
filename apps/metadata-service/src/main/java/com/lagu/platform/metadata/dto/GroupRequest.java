package com.lagu.platform.metadata.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class GroupRequest {

    @NotBlank
    @Size(max = 100)
    private String name;

    private String description;
}
