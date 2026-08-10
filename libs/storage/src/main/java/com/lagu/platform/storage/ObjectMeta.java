package com.lagu.platform.storage;

import java.time.Instant;

/**
 * Server-side metadata for a stored object.
 *
 * <p>{@code sizeBytes} is authoritative — the bucket measured it. {@code contentType} is not:
 * it is whatever the client sent on the PUT. Treat it as a declaration to be checked against
 * the actual bytes, not as evidence.
 */
public record ObjectMeta(
        String key,
        long sizeBytes,
        String contentType,
        Instant createdAt) {
}
