package com.lagu.platform.storage;

import java.time.Instant;

/**
 * A URL the client PUTs bytes to, plus the key to hand back at confirm time.
 *
 * @param url         presigned PUT target; valid until {@code expiresAt}
 * @param key         object key the caller persists once the upload is confirmed
 * @param contentType the {@code Content-Type} the client must send — it is bound into the
 *                    signature, so a mismatch is rejected by the bucket
 * @param expiresAt   when {@code url} stops working
 */
public record PresignedUpload(
        String url,
        String key,
        String contentType,
        Instant expiresAt) {
}
