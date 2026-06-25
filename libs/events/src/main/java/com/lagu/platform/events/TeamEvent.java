package com.lagu.platform.events;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
public class TeamEvent implements PlatformEvent {

    private String  eventType;   // MEMBER_ADDED, MEMBER_REMOVED, ROLE_ASSIGNED, ROLE_REVOKED
    private UUID    orgId;
    private UUID    groupId;
    private UUID    userId;
    private String  roleName;
    private Instant occurredAt;

    @Override public String getEventType()  { return eventType; }
    @Override public UUID getOrgId()        { return orgId; }
    @Override public Instant getOccurredAt(){ return occurredAt; }
}
