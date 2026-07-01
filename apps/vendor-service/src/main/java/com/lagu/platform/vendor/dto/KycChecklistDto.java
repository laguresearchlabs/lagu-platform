package com.lagu.platform.vendor.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class KycChecklistDto {
    private boolean hasGstDoc;
    private boolean hasPanDoc;
    private boolean hasBankDoc;
    private boolean hasIdentityDoc;
    private boolean businessNameFilled;
    private boolean addressFilled;
    private boolean phoneFilled;
    private boolean kycReady;
}
