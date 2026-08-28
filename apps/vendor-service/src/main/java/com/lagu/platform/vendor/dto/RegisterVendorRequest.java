package com.lagu.platform.vendor.dto;

import jakarta.validation.constraints.Email;
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

    /**
     * Where the business wants booking mail sent, written onto the VENDOR record's `email` field.
     *
     * Optional, but supplying it matters more than it looks: booking-service resolves the vendor's
     * notification address from that field, so an org that registers without one gets in-app
     * notifications and no email until somebody fills in Business Profile. Every vendor used to
     * start that way, because registration had nowhere to put an address.
     */
    @Email
    @Size(max = 255)
    private String contactEmail;
}
