package com.lagu.platform.vendor.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class UpdateVendorMemberRoleRequest {

    @NotBlank
    private String role; // OWNER | ADMIN | MEMBER
}
