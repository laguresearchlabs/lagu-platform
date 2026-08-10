package com.lagu.platform.record.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/**
 * Step 1 result. PUT the bytes to {@code uploadUrl} with {@code Content-Type} set to
 * {@code contentType} — it is bound into the signature — then confirm with {@code key}.
 */
@Data
@Builder
public class FileUploadUrlResponse {
    private String  uploadUrl;
    private String  key;
    private String  contentType;
    private Instant expiresAt;
}
