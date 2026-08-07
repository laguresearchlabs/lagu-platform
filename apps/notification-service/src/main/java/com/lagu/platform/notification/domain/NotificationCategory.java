package com.lagu.platform.notification.domain;

import java.util.Arrays;
import java.util.Optional;

/**
 * What a notification is *about*, from the recipient's point of view — the axis a user can
 * switch off. Distinct from {@code triggerName}, which identifies the automation that produced
 * it and is an internal identifier the user never sees.
 *
 * An automation declares its category in its action config; it arrives in the
 * SEND_NOTIFICATION payload untouched, because ActionExecutor forwards the whole config.
 * See todo/19-notification-preferences.md.
 */
public enum NotificationCategory {

    /** Someone invited the recipient to an event. */
    EVENT_INVITES,

    /** Time-based nudge about an event the recipient already joined. */
    EVENT_REMINDERS,

    /** Something changed about an event the recipient is attending. */
    EVENT_UPDATES,

    /** Product announcements and feature news. Opt-in for email — see the defaults. */
    MARKETING,

    /**
     * Account- or transaction-critical: OTPs, password resets, booking confirmations.
     * Never suppressed by a preference, and never offered as a toggle. This is also the
     * fallback for a notification that declares no category, so that an automation nobody has
     * labelled yet keeps reaching people rather than silently disappearing.
     */
    TRANSACTIONAL;

    /** True when this category can be switched off by the recipient. */
    public boolean isOptional() {
        return this != TRANSACTIONAL;
    }

    /**
     * Lenient parse for values arriving from an automation's config, which is data an admin
     * typed rather than a compile-time constant. An unrecognised value is not an error here —
     * it resolves to TRANSACTIONAL at the call site, i.e. it gets delivered.
     */
    public static Optional<NotificationCategory> parse(String raw) {
        if (raw == null || raw.isBlank()) return Optional.empty();
        String needle = raw.trim().toUpperCase();
        return Arrays.stream(values()).filter(c -> c.name().equals(needle)).findFirst();
    }
}
