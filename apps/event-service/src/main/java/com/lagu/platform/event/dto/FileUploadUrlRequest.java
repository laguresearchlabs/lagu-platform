package com.lagu.platform.event.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import lombok.Data;

/**
 * Step 1 of an event photo upload: what the client intends to upload.
 *
 * <p>Every field is a client declaration. They are checked so an obviously bad upload is refused
 * before a URL is minted, but none are trusted — the object is re-verified against its actual
 * stored bytes at confirm time.
 */
@Data
public class FileUploadUrlRequest {

    @NotBlank
    private String fileName;

    @NotBlank
    private String contentType;

    @Positive
    private long sizeBytes;
}
