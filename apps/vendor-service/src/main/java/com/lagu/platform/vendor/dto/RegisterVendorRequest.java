package com.lagu.platform.vendor.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class RegisterVendorRequest {

    @NotBlank
    @Size(max = 255)
    private String businessName;

    @NotBlank
    @Size(max = 10)
    private String country = "IN";

    /** Primary vendor type for the initial listing (VENUE, PHOTOGRAPHER, etc.). */
    private String primaryVendorType;
}
