package com.lagu.platform.vendor.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.UUID;

/**
 * The one org member another service should address when it has something to tell "the vendor",
 * plus where to email them.
 *
 * Separate from {@link MembershipRoleResponse}, which answers "what role does this known user
 * have" — here the user id is the answer, not the question.
 */
@Data
@AllArgsConstructor
public class MembershipOwnerResponse {

    private UUID userId;

    private String role;

    /**
     * The org's **business** contact email, read from the `email` field of its VENDOR record —
     * not the owner's login address, which vendor-service does not hold and which lives in IAM
     * behind user-authenticated endpoints only.
     *
     * Business contact is also the better address on the merits: bookings should reach whoever
     * the vendor nominated to handle enquiries, not whichever personal account happened to
     * register the org.
     *
     * Null when the field was left blank — it is optional on the VENDOR schema — or when
     * record-service could not be reached. Callers must treat email as best-effort.
     */
    private String contactEmail;
}
