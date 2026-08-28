package com.lagu.platform.record.service;

import com.lagu.platform.common.exception.PlatformException;
import com.lagu.platform.record.domain.Record;
import org.springframework.http.HttpStatus;

/**
 * Refuses media edits to a record whose workflow state holds changes for review.
 *
 * <p>The change-approval gate in {@link RecordService#update} routes a held edit into a change set.
 * The media paths cannot do that: {@code RecordFileController} and {@code RecordGalleryController}
 * write {@code record.setData(...)} and save directly, and a change set carries field *values*, not
 * an object lifecycle — the bytes are already in the bucket by the time a confirm arrives, and the
 * gallery mutations reorder and delete objects rather than proposing a new value for one field.
 *
 * <p>So they refuse rather than queue. The consequence is deliberate and worth stating: a vendor
 * whose listing sits in a gated state cannot change its images at all until an admin moves it out
 * of that state. That is the point of gating the state — the alternative, which is what shipped
 * before this, was a gate that held the text of a listing while its photographs could be swapped
 * for anything at will.
 *
 * <p>Applied at two depths on purpose: at {@code upload-url}, so a client is told before it spends
 * bandwidth pushing bytes to a bucket that will never reference them, and again at the write
 * itself, because the presigned URL outlives the check that issued it.
 */
public final class MediaGate {

    private MediaGate() {
    }

    public static void requireEditable(RecordService recordService, Record record) {
        if (recordService.editsRequireApproval(record)) {
            throw new PlatformException("EDIT_REQUIRES_APPROVAL",
                    "This listing is in a state where changes need admin approval, so its files "
                            + "and photos cannot be changed here. Edit the listing details to send "
                            + "a change request for review, or ask an admin to move it out of "
                            + record.getStatus() + ".",
                    HttpStatus.CONFLICT);
        }
    }
}
