package com.lagu.platform.event.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.UUID;

@Data
public class InviteMemberRequest {

    @NotNull
    private UUID userId;

    /** ADMIN | MAINTAINER | INVITEE — defaults to INVITEE. */
    private String role = "INVITEE";

    private String guestNote;
}
