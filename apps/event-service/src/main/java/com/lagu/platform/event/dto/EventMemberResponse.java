package com.lagu.platform.event.dto;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@Builder
public class EventMemberResponse {
    private UUID id;
    private UUID userId;
    private String role;
    private String status;

    /**
     * The host's own note about this guest, written at invite time. Managers only — it is what
     * the host thought worth recording about someone, not a fact about the guest, and the member
     * list went to every non-removed membership row with it attached.
     */
    private String guestNote;

    /**
     * Whether this member has muted the event's notifications. Their own preference, so it is
     * populated on their own row and null on everyone else's — a boxed Boolean rather than a
     * primitive precisely so "not yours to know" and "not muted" stay different answers.
     */
    private Boolean muted;

    private UUID invitedBy;
    private OffsetDateTime joinedAt;
}
