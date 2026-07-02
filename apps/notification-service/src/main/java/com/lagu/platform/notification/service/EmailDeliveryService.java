package com.lagu.platform.notification.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmailDeliveryService {

    private final JavaMailSender mailSender;

    @Value("${platform.notification.email.enabled:false}")
    private boolean enabled;

    @Value("${platform.notification.email.dry-run:true}")
    private boolean dryRun;

    @Value("${platform.notification.email.from:noreply@lagu.platform}")
    private String from;

    /**
     * Sends a plain-text email. Returns true if the email was sent (or dry-run logged).
     * Returns false if email is disabled or an error occurs.
     */
    public boolean send(String to, String subject, String body) {
        if (!enabled) {
            log.debug("Email delivery disabled — skipping email to {}", to);
            return false;
        }
        if (dryRun) {
            log.info("[EMAIL DRY-RUN] To: {} | Subject: {} | Body: {}", to, subject, body);
            return true;
        }
        if (to == null || to.isBlank()) {
            log.warn("No recipient email address provided — skipping email");
            return false;
        }

        SimpleMailMessage msg = new SimpleMailMessage();
        msg.setFrom(from);
        msg.setTo(to);
        msg.setSubject(subject);
        msg.setText(body);
        try {
            mailSender.send(msg);
        } catch (Exception e) {
            // Propagate instead of swallowing: NotificationDeliveryService.deliver() runs inside
            // the Kafka listener's try/catch, which rethrows to the DefaultErrorHandler so the
            // message is retried and, on repeated failure, routed to the DLT. Silently returning
            // false here would ack the message as delivered while the email was actually dropped.
            log.error("Failed to send email to {}: {}", to, e.getMessage(), e);
            throw new EmailDeliveryException("Failed to send email to " + to, e);
        }
        log.info("Email sent to {} subject='{}'", to, subject);
        return true;
    }
}
