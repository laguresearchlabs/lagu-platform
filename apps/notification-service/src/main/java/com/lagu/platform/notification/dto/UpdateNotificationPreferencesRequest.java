package com.lagu.platform.notification.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * Partial update: only the categories present are changed. Sending one toggle does not reset
 * the others.
 */
@Data
public class UpdateNotificationPreferencesRequest {

    @NotEmpty(message = "at least one preference is required")
    @Size(max = 20, message = "at most 20 preferences")
    @Valid
    private List<Entry> preferences;

    @Data
    public static class Entry {
        /**
         * Typed as String rather than the enum so an unknown value produces a 400 naming the
         * offending category, instead of Jackson's generic deserialization error. Parsed in
         * the controller.
         */
        @NotNull(message = "category is required")
        private String category;

        @NotNull(message = "inApp is required")
        private Boolean inApp;

        @NotNull(message = "email is required")
        private Boolean email;
    }
}
