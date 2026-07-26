package com.lagu.platform.event.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class UpdateMemberRoleRequest {

    @NotBlank
    private String role; // ADMIN | MAINTAINER | INVITEE
}
