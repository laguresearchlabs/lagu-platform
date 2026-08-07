package com.lagu.platform.notification.service;

import com.lagu.platform.common.exception.PlatformException;
import com.lagu.platform.notification.domain.NotificationCategory;
import com.lagu.platform.notification.domain.UserNotificationPreference;
import com.lagu.platform.notification.domain.UserNotificationPreferenceRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * The rules that decide whether someone hears from us. The defaults matter as much as the
 * overrides: a missing row is the common case, not an edge case.
 */
class NotificationPreferenceServiceTest {

    private final UserNotificationPreferenceRepository repo = mock(UserNotificationPreferenceRepository.class);
    private final NotificationPreferenceService service = new NotificationPreferenceService(repo);

    private static UserNotificationPreference row(UUID userId, NotificationCategory c, boolean inApp, boolean email) {
        UserNotificationPreference p = new UserNotificationPreference();
        p.setUserId(userId);
        p.setCategory(c);
        p.setInApp(inApp);
        p.setEmail(email);
        return p;
    }

    @Test
    void absentRowFallsBackToPlatformDefault() {
        UUID user = UUID.randomUUID();
        when(repo.findByUserIdAndCategory(user, NotificationCategory.EVENT_INVITES)).thenReturn(Optional.empty());

        var s = service.effective(user, NotificationCategory.EVENT_INVITES);

        assertThat(s.inApp()).isTrue();
        assertThat(s.email()).isTrue();
    }

    @Test
    void marketingEmailIsOffByDefaultBecauseItIsOptIn() {
        UUID user = UUID.randomUUID();
        when(repo.findByUserIdAndCategory(user, NotificationCategory.MARKETING)).thenReturn(Optional.empty());

        var s = service.effective(user, NotificationCategory.MARKETING);

        assertThat(s.inApp()).isTrue();
        assertThat(s.email()).isFalse();
    }

    @Test
    void storedOverrideBeatsTheDefault() {
        UUID user = UUID.randomUUID();
        when(repo.findByUserIdAndCategory(user, NotificationCategory.EVENT_REMINDERS))
                .thenReturn(Optional.of(row(user, NotificationCategory.EVENT_REMINDERS, false, false)));

        var s = service.effective(user, NotificationCategory.EVENT_REMINDERS);

        assertThat(s.inApp()).isFalse();
        assertThat(s.email()).isFalse();
    }

    @Test
    void transactionalIsAlwaysOnAndNeverTouchesStorage() {
        UUID user = UUID.randomUUID();

        var s = service.effective(user, NotificationCategory.TRANSACTIONAL);

        assertThat(s.inApp()).isTrue();
        assertThat(s.email()).isTrue();
        verify(repo, never()).findByUserIdAndCategory(any(), any());
    }

    @Test
    void nullUserResolvesToDeliverBecauseThereAreNoPreferencesToConsult() {
        var s = service.effective(null, NotificationCategory.MARKETING);

        assertThat(s.inApp()).isTrue();
        assertThat(s.email()).isTrue();
        verify(repo, never()).findByUserIdAndCategory(any(), any());
    }

    @Test
    void effectiveForUserMergesDefaultsWithOverridesAndHidesTransactional() {
        UUID user = UUID.randomUUID();
        when(repo.findByUserId(user))
                .thenReturn(List.of(row(user, NotificationCategory.EVENT_UPDATES, false, false)));

        var all = service.effectiveForUser(user);

        assertThat(all).doesNotContainKey(NotificationCategory.TRANSACTIONAL);
        assertThat(all.get(NotificationCategory.EVENT_UPDATES).email()).isFalse();   // override
        assertThat(all.get(NotificationCategory.EVENT_INVITES).email()).isTrue();    // default
        assertThat(all.get(NotificationCategory.MARKETING).email()).isFalse();       // default
    }

    @Test
    void updateRejectsTransactionalRatherThanIgnoringIt() {
        UUID user = UUID.randomUUID();

        assertThatThrownBy(() -> service.update(user,
                Map.of(NotificationCategory.TRANSACTIONAL, new NotificationPreferenceService.Setting(false, false))))
                .isInstanceOf(PlatformException.class);

        verify(repo, never()).save(any());
    }

    @Test
    void updateUpsertsAnExistingRowRatherThanCreatingADuplicate() {
        UUID user = UUID.randomUUID();
        UserNotificationPreference existing = row(user, NotificationCategory.MARKETING, true, true);
        when(repo.findByUserIdAndCategory(user, NotificationCategory.MARKETING)).thenReturn(Optional.of(existing));
        when(repo.findByUserId(user)).thenReturn(List.of(existing));

        service.update(user, Map.of(NotificationCategory.MARKETING,
                new NotificationPreferenceService.Setting(true, false)));

        verify(repo).save(argThat(p -> p == existing && !p.isEmail() && p.isInApp()));
    }

    @Test
    void updateRequiresASignedInUser() {
        assertThatThrownBy(() -> service.update(null,
                Map.of(NotificationCategory.MARKETING, new NotificationPreferenceService.Setting(true, true))))
                .isInstanceOf(PlatformException.class);
    }
}
