package com.lagu.platform.record.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import lombok.Data;

/** Step 1 of a record file upload: what the client intends to upload. */
@Data
public class FileUploadUrlRequest {

    @NotBlank
    private String fileName;

    @NotBlank
    private String contentType;

    @Positive
    private long sizeBytes;
}
