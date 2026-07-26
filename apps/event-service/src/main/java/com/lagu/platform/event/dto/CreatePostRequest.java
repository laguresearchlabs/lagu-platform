package com.lagu.platform.event.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;
import java.util.UUID;

@Data
public class CreatePostRequest {

    @NotBlank
    @Size(max = 2000)
    private String content;

    private List<UUID> imageIds;
}
