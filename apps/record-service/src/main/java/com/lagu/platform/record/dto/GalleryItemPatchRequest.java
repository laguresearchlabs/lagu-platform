package com.lagu.platform.record.dto;

import lombok.Data;

/**
 * Edits one gallery item in place. Both fields are optional and independent: omitting
 * {@code caption} leaves the existing one alone rather than clearing it, so a client setting
 * the cover photo does not have to resend text it never touched.
 *
 * <p>Send an empty string to clear a caption.
 */
@Data
public class GalleryItemPatchRequest {

    private String caption;

    /** True promotes this item to cover photo. False is ignored — a gallery with photos always
     *  has exactly one cover, so demoting is done by promoting something else. */
    private Boolean primary;
}
