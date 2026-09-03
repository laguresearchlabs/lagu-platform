package com.lagu.platform.event.dto;

import lombok.Data;

import java.time.Instant;
import java.util.UUID;

/**
 * An outstanding invitation, as a host sees it.
 *
 * <p>Carries {@code contact} rather than the email and phone columns separately: the caller
 * renders one line of text either way, and a DTO that exposes both invites a UI to decide which
 * kind of contact this is — a decision that belongs to whoever wrote the row.
 */
@Data
public class EventInvitationResponse {
    private UUID id;
    private UUID eventId;
    /** The address or number this was sent to. */
    private String contact;
    private String role;
    private String status;
    private UUID invitedBy;
    private Instant createdAt;
}
