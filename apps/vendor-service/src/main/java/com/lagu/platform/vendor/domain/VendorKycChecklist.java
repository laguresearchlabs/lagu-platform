package com.lagu.platform.vendor.domain;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "vendor_kyc_checklist")
@Data
public class VendorKycChecklist {

    @Id
    private UUID orgId;

    @Column(name = "has_gst_doc")
    private boolean hasGstDoc;

    @Column(name = "has_pan_doc")
    private boolean hasPanDoc;

    @Column(name = "has_bank_doc")
    private boolean hasBankDoc;

    @Column(name = "has_identity_doc")
    private boolean hasIdentityDoc;

    @Column(name = "business_name_filled")
    private boolean businessNameFilled;

    @Column(name = "address_filled")
    private boolean addressFilled;

    @Column(name = "phone_filled")
    private boolean phoneFilled;

    @Column(name = "kyc_ready")
    private boolean kycReady;

    @Column(name = "last_computed_at", nullable = false)
    private Instant lastComputedAt = Instant.now();
}
