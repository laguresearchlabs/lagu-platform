package com.lagu.platform.notification.service;

import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Regression coverage for a real ordering bug: dryRun was checked before the recipient-address
 * check, so a dry-run with no recipient reported "sent" (true) instead of surfacing the
 * misconfiguration. Also covers the "disabled" path staying observable.
 */
class EmailDeliveryServiceTest {

    private final JavaMailSender mailSender = mock(JavaMailSender.class);
    private final EmailDeliveryService service = new EmailDeliveryService(mailSender);

    private void configure(boolean enabled, boolean dryRun) {
        ReflectionTestUtils.setField(service, "enabled", enabled);
        ReflectionTestUtils.setField(service, "dryRun", dryRun);
        ReflectionTestUtils.setField(service, "from", "noreply@lagu.platform");
    }

    @Test
    void disabledReturnsFalseWithoutTouchingMailSender() {
        configure(false, true);

        assertThat(service.send("a@b.com", "Subject", "Body")).isFalse();
        verifyNoInteractions(mailSender);
    }

    @Test
    void dryRunWithBlankRecipientReturnsFalseNotTrue() {
        // The bug: dryRun used to be checked first, so this returned true (a false "success").
        configure(true, true);

        assertThat(service.send(null, "Subject", "Body")).isFalse();
        assertThat(service.send("   ", "Subject", "Body")).isFalse();
        verifyNoInteractions(mailSender);
    }

    @Test
    void dryRunWithValidRecipientReturnsTrueWithoutSending() {
        configure(true, true);

        assertThat(service.send("a@b.com", "Subject", "Body")).isTrue();
        verifyNoInteractions(mailSender);
    }

    @Test
    void realSendWithBlankRecipientReturnsFalse() {
        configure(true, false);

        assertThat(service.send("", "Subject", "Body")).isFalse();
        verifyNoInteractions(mailSender);
    }

    @Test
    void realSendDelegatesToMailSender() {
        configure(true, false);

        assertThat(service.send("a@b.com", "Subject", "Body")).isTrue();
        verify(mailSender).send(any(org.springframework.mail.SimpleMailMessage.class));
    }

    @Test
    void mailSenderFailureThrowsRatherThanSwallowing() {
        configure(true, false);
        doThrow(new org.springframework.mail.MailSendException("smtp down"))
                .when(mailSender).send(any(org.springframework.mail.SimpleMailMessage.class));

        org.junit.jupiter.api.Assertions.assertThrows(EmailDeliveryException.class,
                () -> service.send("a@b.com", "Subject", "Body"));
    }
}
