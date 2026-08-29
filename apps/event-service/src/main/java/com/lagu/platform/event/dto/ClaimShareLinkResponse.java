package com.lagu.platform.event.dto;

import lombok.Builder;
import lombok.Data;

import java.util.UUID;

/** What redeeming a share link did, so the client knows which screen to show next. */
@Data
@Builder
public class ClaimShareLinkResponse {

    private UUID eventId;

    /**
     * JOINED         — an accepted membership now exists; go to the event.
     * REQUESTED      — a join request is pending a host's approval.
     * ALREADY_MEMBER — nothing to do; go to the event.
     */
    private String outcome;

    private String role;
}
