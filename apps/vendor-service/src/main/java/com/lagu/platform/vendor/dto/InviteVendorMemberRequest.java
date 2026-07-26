package com.lagu.platform.vendor.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.UUID;

@Data
public class InviteVendorMemberRequest {

    @NotNull
    private UUID userId;

    /** OWNER | ADMIN | MEMBER — defaults to MEMBER. */
    private String role = "MEMBER";
}
