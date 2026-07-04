package com.lagu.platform.common.outbox;

import java.util.UUID;

/** An unpublished outbox row claimed by {@link OutboxRelay} for delivery. */
public record OutboxRow(UUID id, String topic, String eventKey, String payloadType,
                        String payload, int attempts) {
}
