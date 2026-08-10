package com.lagu.platform.document.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/**
 * Step 1 result. The client PUTs the bytes to {@code uploadUrl} with {@code Content-Type} set
 * to {@code contentType} — that header is bound into the signature, so anything else is
 * rejected by the bucket — then calls confirm with {@code key}.
 */
@Data
@Builder
public class UploadUrlResponse {
    private String  uploadUrl;
    private String  key;
    private String  contentType;
    private Instant expiresAt;
}
