package com.lagu.platform.event.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** Step 3: the client reports that the PUT succeeded, and the photo joins the album. */
@Data
public class ConfirmEventPhotoRequest {

    /** The key returned by the upload-url call. */
    @NotBlank
    private String key;

    /** PUBLIC (default) or PRIVATE. */
    private String visibility;

    private String caption;
}
