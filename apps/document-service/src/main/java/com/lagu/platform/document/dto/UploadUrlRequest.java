package com.lagu.platform.document.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import lombok.Data;

/**
 * Step 1 of the upload: everything needed to authorize the upload and mint a presigned PUT.
 *
 * <p>Every field here is a client <em>declaration</em>. They are validated so an obviously bad
 * upload is rejected before any bytes move, but none of them are trusted — the object is
 * re-checked against its actual stored bytes at confirm time.
 */
@Data
public class UploadUrlRequest {

    @NotBlank
    private String documentType;

    /** Required when documentType=IDENTITY_PROOF. */
    private String identitySubType;

    /** Unlocks a listing type's own document types in addition to the generic/HR set. */
    private String listingType;

    @NotBlank
    private String fileName;

    @NotBlank
    private String contentType;

    @Positive
    private long sizeBytes;
}
