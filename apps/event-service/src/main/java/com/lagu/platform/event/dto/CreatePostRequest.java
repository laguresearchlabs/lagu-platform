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

    // Deliberately no image field. A post's photos are uploaded to its own record gallery AFTER
    // the post exists — the storage key is scoped to the record id, so there is nothing to attach
    // at creation. record-service also strips client-supplied gallery values on write, so a field
    // here would silently do nothing.
}
