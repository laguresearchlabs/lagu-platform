package com.lagu.platform.schema.dto;

import com.lagu.platform.schema.client.SchemaConsumerClient;

import java.util.List;

/**
 * Whether the published schema has actually reached the services that validate against it.
 *
 * <p>Publishing writes an event to the outbox and returns. Consumers evict their caches when the
 * event arrives, and fall back to a ten-minute TTL when it does not. Both paths end in the right
 * place, but neither told the person who pressed Publish anything — so a publish followed by an
 * immediate test looked exactly like a publish that had failed.
 *
 * @param propagationSeconds worst case if the eviction event is missed entirely. Not a promise that
 *                           it will take this long: the usual path is immediate.
 */
public record SchemaPropagationResponse(
        String listingType,
        int currentVersion,
        boolean allInSync,
        long propagationSeconds,
        List<SchemaConsumerClient.ConsumerState> consumers
) {}
