package com.lagu.platform.event.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateJoinRequestRequest {

    private String requestedRole = "INVITEE";

    @Size(max = 500)
    private String message;
}
