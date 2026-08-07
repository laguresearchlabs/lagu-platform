package com.lagu.platform.notification.dto;

import com.lagu.platform.notification.domain.NotificationCategory;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** One category's effective delivery setting for the calling user. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class NotificationPreferenceDto {
    private NotificationCategory category;
    private boolean inApp;
    private boolean email;
}
