package com.lagu.platform.record.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** Step 3: the key returned by the upload-url call, once the PUT has succeeded. */
@Data
public class FileConfirmRequest {

    @NotBlank
    private String key;
}
