package com.lagu.platform.events;

import java.time.Instant;
import java.util.UUID;

public interface PlatformEvent {
    String getEventType();
    UUID getOrgId();
    Instant getOccurredAt();
}
