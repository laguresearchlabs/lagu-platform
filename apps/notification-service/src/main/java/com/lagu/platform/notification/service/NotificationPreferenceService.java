package com.lagu.platform.notification.service;

import com.lagu.platform.common.exception.PlatformException;
import com.lagu.platform.notification.domain.NotificationCategory;
import com.lagu.platform.notification.domain.UserNotificationPreference;
import com.lagu.platform.notification.domain.UserNotificationPreferenceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Resolves what a recipient has agreed to receive.
 *
 * Stored rows are sparse overrides; everything else falls back to {@link #DEFAULTS}. Callers
 * should never read the repository directly — the merge is the whole point, and a raw lookup
 * that misses returns "no row" rather than "the default", which is a different answer.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationPreferenceService {

    /** Whether a category is delivered when the user has expressed no preference. */
    public record Setting(boolean inApp, boolean email) {}

    /**
     * MARKETING email defaults to OFF. In several jurisdictions marketing email is opt-in
     * rather than opt-out, so defaulting it on would make the platform's first send the
     * violation. Everything else defaults on — a user who has never touched the screen still
     * gets told they were invited to something.
     */
    static final Map<NotificationCategory, Setting> DEFAULTS = new EnumMap<>(Map.of(
            NotificationCategory.EVENT_INVITES,   new Setting(true, true),
            NotificationCategory.EVENT_REMINDERS, new Setting(true, true),
            NotificationCategory.EVENT_UPDATES,   new Setting(true, true),
            NotificationCategory.MARKETING,       new Setting(true, false),
            NotificationCategory.TRANSACTIONAL,   new Setting(true, true)
    ));

    private final UserNotificationPreferenceRepository repo;

    /**
     * What this user should actually receive for this category.
     *
     * TRANSACTIONAL always resolves to fully-on without consulting storage, so account-critical
     * mail cannot be switched off by a stray row. A null userId — an EMAIL-only notification to
     * an address with no platform account — has no preferences to consult and resolves to on;
     * suppressing those would silently break flows like booking confirmations to guests.
     */
    @Transactional(readOnly = true)
    public Setting effective(UUID userId, NotificationCategory category) {
        if (category == null || !category.isOptional() || userId == null) {
            return new Setting(true, true);
        }
        return repo.findByUserIdAndCategory(userId, category)
                .map(p -> new Setting(p.isInApp(), p.isEmail()))
                .orElseGet(() -> DEFAULTS.getOrDefault(category, new Setting(true, true)));
    }

    /** Defaults merged with this user's overrides, for every category they can control. */
    @Transactional(readOnly = true)
    public Map<NotificationCategory, Setting> effectiveForUser(UUID userId) {
        Map<NotificationCategory, Setting> out = new LinkedHashMap<>();
        for (NotificationCategory c : NotificationCategory.values()) {
            if (!c.isOptional()) continue;   // never offered as a toggle
            out.put(c, DEFAULTS.getOrDefault(c, new Setting(true, true)));
        }
        for (UserNotificationPreference p : repo.findByUserId(userId)) {
            if (p.getCategory() != null && p.getCategory().isOptional()) {
                out.put(p.getCategory(), new Setting(p.isInApp(), p.isEmail()));
            }
        }
        return out;
    }

    /**
     * Upserts the supplied overrides. Categories absent from the request are left as they were,
     * so a client can send a single toggle without having to echo the rest back.
     */
    @Transactional
    public Map<NotificationCategory, Setting> update(UUID userId, Map<NotificationCategory, Setting> changes) {
        if (userId == null) {
            throw new PlatformException("USER_CONTEXT_REQUIRED",
                    "A signed-in user is required to change notification preferences", HttpStatus.UNAUTHORIZED);
        }
        changes.forEach((category, setting) -> {
            // Rejected rather than ignored: a silently dropped toggle is exactly the failure
            // this feature exists to prevent.
            if (category == null || !category.isOptional()) {
                throw new PlatformException("CATEGORY_NOT_CONFIGURABLE",
                        "Category " + category + " cannot be switched off", HttpStatus.BAD_REQUEST);
            }
            UserNotificationPreference row = repo.findByUserIdAndCategory(userId, category)
                    .orElseGet(() -> {
                        UserNotificationPreference fresh = new UserNotificationPreference();
                        fresh.setUserId(userId);
                        fresh.setCategory(category);
                        return fresh;
                    });
            row.setInApp(setting.inApp());
            row.setEmail(setting.email());
            repo.save(row);
        });
        return effectiveForUser(userId);
    }

    /** Categories a client may set. */
    public static List<NotificationCategory> configurableCategories() {
        return java.util.Arrays.stream(NotificationCategory.values())
                .filter(NotificationCategory::isOptional)
                .toList();
    }
}
