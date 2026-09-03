package com.lagu.platform.event.dto;

import lombok.Data;

/**
 * Inviting a contact rather than a user id — see EventInvitationService.
 *
 * <p>Exactly one of {@code email} or {@code phone} is required, and that is enforced in the
 * service rather than with bean-validation annotations here — same reasoning as {@code role}:
 * "email is required" and "phone is required" can't both be a field-level {@code @NotBlank}
 * without rejecting every request of the kind the other one is for.
 *
 * <p>Role is optional and defaults to INVITEE. It is validated against the assignable set in the
 * service rather than here, so the one place that decides what a form may mint stays one place.
 */
@Data
public class CreateInvitationRequest {
    private String email;
    private String phone;
    /** Dial code, e.g. "+1" — display-only; the service folds it into the stored phone itself. */
    private String countryCode;
    private String role;
}
