package com.lagu.platform.record.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** Adds an uploaded object to a gallery, once the PUT to the presigned URL has succeeded. */
@Data
public class GalleryItemConfirmRequest {

    /** The key returned by the upload-url call. */
    @NotBlank
    private String key;

    /** Shown under the photo and used as its alt text. Optional. */
    private String caption;

    /**
     * Make this the cover photo. When the gallery is empty the first item becomes the cover
     * regardless, since a gallery with photos always has exactly one.
     */
    private boolean primary;
}
