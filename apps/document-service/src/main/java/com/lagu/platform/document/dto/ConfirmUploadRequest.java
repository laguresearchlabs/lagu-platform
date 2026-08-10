package com.lagu.platform.document.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

/**
 * Step 3 of the upload: the client reports that the PUT succeeded, and the document row is
 * created only now — so a client that walks away mid-upload leaves an orphaned object rather
 * than a half-built document record.
 *
 * <p>The document type fields are re-sent and re-validated rather than being carried over from
 * step 1: nothing server-side ties this call to the earlier one, so treating step 1's
 * declarations as settled would let a client request a URL for a permitted type and then
 * confirm it as something else.
 */
@Data
public class ConfirmUploadRequest {

    /** The key returned by the upload-url call. */
    @NotBlank
    private String key;

    @NotBlank
    private String documentType;

    private String identitySubType;

    private String listingType;

    @NotBlank
    private String fileName;

    @NotBlank
    private String contentType;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate expiryDate;
}
